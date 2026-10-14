package service.impl;

import com.google.gson.Gson;
import model.GameEvent;
import model.NotificationMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review finding I2: after a failed batch the worker must go back to the batch's start even when the
 * broker can't be asked anything. The failure that triggers a rewind is often Kafka being down, and
 * asking Kafka for the committed offsets then fails too, leaving the consumer past the failed batch.
 */
class RewindTest {

    private static final TopicPartition TP = new TopicPartition("notifications", 0);

    /** A consumer whose broker is unreachable for offset lookups, as during an outage. */
    static final class OutageConsumer extends MockConsumer<String, String> {
        OutageConsumer() { super("earliest"); }
        @Override public synchronized Map<TopicPartition, OffsetAndMetadata> committed(Set<TopicPartition> partitions) {
            throw new TimeoutException("broker unreachable");
        }
        @Override public synchronized Map<TopicPartition, OffsetAndMetadata> committed(Set<TopicPartition> partitions, Duration timeout) {
            throw new TimeoutException("broker unreachable");
        }
    }

    @Test
    void failedBatchRewindsWithoutAskingTheBroker() {
        OutageConsumer consumer = new OutageConsumer();
        consumer.assign(List.of(TP));
        consumer.updateBeginningOffsets(Map.of(TP, 0L));
        consumer.seek(TP, 5L);
        GameEvent play = new GameEvent("nba-1", "nba-1:1", "SCORE_UPDATE", "LAL", 1L, "x",
            "LAL", "BOS", 1, 0, "IN_PROGRESS", "NBA", 10).withVersion(1);
        for (long offset = 5; offset < 8; offset++) {
            consumer.addRecord(new ConsumerRecord<>(TP.topic(), 0, offset, "u1", new Gson().toJson(NotificationMessage.of(play, "u1"))));
        }
        Delivery fails = new Delivery(null) {
            @Override public List<Boolean> deliverAll(List<NotificationMessage> batch) { throw new IllegalStateException("redis down"); }
        };
        Stage2Worker worker = new Stage2Worker(consumer, fails);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> worker.pollOnce(Duration.ofMillis(10)));
        assertEquals("redis down", e.getMessage(), "the original failure, not the rewind's");
        assertEquals(5L, consumer.position(TP), "back at the batch's first record, so it is read again");
    }
}
