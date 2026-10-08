package testsupport;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
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
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;
import java.util.Properties;

/**
 * One Kafka broker for the whole test JVM, started on first use (a few seconds). Testcontainers
 * removes it when the JVM exits. Tests share the broker, so each test
 * uses its own topic and group names.
 */
public final class KafkaTestSupport {

    /**
     * Same Kafka version as kafka-clients in pom.xml and the Strimzi broker in values.yaml. The JVM
     * image, not apache/kafka-native: the native build segfaulted at startup on GitHub's runners in
     * 2 of 3 runs on 2026-10-08 (issue #30), for a second or two saved per test JVM.
     */
    private static final String IMAGE = "apache/kafka:4.3.1";

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

    /**
     * Creates the topic and returns only when every partition's leader answers. "Created" comes back
     * before that, and a write to a partition whose leader isn't ready gets NOT_LEADER_OR_FOLLOWER:
     * the idempotent producer retries that batch, but a later batch may already have been accepted
     * as the partition's first, and the retry then fails with OutOfOrderSequence until the delivery
     * timeout (seen in CI on PR #13). Production topics are created by Strimzi long before anything
     * writes to them; tests write within milliseconds.
     */
    public static void createTopic(String name, int partitions) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
        try (Admin admin = Admin.create(p)) {
            admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("could not create topic " + name, e);
        }
        awaitLeaders(name, partitions);
    }

    /**
     * A latest-offset query is answered by each partition's leader: the readiness a write needs. The
     * admin client retries NOT_LEADER_OR_FOLLOWER itself until every leader answers, or 30 s pass.
     */
    private static void awaitLeaders(String topic, int partitions) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "30000");
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        for (int i = 0; i < partitions; i++) latest.put(new TopicPartition(topic, i), OffsetSpec.latest());
        try (Admin admin = Admin.create(p)) {
            admin.listOffsets(latest).all().get(40, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("topic " + topic + " still has partitions without a leader", e);
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
