package checker;

import model.GameEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import util.GameEventParser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads one run's accepted plays back from {@code game-events}: every partition from the first record
 * at or after {@code sinceMillis} up to the end as it is when called. A game is one key, so its plays
 * come back in the order they were accepted. No consumer group: nothing is committed.
 */
public final class PlayReader {

    private PlayReader() {}

    public static Map<String, List<GameEvent>> read(String bootstrap, String topic, long sinceMillis, String runId) {
        String marker = RunId.gameMarker(runId);
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "2000");
        Map<String, List<GameEvent>> out = new LinkedHashMap<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            List<TopicPartition> parts = c.partitionsFor(topic, Duration.ofSeconds(30)).stream()
                    .map(i -> new TopicPartition(topic, i.partition())).toList();
            c.assign(parts);
            Map<TopicPartition, Long> end = c.endOffsets(parts, Duration.ofSeconds(30));
            Map<TopicPartition, Long> since = new HashMap<>();
            parts.forEach(tp -> since.put(tp, sinceMillis));
            Map<TopicPartition, OffsetAndTimestamp> start = c.offsetsForTimes(since, Duration.ofSeconds(30));
            List<TopicPartition> open = new ArrayList<>();
            for (TopicPartition tp : parts) {
                OffsetAndTimestamp o = start.get(tp);
                if (o == null || o.offset() >= end.get(tp)) {
                    c.seek(tp, end.get(tp));
                } else {
                    c.seek(tp, o.offset());
                    open.add(tp);
                }
            }
            while (!open.isEmpty()) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(500))) {
                    if (r.offset() >= end.get(new TopicPartition(r.topic(), r.partition()))) continue;
                    if (r.key() == null || !r.key().contains(marker)) continue;
                    GameEvent e;
                    try {
                        e = GameEventParser.parse(r.value());
                    } catch (RuntimeException ex) {
                        continue;
                    }
                    if (e == null || e.getGameId() == null || e.getVersion() <= 0) continue;
                    out.computeIfAbsent(e.getGameId(), k -> new ArrayList<>()).add(e);
                }
                open.removeIf(tp -> c.position(tp) >= end.get(tp));
            }
        }
        return out;
    }
}
