package checker;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Consumer-group lag per partition: the topic's end offset minus what the group committed. */
public final class Lag {

    private Lag() {}

    /**
     * @param groups group id → the topic it reads
     * @return {@code group/topic-partition} → records not yet consumed; a partition the group never
     *         committed counts from 0
     */
    public static Map<String, Long> read(Admin admin, Map<String, String> groups) throws Exception {
        Map<String, Long> out = new TreeMap<>();
        for (Map.Entry<String, String> g : groups.entrySet()) {
            String topic = g.getValue();
            int partitions = admin.describeTopics(List.of(topic)).allTopicNames().get(10, TimeUnit.SECONDS)
                    .get(topic).partitions().size();
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            for (int p = 0; p < partitions; p++) latest.put(new TopicPartition(topic, p), OffsetSpec.latest());
            var ends = admin.listOffsets(latest).all().get(10, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(g.getKey())
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            for (TopicPartition tp : latest.keySet()) {
                OffsetAndMetadata c = committed.get(tp);
                long lag = ends.get(tp).offset() - (c == null ? 0 : c.offset());
                out.put(g.getKey() + "/" + tp, Math.max(0, lag));
            }
        }
        return out;
    }
}
