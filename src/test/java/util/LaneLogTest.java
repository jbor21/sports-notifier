package util;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LaneLogTest {

    @Test
    void revokedAndLostCallBack() {
        List<TopicPartition> dropped = new ArrayList<>();
        LaneLog log = new LaneLog("Stage1", (Collection<TopicPartition> lanes) -> dropped.addAll(lanes));
        TopicPartition a1 = new TopicPartition("a", 1), a2 = new TopicPartition("a", 2), a3 = new TopicPartition("a", 3);
        log.onPartitionsAssigned(List.of(a3));
        assertEquals(List.of(), dropped, "a new lane drops nothing");
        log.onPartitionsRevoked(List.of(a1));
        log.onPartitionsLost(List.of(a2));
        assertEquals(List.of(a1, a2), dropped);
    }

    @Test
    void describeSortsLanes() {
        assertEquals("[a-2, a-10, b-0]", LaneLog.describe(List.of(
            new TopicPartition("b", 0), new TopicPartition("a", 10), new TopicPartition("a", 2))));
    }
}
