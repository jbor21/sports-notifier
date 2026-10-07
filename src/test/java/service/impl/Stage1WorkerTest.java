package service.impl;

import catalog.FollowKind;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import model.GameEvent;
import model.NotificationMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import testsupport.KafkaTestSupport;
import util.GameEventParser;
import util.KafkaSettings;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Stage 1: one notifications message per user per play, deduped by version (Phase 3.1 spec, section 2.2). */
class Stage1WorkerTest {

    private static final Gson GSON = new Gson();

    private JedisPool pool;
    private Jedis jedis;
    private String gameEvents;
    private String notifications;
    private KafkaProducer<String, String> playProducer;
    private final List<Stage1Worker> workers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        String id = UUID.randomUUID().toString();
        gameEvents = "game-events-" + id;
        notifications = "notifications-" + id;
        KafkaTestSupport.createTopic(gameEvents, 12);
        KafkaTestSupport.createTopic(notifications, 48);
        playProducer = new KafkaProducer<>(KafkaSettings.ingestProducer(KafkaTestSupport.bootstrap()));
    }

    @AfterEach
    void tearDown() {
        workers.forEach(Stage1Worker::close);
        playProducer.close();
        jedis.close();
        pool.close();
    }

    private Stage1Worker worker(String group, Stage1Progress progress) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(KafkaSettings.consumer(KafkaTestSupport.bootstrap(), group));
        consumer.subscribe(List.of(gameEvents));
        KafkaProducer<String, String> producer = new KafkaProducer<>(KafkaSettings.fanoutProducer(KafkaTestSupport.bootstrap()));
        Stage1Worker w = new Stage1Worker(pool, progress, consumer, producer, notifications, () -> true);
        workers.add(w);
        return w;
    }

    private Stage1Worker worker(String group) {
        return worker(group, new Stage1Progress(pool));
    }

    /** Polls until the worker has handled {@code records} records (joining a group takes a moment). */
    private static void pollUntilHandled(Stage1Worker w, int records) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        int handled = 0;
        while (handled < records) {
            assertTrue(System.nanoTime() < deadline, "handled only " + handled + " of " + records);
            handled += w.pollOnce(Duration.ofMillis(200));
        }
    }

    private void send(GameEvent play) throws Exception {
        playProducer.send(new ProducerRecord<>(gameEvents, play.getGameId(), GameEventParser.toJson(play))).get();
    }

    /** A follow of an NHL team with one switch on, as user-pref's save path writes it. */
    private void follow(String userId, String team, String alertSwitch) {
        jedis.sadd(FollowSets.key(FollowKind.TEAM, "nhl-" + team.toLowerCase(), alertSwitch), userId);
    }

    private static GameEvent play(long version, String type, int home, int away) {
        return new GameEvent("nhl-1", "nhl-1:" + version, type, "TOR", 1234L, "TOR scores! TOR " + home + " - " + away + " BOS",
            "TOR", "BOS", home, away, "IN_PROGRESS", "NHL", 10 + version).withVersion(version);
    }

    private List<NotificationMessage> messages() {
        List<NotificationMessage> out = new ArrayList<>();
        for (ConsumerRecord<String, String> r : KafkaTestSupport.readAll(notifications, Duration.ofSeconds(2))) {
            NotificationMessage m = GSON.fromJson(r.value(), NotificationMessage.class);
            assertEquals(m.getUserId(), r.key(), "keyed by user, so a user's messages share a lane");
            out.add(m);
        }
        return out;
    }

    @Test
    void samePlayTwiceSendsOneMessagePerUser() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, "SCORE_UPDATE", 2, 0));
        send(play(1, "SCORE_UPDATE", 2, 0));     // the retry loop sent it twice
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 2);
        assertEquals(1, messages().size());
    }

    @Test
    void messageCarriesVersionedIdAndScores() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        send(play(3, "SCORE_UPDATE", 5, 2));
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 1);
        NotificationMessage m = messages().get(0);
        assertEquals("nhl-1:v3", m.getMessageId());
        assertEquals("bos-fan", m.getUserId());
        assertEquals(5, m.getHomeScore());
        assertEquals(2, m.getAwayScore());
        assertEquals(1234L, m.getDetectedAt());
        assertEquals("SCORE_UPDATE", m.getEventType());
        // The API's Notification, which history stores and the new UI reads (spec section 6).
        JsonObject n = m.getNotification();
        assertEquals("nhl-1:v3", n.get("id").getAsString());
        assertEquals("game_moment", n.get("kind").getAsString());
        assertEquals("NHL", n.get("leagueId").getAsString());
        assertEquals("GOAL", n.getAsJsonObject("headline").get("type").getAsString());
        assertEquals("TOR scores! TOR 5 - 2 BOS", n.getAsJsonObject("headline").get("text").getAsString());
        JsonObject away = n.getAsJsonObject("game").getAsJsonObject("away");
        assertEquals("nhl-bos", away.getAsJsonObject("team").get("id").getAsString());
        assertEquals("Boston Bruins", away.getAsJsonObject("team").get("displayName").getAsString());
        assertEquals(2, away.get("score").getAsInt());
        assertEquals("1970-01-01T00:00:01.234Z", n.get("detectedAt").getAsString());
    }

    @Test
    void gameSyncSendsNothingButIsRecorded() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, "GAME_SYNC", 0, 0));
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 1);
        assertTrue(messages().isEmpty());
        assertEquals(1L, new Stage1Progress(pool).last("nhl-1"));
    }

    @Test
    void crashBeforeRecordingResends() throws Exception {
        // First row of the crash table: messages sent, then the worker dies before recording the version.
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, "SCORE_UPDATE", 2, 0));
        String group = "s1-" + UUID.randomUUID();
        Stage1Progress dies = new Stage1Progress(pool) {
            @Override public boolean recordIfHigher(String gameId, long version) { throw new IllegalStateException("crash"); }
        };
        Stage1Worker a = worker(group, dies);
        assertThrows(IllegalStateException.class, () -> pollUntilHandled(a, 1));
        a.close();                               // gone without committing
        pollUntilHandled(worker(group), 1);      // the lane moves to B, which reads from the last commit
        List<NotificationMessage> msgs = messages();
        assertEquals(2, msgs.size(), "sent twice; Stage 2's dedup makes it one notification");
        assertEquals(msgs.get(0).getMessageId(), msgs.get(1).getMessageId());
    }

    @Test
    void crashAfterRecordingSkips() throws Exception {
        // Second row: the version was recorded but the offset was never committed. B re-reads and sends nothing.
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, "SCORE_UPDATE", 2, 0));
        new Stage1Progress(pool).recordIfHigher("nhl-1", 1);
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 1);
        assertTrue(messages().isEmpty());
    }

    @Test
    void recordIfHigherNeverLowers() {
        Stage1Progress progress = new Stage1Progress(pool);
        assertTrue(progress.recordIfHigher("nhl-1", 7));
        assertFalse(progress.recordIfHigher("nhl-1", 5), "a stalled worker finishing an older play must not lower it");
        assertEquals(7L, progress.last("nhl-1"));
        long ttl = jedis.ttl("s1:last:nhl-1");
        assertTrue(ttl > 691_000 && ttl <= 691_200, "outlives game-events' 7-day retention: " + ttl);
    }

    @Test
    void newGroupReadsEarlierPlays() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, "SCORE_UPDATE", 2, 0));     // sent before any Stage 1 worker exists
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 1);
        assertEquals(1, messages().size());
    }

    @Test
    void rereadFromStartSendsNothingNew() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        for (long v = 1; v <= 3; v++) send(play(v, "SCORE_UPDATE", (int) v, 0));
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 3);
        // A group with no bookmark starts from the beginning, like a lost or reset bookmark.
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 3);
        assertEquals(3, messages().size());
    }

    @Test
    void poisonRecordIsSkippedAndCommitted() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        playProducer.send(new ProducerRecord<>(gameEvents, "nhl-1", "{not json")).get();
        send(play(1, "SCORE_UPDATE", 2, 0));
        String group = "s1-" + UUID.randomUUID();
        pollUntilHandled(worker(group), 2);
        assertEquals(1, messages().size());
        try (KafkaConsumer<String, String> probe = new KafkaConsumer<>(KafkaSettings.consumer(KafkaTestSupport.bootstrap(), group))) {
            long committed = 0;
            for (int p = 0; p < 12; p++) {
                OffsetAndMetadata o = probe.committed(Set.of(new TopicPartition(gameEvents, p))).get(new TopicPartition(gameEvents, p));
                if (o != null) committed += o.offset();
            }
            assertEquals(2, committed, "committed past the bad record, so the lane is not stuck on it");
        }
    }

    @Test
    void twoThousandFollowersGetOneMessageEach() throws Exception {
        for (int i = 0; i < 2000; i++) follow("u" + i, "BOS", "GOAL");
        send(play(1, "SCORE_UPDATE", 2, 0));
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 1);
        List<NotificationMessage> msgs = messages();
        assertEquals(2000, msgs.size());
        Set<String> users = new HashSet<>();
        for (NotificationMessage m : msgs) users.add(m.getUserId());
        assertEquals(2000, users.size());
    }
}
