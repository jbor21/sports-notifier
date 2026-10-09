package checker;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import testsupport.KafkaTestSupport;
import util.KafkaSettings;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Group lag as the end condition reads it (Phase 4.1 spec, 5). */
class LagTest {

    @Test
    void endOffsetMinusCommittedPerPartition() throws Exception {
        String topic = "lag-" + UUID.randomUUID();
        String group = "g-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(topic, 2);
        try (KafkaProducer<String, String> p = new KafkaProducer<>(KafkaSettings.ingestProducer(KafkaTestSupport.bootstrap()))) {
            for (int i = 0; i < 5; i++) p.send(new ProducerRecord<>(topic, 0, "k", "v" + i)).get();
        }
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.bootstrap()))) {
            Map<String, Long> before = Lag.read(admin, Map.of(group, topic));
            assertEquals(Map.of(group + "/" + topic + "-0", 5L, group + "/" + topic + "-1", 0L), before,
                "a group that never committed has every record ahead of it");
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(KafkaSettings.consumer(KafkaTestSupport.bootstrap(), group))) {
                c.commitSync(Map.of(new TopicPartition(topic, 0), new OffsetAndMetadata(3)));
            }
            assertEquals(2L, Lag.read(admin, Map.of(group, topic)).get(group + "/" + topic + "-0"));
        }
    }
}
