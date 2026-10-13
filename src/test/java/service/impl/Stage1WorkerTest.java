package service.impl;

import alerts.Detector;
import alerts.Detectors;
import alerts.GameLabels;
import alerts.GameMemory;
import catalog.FollowKind;
import catalog.Teams;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 1 runs the detectors: one message per user per moment, deduped by version, with its memory in
 * det:<gameId> (Phase 3.3 spec, sections 2.3 and 6). The game is nhl-1, Maple Leafs (TOR) home vs
 * Bruins (BOS). The first play of a game is only the baseline, so every test starts with one.
 */
class Stage1WorkerTest {

    private static final Gson GSON = new Gson();
    private static final GameLabels LABELS = new GameLabels(Teams.load());

    private JedisPool pool;
    private Jedis jedis;
    private String gameEvents;
    private String notifications;
    private String deadLetters;
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
        deadLetters = "game-events-dlq-" + id;
        KafkaTestSupport.createTopic(deadLetters, 1);
        playProducer = new KafkaProducer<>(KafkaSettings.ingestProducer(KafkaTestSupport.bootstrap()));
    }

    @AfterEach
    void tearDown() {
        workers.forEach(Stage1Worker::close);
        playProducer.close();
        jedis.close();
        pool.close();
    }

    private Stage1Worker worker(String group) {
        return worker(group, new DetectorMemory(pool), () -> true);
    }

    private Stage1Worker worker(String group, DetectorMemory memory) {
        return worker(group, memory, () -> true);
    }

    private Stage1Worker worker(String group, DetectorMemory memory, BooleanSupplier setsReady) {
        return worker(group, memory, setsReady, Detectors.standard(LABELS));
    }

    private Stage1Worker worker(String group, DetectorMemory memory, BooleanSupplier setsReady, Detectors detectors) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(KafkaSettings.consumer(KafkaTestSupport.bootstrap(), group));
        consumer.subscribe(List.of(gameEvents));
        KafkaProducer<String, String> producer = new KafkaProducer<>(KafkaSettings.fanoutProducer(KafkaTestSupport.bootstrap()));
        Stage1Worker w = new Stage1Worker(memory, detectors,
            new MomentFanout(pool, new AlertAudience(), LABELS), consumer, producer, notifications, deadLetters, setsReady);
        workers.add(w);
        return w;
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

    /** Like {@link #pollUntilHandled}, but a failed batch is retried, as {@code run} does. */
    private static void pollThroughFailures(Stage1Worker w, int records) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        int handled = 0;
        while (handled < records) {
            assertTrue(System.nanoTime() < deadline, "handled only " + handled + " of " + records);
            try {
                handled += w.pollOnce(Duration.ofMillis(200));
            } catch (RuntimeException e) {
                // rewound; the next poll reads the batch again
            }
        }
    }

    /** Every play into lane 0, so two games share one lane. */
    private void sendToLaneZero(GameEvent play) throws Exception {
        playProducer.send(new ProducerRecord<>(gameEvents, 0, play.getGameId(), GameEventParser.toJson(play))).get();
    }

    private void send(GameEvent play) throws Exception {
        playProducer.send(new ProducerRecord<>(gameEvents, play.getGameId(), GameEventParser.toJson(play))).get();
    }

    /** A follow of a team with these switches on, as user-pref's save path writes it. */
    private void follow(String userId, String team, String... switches) {
        String teamId = "nhl-" + team.toLowerCase();
        jedis.sadd(FollowSets.key(FollowKind.TEAM, teamId, "ANY"), userId);
        for (String s : switches) jedis.sadd(FollowSets.key(FollowKind.TEAM, teamId, s), userId);
    }

    /** nhl-1 in the 2nd period; the clock runs down a little with every version. */
    private static GameEvent play(long version, int home, int away) {
        return play("nhl-1", version, home, away);
    }

    private static GameEvent play(String gameId, long version, int home, int away) {
        return new GameEvent(gameId, gameId + ":" + version, "GAME_SYNC", "TOR", 1234L, "",
            "TOR", "BOS", home, away, "IN_PROGRESS", "NHL", 10 + version)
            .withPosition("LIVE", 2, 12000 - (int) version * 100, "TOP", 0, "IN_PLAY", 2, null)
            .withVersion(version);
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

    /** Final review (3.2), Important 1: the marker check is the first Redis call after a poll. */
    @Test
    void redisFailingAtTheMarkerCheckLosesNothing() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, 0, 0));
        send(play(2, 0, 1));
        AtomicInteger checks = new AtomicInteger();
        Stage1Worker w = worker("s1-" + UUID.randomUUID(), new DetectorMemory(pool), () -> {
            if (checks.getAndIncrement() == 0) throw new redis.clients.jedis.exceptions.JedisConnectionException("Redis is down (test)");
            return true;
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        boolean failed = false;
        while (!failed) {
            assertTrue(System.nanoTime() < deadline, "the marker check never ran");
            try {
                w.pollOnce(Duration.ofMillis(200));
            } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
                failed = true;   // what run() does next: log, wait, poll again
            }
        }
        pollUntilHandled(w, 2);   // the batch was rewound, so the plays come back
        assertEquals(1, messages().size(), "the goal is delivered once, not skipped");
    }

    @Test
    void firstPlayIsOnlyTheBaseline() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, 0, 3));
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 1);
        assertTrue(messages().isEmpty(), "a game first seen at 0-3 sends no goals");
        assertEquals("1", jedis.hget("det:nhl-1", "version"));
    }

    @Test
    void duplicateVersionSendsNothing() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, 0, 0));
        send(play(2, 0, 1));
        send(play(2, 0, 1));     // the retry loop sent it twice
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 3);
        assertEquals(1, messages().size());
    }

    @Test
    void messageCarriesAlertIdAndBody() throws Exception {
        follow("tor-fan", "TOR", "GOAL");
        send(play(1, 4, 2));
        send(play(2, 5, 2));
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 2);
        NotificationMessage m = messages().get(0);
        assertEquals("nhl-1:SCORE:home:1", m.getMessageId());
        assertEquals("tor-fan", m.getUserId());
        assertEquals("GOAL", m.getEventType());
        assertEquals(5, m.getHomeScore());
        assertEquals(2, m.getAwayScore());
        assertEquals(1234L, m.getDetectedAt());
        JsonObject n = m.getNotification();
        assertEquals("nhl-1:SCORE:home:1", n.get("id").getAsString());
        assertEquals("game_moment", n.get("kind").getAsString());
        assertEquals("NHL", n.get("leagueId").getAsString());
        assertEquals("GOAL", n.getAsJsonObject("headline").get("type").getAsString());
        assertEquals("Goal Maple Leafs! Maple Leafs 5, Bruins 2 · 2nd 19:40", n.getAsJsonObject("headline").get("text").getAsString());
        assertEquals("2nd", n.getAsJsonObject("game").get("periodLabel").getAsString());
        assertEquals("19:40", n.getAsJsonObject("game").get("clock").getAsString());
        JsonObject away = n.getAsJsonObject("game").getAsJsonObject("away");
        assertEquals("nhl-bos", away.getAsJsonObject("team").get("id").getAsString());
        assertEquals("Boston Bruins", away.getAsJsonObject("team").get("displayName").getAsString());
        assertEquals(2, away.get("score").getAsInt());
        assertEquals("1970-01-01T00:00:01.234Z", n.get("detectedAt").getAsString());
    }

    @Test
    void goalAndLeadChangeOneMessage() throws Exception {
        follow("tor-fan", "TOR", "GOAL", "LEAD_CHANGE");
        send(play(1, 1, 2));
        send(play(2, 2, 2));
        send(play(3, 3, 2));
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 3);
        List<NotificationMessage> msgs = messages();
        assertEquals(List.of("nhl-1:SCORE:home:1", "nhl-1:SCORE:home:2"), msgs.stream().map(NotificationMessage::getMessageId).toList());
        JsonObject second = msgs.get(1).getNotification();
        assertEquals("GOAL", second.getAsJsonObject("headline").get("type").getAsString());
        assertEquals("LEAD_CHANGE", second.getAsJsonArray("extras").get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void clockOnlyPlaySendsNothingButIsRecorded() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, 0, 0));
        send(play(2, 0, 0));
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 2);
        assertTrue(messages().isEmpty());
        assertEquals("2", jedis.hget("det:nhl-1", "version"));
        assertTrue(jedis.hget("det:nhl-1", "memory").contains("\"clockTenths\":11800"), "the memory moved with the clock");
    }

    @Test
    void crashBeforeMemoryWriteResendsSameIds() throws Exception {
        // Messages sent, then the worker dies before writing det:. B re-reads against the same memory.
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, 0, 0));
        send(play(2, 0, 1));
        String group = "s1-" + UUID.randomUUID();
        DetectorMemory dies = new DetectorMemory(pool) {
            @Override public boolean writeIfHigher(String gameId, long version, GameMemory m) {
                if (version == 2) throw new IllegalStateException("crash");
                return super.writeIfHigher(gameId, version, m);
            }
        };
        Stage1Worker a = worker(group, dies);
        assertThrows(IllegalStateException.class, () -> pollUntilHandled(a, 2));
        a.close();                               // gone without committing
        pollUntilHandled(worker(group), 2);      // the lane moves to B, which reads from the last commit
        List<NotificationMessage> msgs = messages();
        assertEquals(2, msgs.size(), "sent twice; Stage 2's dedup on the alert id makes it one push");
        assertEquals("nhl-1:SCORE:away:1", msgs.get(0).getMessageId());
        assertEquals(msgs.get(0).getMessageId(), msgs.get(1).getMessageId());
    }

    @Test
    void crashAfterMemoryWriteSkips() throws Exception {
        // det: was written but the offset was never committed: B re-reads and sends nothing.
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, 0, 0));
        send(play(2, 0, 1));
        GameMemory m = new GameMemory();
        m.takePosition(alerts.Play.of(play(2, 0, 1)));
        new DetectorMemory(pool).writeIfHigher("nhl-1", 2, m);
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 2);
        assertTrue(messages().isEmpty());
    }

    @Test
    void redisRollbackRedetectsSameIds() throws Exception {
        // Redis lost its last second (AOF): det: is one play behind. The next play compares with the
        // older memory and finds the goal again; the counters rolled back too, so the id is the same.
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, 0, 0));
        Stage1Worker w = worker("s1-" + UUID.randomUUID());
        pollUntilHandled(w, 1);
        Map<String, String> afterBaseline = jedis.hgetAll("det:nhl-1");
        send(play(2, 0, 1));
        pollUntilHandled(w, 1);
        jedis.del("det:nhl-1");
        jedis.hset("det:nhl-1", afterBaseline);
        send(play(3, 0, 1));
        pollUntilHandled(w, 1);
        List<NotificationMessage> msgs = messages();
        assertEquals(2, msgs.size());
        assertEquals("nhl-1:SCORE:away:1", msgs.get(0).getMessageId());
        assertEquals(msgs.get(0).getMessageId(), msgs.get(1).getMessageId(), "Stage 2 drops the second");
    }

    @Test
    void newGroupReadsEarlierPlays() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, 0, 0));     // sent before any Stage 1 worker exists
        send(play(2, 0, 1));
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 2);
        assertEquals(1, messages().size());
    }

    @Test
    void rereadFromStartSendsNoSecondPush() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, 0, 0));
        for (long v = 2; v <= 4; v++) send(play(v, 0, (int) v - 1));
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 4);
        // A group with no bookmark starts from the beginning, like a lost or reset bookmark.
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 4);
        assertEquals(3, messages().size());
    }

    /** Issue #44: a lane's poison count goes with the lane; another owner starts its own count. */
    @Test
    void forgetDropsAttemptsOfRevokedLanes() throws Exception {
        Detector bug = new Detector() {
            @Override public void detect(GameMemory before, alerts.Play now, GameMemory after, List<alerts.Alert> out) {
                if (now.version() == 2) throw new IllegalArgumentException("detector bug");
            }
        };
        List<Detector> chain = new ArrayList<>(List.of(bug));
        chain.addAll(Detectors.standardChain(LABELS));
        sendToLaneZero(play("nhl-1", 1, 0, 0));
        sendToLaneZero(play("nhl-1", 2, 0, 1));
        Stage1Worker w = worker("s1-" + UUID.randomUUID(), new DetectorMemory(pool), () -> true, new Detectors(chain));
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (w.attemptsTracked() == 0) {
            assertTrue(System.nanoTime() < deadline, "the poison play never failed");
            try {
                w.pollOnce(Duration.ofMillis(200));
            } catch (RuntimeException e) {
                // the first failed try; counted
            }
        }
        assertEquals(1, w.attemptsTracked());
        w.forget(List.of(new TopicPartition(gameEvents, 5)));
        assertEquals(1, w.attemptsTracked(), "another lane's loss leaves this count");
        w.forget(List.of(new TopicPartition(gameEvents, 0)));
        assertEquals(0, w.attemptsTracked());
    }

    @Test
    void attemptKeyNamesTheTopic() {
        assertEquals("game-events-0@7", Stage1Worker.attemptKey(new ConsumerRecord<>("game-events", 0, 7L, "k", "v")));
    }

    /** Issue #15: a readable play that always throws is dead-lettered after 3 tries, not retried forever. */
    @Test
    void poisonPlayIsDeadLetteredAndTheLaneMovesOn() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        Detector bug = new Detector() {
            @Override public void detect(GameMemory before, alerts.Play now, GameMemory after, List<alerts.Alert> out) {
                if (now.gameId().equals("nhl-1") && now.version() == 2) throw new IllegalArgumentException("detector bug");
            }
        };
        List<Detector> chain = new ArrayList<>(List.of(bug));
        chain.addAll(Detectors.standardChain(LABELS));
        sendToLaneZero(play("nhl-1", 1, 0, 0));
        sendToLaneZero(play("nhl-1", 2, 0, 1));   // the poison play
        sendToLaneZero(play("nhl-2", 1, 0, 0));
        sendToLaneZero(play("nhl-2", 2, 0, 1));   // another game in the same lane: its goal must arrive
        Stage1Worker w = worker("s1-" + UUID.randomUUID(), new DetectorMemory(pool), () -> true, new Detectors(chain));
        pollThroughFailures(w, 4);
        // det: moved past the poison play, so a re-read after a crash skips it instead of dead-lettering it again.
        assertEquals("2", jedis.hget("det:nhl-1", "version"));
        sendToLaneZero(play("nhl-1", 3, 0, 2));   // the poisoned game goes on too
        pollThroughFailures(w, 1);

        List<String> ids = messages().stream().map(NotificationMessage::getMessageId).toList();
        assertTrue(ids.stream().anyMatch(i -> i.startsWith("nhl-2:SCORE")), "nhl-2's goal arrived: " + ids);
        assertTrue(ids.stream().anyMatch(i -> i.startsWith("nhl-1:SCORE")), "nhl-1's next goal arrived: " + ids);
        List<ConsumerRecord<String, String>> dead = KafkaTestSupport.readAll(deadLetters, Duration.ofSeconds(2));
        assertEquals(1, dead.size());
        assertEquals("nhl-1", dead.get(0).key());
        assertEquals(2, GameEventParser.parse(dead.get(0).value()).getVersion());
        String error = new String(dead.get(0).headers().lastHeader("error").value(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(error.contains("detector bug"), error);
        String source = new String(dead.get(0).headers().lastHeader("source").value(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(gameEvents + "-0@1", source);
        assertEquals(1, w.deadLettered());
    }

    /** Review of #34: a game whose stored memory can't be read is not dead-lettered over and over. */
    @Test
    void unreadableMemoryIsForgottenAndTheLaneMovesOn() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        jedis.hset("det:nhl-1", Map.of("version", "1", "memory", "{not json"));
        sendToLaneZero(play("nhl-1", 2, 0, 1));
        sendToLaneZero(play("nhl-2", 1, 0, 0));
        sendToLaneZero(play("nhl-2", 2, 0, 1));
        Stage1Worker w = worker("s1-" + UUID.randomUUID());
        pollThroughFailures(w, 3);
        assertEquals(1, KafkaTestSupport.readAll(deadLetters, Duration.ofSeconds(2)).size(), "dead-lettered once");
        assertEquals(1, w.deadLettered());
        assertFalse(jedis.exists("det:nhl-1"), "the broken memory is gone; the game's next play starts from a baseline");
        assertTrue(messages().stream().anyMatch(m -> m.getMessageId().startsWith("nhl-2:SCORE")));
    }

    /** Review of #34: a Redis error reply that will never change is a bad play, not an outage. */
    @Test
    void aRedisCommandErrorIsPoisonButLoadingIsAnOutage() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, 0, 0));
        DetectorMemory wrongArgs = new DetectorMemory(pool) {
            @Override public Stored read(String gameId) {
                throw new redis.clients.jedis.exceptions.JedisDataException("ERR wrong number of arguments for 'sunionstore' command");
            }
        };
        pollThroughFailures(worker("s1-" + UUID.randomUUID(), wrongArgs), 1);
        assertEquals(1, KafkaTestSupport.readAll(deadLetters, Duration.ofSeconds(2)).size());

        java.util.concurrent.atomic.AtomicInteger loading = new java.util.concurrent.atomic.AtomicInteger(10);
        DetectorMemory restarting = new DetectorMemory(pool) {
            @Override public Stored read(String gameId) {
                if (loading.getAndDecrement() > 0)
                    throw new redis.clients.jedis.exceptions.JedisDataException("LOADING Redis is loading the dataset in memory");
                return super.read(gameId);
            }
        };
        pollThroughFailures(worker("s1-" + UUID.randomUUID(), restarting), 1);
        assertEquals(1, KafkaTestSupport.readAll(deadLetters, Duration.ofSeconds(2)).size(), "LOADING never dead-letters");
    }

    /** Review of #34: outages don't use up a play's tries; only its own failures do. */
    @Test
    void outagesDoNotUseUpThePlaysTries() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, 0, 0));
        send(play(2, 0, 1));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        DetectorMemory mixed = new DetectorMemory(pool) {
            @Override public Stored read(String gameId) {
                int n = calls.incrementAndGet();
                if (n <= 4) throw new redis.clients.jedis.exceptions.JedisConnectionException("Redis is down");
                if (n == 5) throw new IllegalStateException("one odd failure");
                return super.read(gameId);
            }
        };
        Stage1Worker w = worker("s1-" + UUID.randomUUID(), mixed);
        pollThroughFailures(w, 2);
        assertEquals(0, w.deadLettered());
        assertEquals(1, messages().size());
    }

    /** Review of #34: a detector that recurses forever is a bad play, not a reason to crash every lane. */
    @Test
    void stackOverflowIsPoison() throws Exception {
        Detector recurses = new Detector() {
            @Override public void detect(GameMemory before, alerts.Play now, GameMemory after, List<alerts.Alert> out) {
                if (now.version() == 2) throw new StackOverflowError();
            }
        };
        send(play(1, 0, 0));
        send(play(2, 0, 1));
        Stage1Worker w = worker("s1-" + UUID.randomUUID(), new DetectorMemory(pool), () -> true, new Detectors(List.of(recurses)));
        pollThroughFailures(w, 2);
        assertEquals(1, w.deadLettered());
    }

    /** Issue #15: an outage is not poison. Redis failing over and over never dead-letters a play. */
    @Test
    void redisFailuresAreRetriedNeverDeadLettered() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        send(play(1, 0, 0));
        send(play(2, 0, 1));
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(10);
        DetectorMemory flaky = new DetectorMemory(pool) {
            @Override public Stored read(String gameId) {
                if (failures.getAndDecrement() > 0) throw new redis.clients.jedis.exceptions.JedisConnectionException("Redis is down");
                return super.read(gameId);
            }
        };
        Stage1Worker w = worker("s1-" + UUID.randomUUID(), flaky);
        pollThroughFailures(w, 2);
        assertEquals(1, messages().size(), "the goal arrived once Redis was back");
        assertTrue(KafkaTestSupport.readAll(deadLetters, Duration.ofSeconds(1)).isEmpty());
        assertEquals(0, w.deadLettered());
    }

    @Test
    void poisonRecordIsSkippedAndCommitted() throws Exception {
        follow("bos-fan", "BOS", "GOAL");
        playProducer.send(new ProducerRecord<>(gameEvents, "nhl-1", "{not json")).get();
        send(play(1, 0, 0));
        send(play(2, 0, 1));
        String group = "s1-" + UUID.randomUUID();
        pollUntilHandled(worker(group), 3);
        assertEquals(1, messages().size());
        try (KafkaConsumer<String, String> probe = new KafkaConsumer<>(KafkaSettings.consumer(KafkaTestSupport.bootstrap(), group))) {
            long committed = 0;
            for (int p = 0; p < 12; p++) {
                OffsetAndMetadata o = probe.committed(Set.of(new TopicPartition(gameEvents, p))).get(new TopicPartition(gameEvents, p));
                if (o != null) committed += o.offset();
            }
            assertEquals(3, committed, "committed past the bad record, so the lane is not stuck on it");
        }
    }

    @Test
    void twoThousandFollowersGetOneMessageEach() throws Exception {
        for (int i = 0; i < 2000; i++) follow("u" + i, "BOS", "GOAL");
        send(play(1, 0, 0));
        send(play(2, 0, 1));
        pollUntilHandled(worker("s1-" + UUID.randomUUID()), 2);
        List<NotificationMessage> msgs = messages();
        assertEquals(2000, msgs.size());
        Set<String> users = new HashSet<>();
        for (NotificationMessage m : msgs) users.add(m.getUserId());
        assertEquals(2000, users.size());
    }
}
