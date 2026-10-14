package service.impl;

import com.google.gson.Gson;
import model.GameEvent;
import model.NotificationMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import util.LoopHealth;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * With one Stage 2 replica, a loop that dies stops every notification while the pod looks fine.
 * The loop catches RuntimeException and retries; anything else (an Error) ends the thread, and the
 * health check must say so.
 */
class WorkerLoopHealthTest {

    private static final TopicPartition TP = new TopicPartition("notifications", 0);

    @Test
    void healthyWhileRunningUnhealthyAfterTheLoopDies() throws Exception {
        MockConsumer<String, String> consumer = new MockConsumer<>("earliest");
        consumer.assign(List.of(TP));
        consumer.updateBeginningOffsets(Map.of(TP, 0L));
        Delivery dies = new Delivery(null) {
            @Override public List<Boolean> deliverAll(List<NotificationMessage> batch) { throw new AssertionError("loop-killing error"); }
        };
        Stage2Worker worker = new Stage2Worker(consumer, dies);
        LoopHealth health = new LoopHealth(System::currentTimeMillis, 60_000);
        Thread loop = new Thread(() -> worker.run(health));
        health.attach(loop);
        loop.start();

        long deadline = System.currentTimeMillis() + 5_000;
        while (!health.isHealthy() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(health.isHealthy(), "polling an empty topic is healthy");

        GameEvent play = new GameEvent("nba-1", "nba-1:1", "SCORE_UPDATE", "LAL", 1L, "x",
            "LAL", "BOS", 1, 0, "IN_PROGRESS", "NBA", 10).withVersion(1);
        consumer.schedulePollTask(() -> consumer.addRecord(
            new ConsumerRecord<>(TP.topic(), 0, 0L, "u1", new Gson().toJson(NotificationMessage.of(play, "u1")))));
        loop.join(5_000);
        assertFalse(loop.isAlive(), "the Error ended the loop");
        assertFalse(health.isHealthy());
    }
}
