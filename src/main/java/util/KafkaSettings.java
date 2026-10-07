package util;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Kafka client settings. Every producer and consumer in the app is built from these. */
public final class KafkaSettings {

    private KafkaSettings() {}

    public static String bootstrap() {
        return System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092");
    }

    /**
     * Event Processing's producer. max.block.ms is short because send() runs on the request
     * thread: with Kafka down, the play stays in the outbox and the poller gets an answer
     * within 2 s instead of hanging for the default minute.
     */
    public static Properties ingestProducer(String bootstrap) {
        Properties p = producerBase(bootstrap);
        p.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "2000");
        return p;
    }

    /** Stage 1's producer: one play becomes many messages, so a 5 ms batch is worth it. */
    public static Properties fanoutProducer(String bootstrap) {
        Properties p = producerBase(bootstrap);
        p.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        return p;
    }

    /** Offsets are committed by hand, after the work is done, so a crash re-reads instead of skipping. */
    public static Properties consumer(String bootstrap, String groupId) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, CooperativeStickyAssignor.class.getName());
        return p;
    }

    private static Properties producerBase(String bootstrap) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        return p;
    }

    /**
     * Blocks until Kafka answers, or exits the process after 60 s so Kubernetes restarts the pod.
     * Same reason as {@link JedisPoolHolder#awaitRedisOrExit}: on a fresh deploy services often
     * start before the broker.
     */
    public static void awaitKafkaOrExit(String serviceName) {
        String bootstrap = bootstrap();
        if (!waitForKafka(bootstrap, Duration.ofSeconds(60), Duration.ofSeconds(2), serviceName)) {
            System.err.println("[" + serviceName + "] Kafka at " + bootstrap + " not reachable after 60s, exiting");
            System.exit(1);
        }
    }

    private static boolean waitForKafka(String bootstrap, Duration max, Duration every, String serviceName) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "1000");
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "1000");
        long deadline = System.nanoTime() + max.toNanos();
        try (Admin admin = Admin.create(p)) {
            while (true) {
                try {
                    admin.describeCluster(new DescribeClusterOptions().timeoutMs(1000))
                            .clusterId().get(2, TimeUnit.SECONDS);
                    return true;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (Exception e) {
                    System.out.println("[" + serviceName + "] waiting for Kafka at " + bootstrap);
                }
                if (System.nanoTime() + every.toNanos() > deadline) return false;
                try {
                    Thread.sleep(every.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }
}
