package testsupport;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import util.KafkaSettings;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class KafkaTestSupportTest {

    @Test
    void producedRecordIsReadBack() throws Exception {
        String topic = "game-events-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(topic, 12);

        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(KafkaSettings.ingestProducer(KafkaTestSupport.bootstrap()))) {
            producer.send(new ProducerRecord<>(topic, "nfl-1", "hello")).get(10, TimeUnit.SECONDS);
        }

        List<ConsumerRecord<String, String>> records = KafkaTestSupport.readAll(topic, Duration.ofSeconds(2));
        assertEquals(1, records.size());
        assertEquals("nfl-1", records.get(0).key());
        assertEquals("hello", records.get(0).value());
    }

    @Test
    void pausedBrokerFailsSendsUntilUnpaused() throws Exception {
        String topic = "game-events-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(topic, 12);
        Properties props = KafkaSettings.ingestProducer(KafkaTestSupport.bootstrap());

        // A fresh producer has no metadata yet, so with the broker paused it blocks in send()
        // for max.block.ms (2 s) and then fails.
        KafkaTestSupport.pause();
        KafkaProducer<String, String> stuck = new KafkaProducer<>(props);
        try {
            long start = System.nanoTime();
            assertThrows(Exception.class, () ->
                    stuck.send(new ProducerRecord<>(topic, "nfl-1", "lost")).get(5, TimeUnit.SECONDS));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 5_000, "took " + elapsedMs + " ms");
        } finally {
            // Unpause first: even close(ZERO) waits for the producer's in-flight request to a
            // paused broker to time out (request.timeout.ms, 30 s).
            KafkaTestSupport.unpause();
            stuck.close(Duration.ZERO);
        }

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, "nfl-1", "kept")).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            fail("send after unpause failed: " + e.getCause());
        }
        List<ConsumerRecord<String, String>> records = KafkaTestSupport.readAll(topic, Duration.ofSeconds(2));
        assertEquals(List.of("kept"), records.stream().map(ConsumerRecord::value).toList());
    }
}
