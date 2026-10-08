package gateway;

import catalog.Teams;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.GameSummaries;
import model.GameEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.args.ClientType;
import redis.clients.jedis.params.ClientKillParams;
import service.impl.EventProcessingServiceImpl;
import testsupport.TestPublisher;
import util.GameEventParser;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Per-game subscriptions, confirmed before current state is read (Phase 3.4 spec, 7.2 and 7.3). */
class GameSubscriptionsTest {

    private JedisPool pool;
    private Jedis jedis;
    private GatewayChannel channel;
    private GameSubscriptions games;

    static final class FakeSocket implements ClientSocket {
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean open = true;
        @Override public boolean isOpen() { return open; }
        @Override public void send(String text) { sent.add(text); }
        @Override public void close(int code, String reason) { open = false; }
    }

    @BeforeEach
    void setUp() throws Exception {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        channel = new GatewayChannel(() -> new Jedis("localhost", 6379), "gw-" + UUID.randomUUID());
        games = new GameSubscriptions(pool, channel, new GameSummaries(Teams.load()), 2000);
        channel.start();
        Thread.sleep(200);
    }

    @AfterEach
    void tearDown() {
        channel.stop();
        jedis.close();
        pool.close();
    }

    private static SocketSession tab(FakeSocket s) {
        return new SocketSession("u1", s, 0L);
    }

    private long subscribers(String gameId) {
        return jedis.pubsubNumSub("live:" + gameId).get("live:" + gameId);
    }

    private void record(String gameId, long version, int home) {
        jedis.hset("game:" + gameId, Map.of("league", "NFL", "homeTeam", "CHI", "awayTeam", "LV",
            "homeScore", String.valueOf(home), "awayScore", "0", "status", "IN_PROGRESS", "phase", "LIVE",
            "period", "2", "clockTenths", "3000", "version", String.valueOf(version)));
    }

    private static String live(String gameId, long version, int home) {
        GameEvent play = new GameEvent(gameId, gameId + ":1", "SCORE_UPDATE", "CHI", 1000L, "d",
            "CHI", "LV", home, 0, "IN_PROGRESS", "NFL", 1L).withPosition("LIVE", 2, 2900, "TOP", 0, "IN_PLAY", 2, "");
        return "{\"version\":" + version + ",\"play\":" + GameEventParser.toJson(play) + "}";
    }

    private static List<Long> versions(FakeSocket s) {
        return s.sent.stream().map(t -> JsonParser.parseString(t).getAsJsonObject())
            .filter(o -> "game_update".equals(o.get("type").getAsString()))
            .map(o -> o.getAsJsonObject("game").get("version").getAsLong()).toList();
    }

    private static void await(java.util.function.BooleanSupplier done, String what) throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(20);
        assertTrue(done.getAsBoolean(), what);
    }

    @Test
    void twoSocketsOneSubscribe() throws Exception {
        games.set(tab(new FakeSocket()), List.of("nfl-1"));
        games.set(tab(new FakeSocket()), List.of("nfl-1"));
        assertEquals(1, subscribers("nfl-1"), "one gateway connection, however many tabs");
    }

    @Test
    void lastLeaveUnsubscribes() throws Exception {
        SocketSession a = tab(new FakeSocket()), b = tab(new FakeSocket());
        games.set(a, List.of("nfl-1"));
        games.set(b, List.of("nfl-1"));
        games.set(a, List.of());
        Thread.sleep(200);
        assertEquals(1, subscribers("nfl-1"), "b still shows it");
        games.removeAll(b);
        await(() -> subscribers("nfl-1") == 0, "nobody shows it any more");
    }

    @Test
    void currentStateSentAfterConfirmedSubscribe() throws Exception {
        record("nfl-1", 7, 3);
        AtomicReference<Long> atRead = new AtomicReference<>();
        games.afterConfirmBeforeRead = () -> atRead.set(subscribers("nfl-1"));
        FakeSocket s = new FakeSocket();
        games.set(tab(s), List.of("nfl-1", "nfl-none"));
        assertEquals(1L, atRead.get(), "Redis confirmed the subscription before game:<id> was read");
        assertEquals(List.of(7L), versions(s), "current state for the game with a record, nothing for the other");
    }

    @Test
    void playBetweenSubscribeAndReadArrives() throws Exception {
        record("nfl-1", 7, 3);
        games.afterConfirmBeforeRead = () -> jedis.publish("live:nfl-1", live("nfl-1", 8, 10));
        FakeSocket s = new FakeSocket();
        games.set(tab(s), List.of("nfl-1"));
        await(() -> versions(s).contains(8L), "the play accepted in between still reached the tab: " + s.sent);
    }

    @Test
    void alreadyListeningReadsAtOnce() throws Exception {
        record("nfl-1", 7, 3);
        games.set(tab(new FakeSocket()), List.of("nfl-1"));
        FakeSocket second = new FakeSocket();
        games.set(tab(second), List.of("nfl-1"));
        assertEquals(List.of(7L), versions(second));
        assertEquals(1, subscribers("nfl-1"));
    }

    @Test
    void liveReachesOnlySubscribers() throws Exception {
        FakeSocket on = new FakeSocket(), off = new FakeSocket();
        games.set(tab(on), List.of("nfl-1"));
        games.set(tab(off), List.of("nfl-2"));
        jedis.publish("live:nfl-1", live("nfl-1", 9, 14));
        await(() -> versions(on).contains(9L), "subscriber got it");
        JsonObject game = JsonParser.parseString(on.sent.get(on.sent.size() - 1)).getAsJsonObject().getAsJsonObject("game");
        assertEquals("nfl-1", game.get("id").getAsString());
        assertEquals(14, game.getAsJsonObject("home").get("score").getAsInt());
        assertEquals("Q2", game.get("periodLabel").getAsString());
        Thread.sleep(200);
        assertTrue(off.sent.isEmpty(), "another game's tab hears nothing");
    }

    @Test
    void badLiveMessageIgnored() throws Exception {
        FakeSocket s = new FakeSocket();
        games.set(tab(s), List.of("nfl-1"));
        jedis.publish("live:nfl-1", "{not json");
        jedis.publish("live:nfl-1", "{\"version\":1}");
        jedis.publish("live:nfl-1", live("nfl-1", 10, 1));
        await(() -> versions(s).contains(10L), "the good one after the bad ones still arrives");
        assertEquals(List.of(10L), versions(s));
    }

    @Test
    void closeDuringSubscribeLeavesNoSubscription() throws Exception {
        SocketSession s = tab(new FakeSocket());
        games.afterConfirmBeforeRead = () -> games.removeAll(s);
        games.set(s, List.of("nfl-1"));
        await(() -> subscribers("nfl-1") == 0, "the tab left while subscribing: nothing stays subscribed");
    }

    @Test
    void resubscribeAfterReconnectSendsCurrentState() throws Exception {
        record("nfl-1", 7, 3);
        FakeSocket s = new FakeSocket();
        games.set(tab(s), List.of("nfl-1"));
        assertEquals(List.of(7L), versions(s));
        record("nfl-1", 12, 21);   // a play accepted while the gateway was cut off
        jedis.clientKill(new ClientKillParams().type(ClientType.PUBSUB));
        await(() -> versions(s).contains(12L), "after reconnecting, the tab gets the game's state again: " + s.sent);
        assertEquals(1, subscribers("nfl-1"));
    }

    @Test
    void endToEndAcceptedPlayReachesSocket() throws Exception {
        FakeSocket s = new FakeSocket();
        games.set(tab(s), List.of("nba-g1"));
        EventProcessingServiceImpl eps = new EventProcessingServiceImpl(pool, new TestPublisher(true), System::currentTimeMillis);
        eps.processEvent(new GameEvent("nba-g1", "e1", "SCORE_UPDATE", "LAL", 1000L, "d",
            "LAL", "BOS", 2, 0, "IN_PROGRESS", "NBA", 10));
        long version = Long.parseLong(jedis.hget("game:nba-g1", "version"));
        await(() -> versions(s).contains(version), "the accepted play's version reached the tab: " + s.sent);
    }
}
