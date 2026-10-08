package simulator;

import catalog.AlertSetting;
import catalog.FollowKind;
import com.google.gson.JsonParser;
import feed.EventSink;
import feed.Poller;
import feed.Seq;
import model.NotificationMessage;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import service.ProcessResult;
import service.impl.Delivery;
import service.impl.EventProcessingServiceImpl;
import service.impl.Follow;
import service.impl.FollowSets;
import service.impl.KafkaPlayPublisher;
import service.impl.Stage1Progress;
import service.impl.Stage1Worker;
import service.impl.Stage2Worker;
import testsupport.KafkaTestSupport;
import util.KafkaSettings;
import util.RedisKeys;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The simulator's games through the whole pipeline: the real poller over a {@link SimulatedScoreFeed},
 * Event Processing's accept script, game-events, Stage 1, notifications, Stage 2, and a gateway's
 * channel. A second run of the same matchup must reach the same follower again (spec 1.4).
 */
class SimulatorRunsEndToEndTest {

    private static final long STEP = 2000;
    private static final String USER = "hou-fan";

    private JedisPool pool;
    private Jedis jedis;
    private String gameEvents;
    private String notifications;
    private KafkaPlayPublisher publisher;
    private EventProcessingServiceImpl eps;
    private Stage1Worker stage1;
    private Stage2Worker stage2;

    /** What Stage 2 handed to delivery, every message, in order. */
    private final List<NotificationMessage> delivered = new CopyOnWriteArrayList<>();
    /** What the test gateway's channel heard: what the user's browser tab would have been sent. */
    private final List<String> pushes = new CopyOnWriteArrayList<>();
    private String gateway;
    private Jedis listener;

    @BeforeEach
    void setUp() throws Exception {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(32);
        pool = new JedisPool(cfg, "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();

        String id = UUID.randomUUID().toString();
        gameEvents = "game-events-" + id;
        notifications = "notifications-" + id;
        KafkaTestSupport.createTopic(gameEvents, 12);
        KafkaTestSupport.createTopic(notifications, 48);
        publisher = new KafkaPlayPublisher(
            new KafkaProducer<>(KafkaSettings.ingestProducer(KafkaTestSupport.bootstrap())), gameEvents);
        eps = new EventProcessingServiceImpl(pool, publisher, System::currentTimeMillis);

        // An Astros follower with the catalog's game-flow and scoring switches on, as user-pref saves it.
        Map<String, AlertSetting> alerts = new LinkedHashMap<>();
        for (String type : List.of("GAME_START", "RUN_SCORED", "FINAL")) alerts.put(type, new AlertSetting(true, Map.of()));
        new FollowSets(pool).writeState(new Follow("f1", USER, FollowKind.TEAM, "mlb-hou", "MLB", alerts, null, Instant.now()));
        jedis.set(RedisKeys.SUB_BUILT, "1");

        stage1 = stage1Worker();
        KafkaConsumer<String, String> s2 = new KafkaConsumer<>(
            KafkaSettings.consumer(KafkaTestSupport.bootstrap(), "s2-" + UUID.randomUUID()));
        s2.subscribe(List.of(notifications));
        stage2 = new Stage2Worker(s2, new Delivery(pool) {
            @Override public void deliverAll(List<NotificationMessage> batch) {
                super.deliverAll(batch);
                delivered.addAll(batch);
            }
        });

        gateway = "test-gw-" + UUID.randomUUID();
        listener = new Jedis("localhost", 6379);
        CountDownLatch subscribed = new CountDownLatch(1);
        JedisPubSub sub = new JedisPubSub() {
            @Override public void onMessage(String channel, String message) { pushes.add(message); }
            @Override public void onSubscribe(String channel, int count) { subscribed.countDown(); }
        };
        new Thread(() -> listener.subscribe(sub, RedisKeys.GATEWAY_CHANNEL_PREFIX + gateway)).start();
        assertTrue(subscribed.await(5, TimeUnit.SECONDS));
        long expires = Long.parseLong(jedis.time().get(0)) + 600;
        jedis.zadd(RedisKeys.CONN_PREFIX + USER, expires, gateway);
    }

    private Stage1Worker stage1Worker() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
            KafkaSettings.consumer(KafkaTestSupport.bootstrap(), "s1-" + UUID.randomUUID()));
        consumer.subscribe(List.of(gameEvents));
        KafkaProducer<String, String> producer = new KafkaProducer<>(KafkaSettings.fanoutProducer(KafkaTestSupport.bootstrap()));
        return new Stage1Worker(pool, new Stage1Progress(pool), consumer, producer, notifications, () -> {
            try (Jedis j = pool.getResource()) {
                return j.exists(RedisKeys.SUB_BUILT);
            }
        });
    }

    @AfterEach
    void tearDown() {
        listener.close();
        stage1.close();
        stage2.close();
        publisher.close();
        jedis.close();
        pool.close();
    }

    /** Event Processing's POST /events, called directly. */
    private final EventSink sink = event -> {
        ProcessResult r = eps.processEvent(event);
        return new EventSink.PostResult(r == ProcessResult.INVALID ? 400 : 200, r.name().toLowerCase(Locale.ROOT));
    };

    /**
     * Plays one Red Sox–Astros game through the real poller, one step at a time: the poller polls
     * until the script has accepted the step (a {@code pending} answer is re-sent on the next poll,
     * as in production), then the clock moves on.
     */
    private String runOneGame(long runStartSeconds) throws Exception {
        AtomicLong now = new AtomicLong(1_000_000L);
        SimulatedScoreFeed feed = new SimulatedScoreFeed(runStartSeconds, List.of(GameScript.mlb("BOS", "HOU")), now::get, STEP);
        Poller poller = new Poller(feed, pool, sink, now::get);
        String gameId = feed.gameIds().get(0);
        while (true) {
            String expected = String.valueOf(Seq.of(feed.fetch("MLB").get(0)));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!expected.equals(jedis.hget(RedisKeys.GAME_PREFIX + gameId, "lastSeq"))) {
                assertTrue(System.nanoTime() < deadline, gameId + " never accepted seq " + expected);
                poller.pollLeague("MLB");
                Thread.sleep(10);
            }
            if (feed.done()) break;
            now.addAndGet(STEP);
        }
        assertEquals("FINAL", jedis.hget(RedisKeys.GAME_PREFIX + gameId, "status"));
        return gameId;
    }

    private static String headline(NotificationMessage m) {
        return m.getNotification().getAsJsonObject("headline").get("type").getAsString();
    }

    /** Distinct messages delivered to the user for one game, in order. */
    private List<NotificationMessage> deliveredFor(String gameId) {
        Map<String, NotificationMessage> byId = new LinkedHashMap<>();
        for (NotificationMessage m : delivered) {
            if (USER.equals(m.getUserId()) && gameId.equals(m.getGameId())) byId.putIfAbsent(m.getMessageId(), m);
        }
        return new ArrayList<>(byId.values());
    }

    private long pushesFor(String gameId) {
        return pushes.stream()
            .map(raw -> JsonParser.parseString(raw).getAsJsonObject())
            .filter(m -> USER.equals(m.get("userId").getAsString()))
            .map(m -> m.getAsJsonObject("payload"))
            .filter(p -> gameId.equals(p.get("gameId").getAsString()))
            .count();
    }

    /** Everything the user should have heard about one game, and each delivered push reached the gateway once. */
    private boolean complete(String gameId) {
        List<NotificationMessage> msgs = deliveredFor(gameId);
        List<String> types = msgs.stream().map(SimulatorRunsEndToEndTest::headline).toList();
        return types.contains("GAME_START") && types.contains("RUN_SCORED") && types.contains("FINAL")
            && pushesFor(gameId) == msgs.size();
    }

    @Test
    void twoRunsReachTheSameUserTwice() throws Exception {
        String first = runOneGame(1_760_000_000L);
        String second = runOneGame(1_760_000_100L);
        assertNotEquals(first, second);

        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!(complete(first) && complete(second))) {
            assertTrue(System.nanoTime() < deadline, "first run: " + summary(first) + "; second run: " + summary(second));
            stage1.pollOnce(Duration.ofMillis(200));
            stage2.pollOnce(Duration.ofMillis(200));
        }

        for (String gameId : List.of(first, second)) {
            List<String> types = deliveredFor(gameId).stream().map(SimulatorRunsEndToEndTest::headline).toList();
            assertEquals("GAME_START", types.get(0), gameId + ": " + types);
            assertEquals("FINAL", types.get(types.size() - 1), gameId + ": " + types);
            assertEquals(1, Collections.frequency(types, "GAME_START"), gameId + ": " + types);
            assertEquals(1, Collections.frequency(types, "FINAL"), gameId + ": " + types);
        }
    }

    private String summary(String gameId) {
        return deliveredFor(gameId).stream().map(SimulatorRunsEndToEndTest::headline).toList()
            + ", pushes " + pushesFor(gameId);
    }
}
