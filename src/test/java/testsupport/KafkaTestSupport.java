package testsupport;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * One Kafka broker for the whole test JVM, started on first use (about a second for the native
 * image). Testcontainers removes it when the JVM exits. Tests share the broker, so each test
 * uses its own topic and group names.
 */
public final class KafkaTestSupport {

    /** Same Kafka version as kafka-clients in pom.xml and the Strimzi broker in values.yaml. */
    private static final String IMAGE = "apache/kafka-native:4.3.1";

    private static KafkaContainer broker;

    private KafkaTestSupport() {}

    public static synchronized String bootstrap() {
        if (broker == null) {
            KafkaContainer started = new KafkaContainer(IMAGE);
            started.start();
            broker = started;
        }
        return broker.getBootstrapServers();
    }

    public static void createTopic(String name, int partitions) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
        try (Admin admin = Admin.create(p)) {
            admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("could not create topic " + name, e);
        }
    }

    /**
     * Reads the topic from the beginning, every partition, for the whole of {@code wait}, and
     * returns everything seen. It always takes {@code wait}, so records produced asynchronously
     * during it are included.
     */
    public static List<ConsumerRecord<String, String>> readAll(String topic, Duration wait) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            // assign, not subscribe: no consumer group, so reading here never moves anyone's offsets.
            // A topic that was just created and never written can report no partitions for a moment.
            long deadline = System.nanoTime() + wait.toNanos();
            List<TopicPartition> partitions = List.of();
            while (partitions.isEmpty() && System.nanoTime() < deadline) {
                partitions = consumer.partitionsFor(topic).stream()
                        .map(info -> new TopicPartition(topic, info.partition()))
                        .toList();
            }
            if (partitions.isEmpty()) throw new IllegalStateException("topic " + topic + " has no partitions");
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            while (System.nanoTime() < deadline) {
                long leftMs = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
                consumer.poll(Duration.ofMillis(Math.min(200, leftMs))).forEach(out::add);
            }
        }
        return out;
    }

    /** Whether this JVM has started the broker (diagnostics only look at a broker that exists). */
    public static synchronized boolean started() {
        return broker != null;
    }

    /** The broker container's own log, for {@link KafkaDiagnostics}. */
    public static synchronized String brokerLogs() {
        return broker == null ? "" : broker.getLogs();
    }

    /** Freezes the broker's process (docker pause): connections stay open but nothing answers. */
    public static void pause() {
        bootstrap();
        DockerClientFactory.instance().client().pauseContainerCmd(broker.getContainerId()).exec();
    }

    public static void unpause() {
        bootstrap();
        DockerClientFactory.instance().client().unpauseContainerCmd(broker.getContainerId()).exec();
    }

    /** Safety net for test cleanup: unpause only if a failed test left the broker paused. */
    public static void unpauseIfPaused() {
        bootstrap();
        Boolean paused = DockerClientFactory.instance().client()
                .inspectContainerCmd(broker.getContainerId()).exec().getState().getPaused();
        if (Boolean.TRUE.equals(paused)) unpause();
    }
}
