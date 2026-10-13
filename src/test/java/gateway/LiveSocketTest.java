package gateway;

import catalog.Teams;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.GameSummaries;
import io.javalin.Javalin;
import model.GameEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.impl.Delivery;
import testsupport.TestMessages;
import util.RedisKeys;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** /api/v1/ws as docs/api/websocket.md describes it (Phase 3.4 spec, section 8). */
class LiveSocketTest {

    private JedisPool pool;
    private Jedis jedis;
    private GatewayChannel channel;
    private LiveSocket live;
    private Javalin app;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<Client> clients = new ArrayList<>();
    private String sid;

    /** A browser tab: every text frame it got, and how it was closed. */
    static final class Client implements WebSocket.Listener {
        final List<String> texts = new CopyOnWriteArrayList<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder part = new StringBuilder();
        WebSocket ws;

        @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            part.append(data);
            if (last) { texts.add(part.toString()); part.setLength(0); }
            w.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            closed.complete(code);
            return null;
        }
        @Override public void onError(WebSocket w, Throwable error) { closed.completeExceptionally(error); }

        void send(String text) { ws.sendText(text, true).join(); }

        String awaitType(String type, int nth) throws InterruptedException {
            long until = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < until) {
                List<String> of = texts.stream().filter(t -> type.equals(typeOf(t))).toList();
                if (of.size() >= nth) return of.get(nth - 1);
                Thread.sleep(20);
            }
            throw new AssertionError("no " + type + " #" + nth + " in " + texts);
        }
    }

    static String typeOf(String text) {
        try {
            return JsonParser.parseString(text).getAsJsonObject().get("type").getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String gatewayId;

    @BeforeEach
    void setUp() throws Exception {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        gatewayId = "gw-" + UUID.randomUUID();
        channel = new GatewayChannel(() -> new Jedis("localhost", 6379), gatewayId);
        channel.start();
        Thread.sleep(200);
        GameSubscriptions games = new GameSubscriptions(pool, channel, new GameSummaries(Teams.load()), 2000);
        live = new LiveSocket(pool, channel, new ConnectionRegistry(pool, gatewayId, 90),
            new UnreadCount(pool, user -> 3), games, now::get);
        app = Javalin.create(LiveSocket::configure).start(0);
        live.register(app);
        sid = UUID.randomUUID().toString();
        jedis.set(RedisKeys.SESSION_PREFIX + sid, "u1");
    }

    @AfterEach
    void tearDown() {
        clients.forEach(c -> { if (c.ws != null) c.ws.abort(); });
        app.stop();
        channel.stop();
        jedis.close();
        pool.close();
    }

    private Client connect(String session) {
        Client c = new Client();
        WebSocket.Builder b = HttpClient.newHttpClient().newWebSocketBuilder();
        if (session != null) b.header("Cookie", "sid=" + session);
        c.ws = b.buildAsync(URI.create("ws://localhost:" + app.port() + "/api/v1/ws"), c).join();
        clients.add(c);
        return c;
    }

    /**
     * Issue #9: a tab reload closes the user's last socket here while the new one registers. If the new
     * socket's ZADD lands before the old one's ZREM, the open socket must still have its entry.
     */
    @Test
    void reRegisteredUserKeepsConnEntry() {
        new ConnectionRegistry(pool, gatewayId, 90).add("u7");
        channel.register("u7", new GatewayChannelTest.FakeSocket());   // the new tab, registered in between
        live.released("u7");
        assertNotNull(jedis.zscore("conn:u7", gatewayId), "the open socket keeps its routing entry");
    }

    @Test
    void releasedUserWithNoSocketLosesEntry() {
        new ConnectionRegistry(pool, gatewayId, 90).add("u7");
        live.released("u7");
        assertNull(jedis.zscore("conn:u7", gatewayId));
    }

    @Test
    void noSessionCloses4401() throws Exception {
        assertEquals(4401, connect(null).closed.get(5, TimeUnit.SECONDS));
    }

    @Test
    void helloHasUnreadCount() throws Exception {
        JsonObject hello = JsonParser.parseString(connect(sid).awaitType("hello", 1)).getAsJsonObject();
        assertEquals("u1", hello.get("userId").getAsString());
        assertEquals(3, hello.get("unreadCount").getAsInt());
        assertTrue(hello.get("serverTime").getAsString().endsWith("Z"));
    }

    @Test
    void pingPong() throws Exception {
        Client c = connect(sid);
        c.awaitType("hello", 1);
        c.send("{\"type\":\"ping\"}");
        c.awaitType("pong", 1);
    }

    @Test
    void unknownTypeAndBadJsonIgnored() throws Exception {
        Client c = connect(sid);
        c.awaitType("hello", 1);
        c.send("{\"type\":\"dance\"}");
        c.send("{not json");
        c.send("[1,2]");
        c.send("{\"type\":\"ping\"}");
        c.awaitType("pong", 1);
        assertFalse(c.closed.isDone());
    }

    @Test
    void parseGameIdsKeepsFirstHundredValid() {
        StringBuilder ids = new StringBuilder("[\"bad id\",\"nfl-\",\"xfl-1\"");
        for (int i = 0; i < 101; i++) ids.append(",\"nfl-").append(i).append('"');
        ids.append(']');
        List<String> kept = LiveSocket.parseGameIds(JsonParser.parseString(ids.toString()));
        assertEquals(100, kept.size());
        assertEquals("nfl-0", kept.get(0));
        assertEquals("nfl-99", kept.get(99));
        assertEquals(List.of(), LiveSocket.parseGameIds(JsonParser.parseString("\"nfl-1\"")));
        assertEquals(List.of("mlb-sim1760000000-2"), LiveSocket.parseGameIds(JsonParser.parseString("[\"mlb-sim1760000000-2\"]")));
    }

    @Test
    void idleClosesAfterSixtySeconds() throws Exception {
        Client c = connect(sid);
        c.awaitType("hello", 1);
        now.addAndGet(59_000);
        live.sweep();
        Thread.sleep(200);
        assertFalse(c.closed.isDone(), "59 s is not idle yet");
        now.addAndGet(1_000);
        live.sweep();
        assertEquals(1000, c.closed.get(5, TimeUnit.SECONDS));
    }

    @Test
    void anyMessageResetsIdle() throws Exception {
        Client c = connect(sid);
        c.awaitType("hello", 1);
        now.addAndGet(50_000);
        c.send("{\"type\":\"ping\"}");
        c.awaitType("pong", 1);
        now.addAndGet(50_000);
        live.sweep();
        Thread.sleep(200);
        assertFalse(c.closed.isDone());
    }

    @Test
    void notificationReachesSocketInApiShape() throws Exception {
        Client c = connect(sid);
        c.awaitType("hello", 1);   // the unread count is in Redis now (3)
        GameEvent play = new GameEvent("nba-1", "nba-1:1", "SCORE_UPDATE", "LAL", 1234L, "LAL scores!",
            "LAL", "BOS", 2, 0, "IN_PROGRESS", "NBA", 10).withVersion(1);
        new Delivery(pool).deliver(TestMessages.of(play, "u1"));
        JsonObject n = JsonParser.parseString(c.awaitType("notification", 1)).getAsJsonObject();
        assertEquals("nba-1:v1", n.getAsJsonObject("notification").get("id").getAsString());
        JsonObject count = JsonParser.parseString(c.awaitType("unread_count", 1)).getAsJsonObject();
        assertEquals(4, count.get("count").getAsInt());
        assertTrue(c.texts.indexOf(c.awaitType("notification", 1)) < c.texts.indexOf(c.awaitType("unread_count", 1)));
    }

    @Test
    void concurrentSendsDoNotThrow() throws Exception {
        Client c = connect(sid);
        c.awaitType("hello", 1);
        SocketSession s = live.sessions().iterator().next();
        Runnable burst = () -> { for (int i = 0; i < 200; i++) s.send("{\"type\":\"x\"}"); };
        Thread a = new Thread(burst), b = new Thread(burst);
        a.start(); b.start(); a.join(); b.join();
        c.awaitType("x", 400);
    }

    @Test
    void helloWhenCountUnavailableSaysZeroAndCachesNothing() throws Exception {
        app.stop();
        app = Javalin.create().start(0);
        new LiveSocket(pool, channel, new ConnectionRegistry(pool, "gw-x", 90),
            new UnreadCount(pool, user -> { throw new IllegalStateException("notification service down"); }),
            new GameSubscriptions(pool, channel, new GameSummaries(Teams.load()), 2000), now::get).register(app);
        JsonObject hello = JsonParser.parseString(connect(sid).awaitType("hello", 1)).getAsJsonObject();
        assertEquals(0, hello.get("unreadCount").getAsInt());
        assertFalse(jedis.exists("unread:u1"), "a guessed 0 must not stick for a day");
    }

    /** Jetty's own WebSocket idle timeout (30 s by default) must not beat the contract's 60 s. Real time, 35 s. */
    @Test
    void quietSocketOutlivesJettysDefaultTimeout() throws Exception {
        Client c = connect(sid);
        c.awaitType("hello", 1);
        Thread.sleep(35_000);
        assertFalse(c.closed.isDone(), "closed by Jetty with " + (c.closed.isDone() ? c.closed.getNow(-1) : 0));
    }
}
