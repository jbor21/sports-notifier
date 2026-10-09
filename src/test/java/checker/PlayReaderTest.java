package checker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import model.GameEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import testsupport.KafkaTestSupport;
import util.GameEventParser;
import util.KafkaSettings;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Reading one run's accepted plays back from game-events (Phase 4.1 spec, 6.1). */
class PlayReaderTest {

    private static String play(String gameId, long version) {
        JsonObject o = new JsonObject();
        o.addProperty("eventId", gameId + ":v" + version);
        o.addProperty("gameId", gameId);
        o.addProperty("league", "NFL");
        o.addProperty("eventType", "SCORE_UPDATE");
        o.addProperty("homeTeam", "CHI");
        o.addProperty("awayTeam", "LV");
        o.addProperty("version", version);
        o.addProperty("homeScore", (int) version);
        return o.toString();
    }

    private static String topic() {
        String t = "game-events-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(t, 3);
        return t;
    }

    private static void send(String topic, String gameId, long version, Long timestamp) throws Exception {
        try (KafkaProducer<String, String> p = new KafkaProducer<>(KafkaSettings.ingestProducer(KafkaTestSupport.bootstrap()))) {
            p.send(new ProducerRecord<>(topic, null, timestamp, gameId, play(gameId, version))).get();
        }
    }

    @Test
    void readsOnlyThisRunsGames() throws Exception {
        String t = topic();
        long since = System.currentTimeMillis() - 1000;
        send(t, "nfl-rpaaa111-1", 1, null);
        send(t, "nfl-rpbbb222-1", 1, null);
        send(t, "nfl-401872971", 1, null);
        Map<String, List<GameEvent>> got = PlayReader.read(KafkaTestSupport.bootstrap(), t, since, "aaa111");
        assertEquals(List.of("nfl-rpaaa111-1"), List.copyOf(got.keySet()));
    }

    @Test
    void startsAtSinceMillis() throws Exception {
        String t = topic();
        long now = System.currentTimeMillis();
        send(t, "nfl-rpaaa111-1", 1, now - 3_600_000);     // an earlier run's record, an hour old
        send(t, "nfl-rpaaa111-1", 2, now);
        Map<String, List<GameEvent>> got = PlayReader.read(KafkaTestSupport.bootstrap(), t, now - 60_000, "aaa111");
        assertEquals(List.of(2L), got.get("nfl-rpaaa111-1").stream().map(GameEvent::getVersion).toList());
    }

    @Test
    void keepsPartitionOrder() throws Exception {
        String t = topic();
        long since = System.currentTimeMillis() - 1000;
        try (KafkaProducer<String, String> p = new KafkaProducer<>(KafkaSettings.ingestProducer(KafkaTestSupport.bootstrap()))) {
            for (long v = 1; v <= 20; v++) p.send(new ProducerRecord<>(t, "nfl-rpaaa111-9", play("nfl-rpaaa111-9", v)));
            p.flush();
        }
        List<GameEvent> got = PlayReader.read(KafkaTestSupport.bootstrap(), t, since, "aaa111").get("nfl-rpaaa111-9");
        assertEquals(java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList(), got.stream().map(GameEvent::getVersion).toList());
    }

    @Test
    void unreadableRecordSkipped() throws Exception {
        String t = topic();
        long since = System.currentTimeMillis() - 1000;
        try (KafkaProducer<String, String> p = new KafkaProducer<>(KafkaSettings.ingestProducer(KafkaTestSupport.bootstrap()))) {
            p.send(new ProducerRecord<>(t, "nfl-rpaaa111-1", "{not json")).get();
        }
        send(t, "nfl-rpaaa111-1", 1, null);
        assertEquals(1, PlayReader.read(KafkaTestSupport.bootstrap(), t, since, "aaa111").get("nfl-rpaaa111-1").size());
    }
}
