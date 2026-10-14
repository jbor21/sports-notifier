package gateway;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import telemetry.Traces;
import testsupport.TelemetryTestSupport;
import model.GameEvent;
import model.NotificationMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.impl.Delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Each gateway hears only its own users' notifications (DESIGN.md decision 13). */
class GatewayChannelTest {

    private JedisPool pool;
    private Jedis jedis;
    private Delivery delivery;
    private final List<GatewayChannel> channels = new ArrayList<>();

    static final class FakeSocket implements ClientSocket {
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile Integer closedWith;
        @Override public boolean isOpen() { return closedWith == null; }
        @Override public void send(String text) { sent.add(text); }
        @Override public void close(int code, String reason) { closedWith = code; }
    }

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        delivery = new Delivery(pool);
    }

    @AfterEach
    void tearDown() {
        channels.forEach(GatewayChannel::stop);
        jedis.close();
        pool.close();
    }

    /** A started gateway channel with a unique id (pub/sub channels are shared across tests). */
    private GatewayChannel gateway() throws InterruptedException {
        GatewayChannel c = new GatewayChannel(() -> new Jedis("localhost", 6379), "gw-" + UUID.randomUUID());
        c.start();
        channels.add(c);
        Thread.sleep(200);   // let SUBSCRIBE land before anything is published
        return c;
    }

    private ConnectionRegistry registry(GatewayChannel c) {
        return new ConnectionRegistry(pool, c.gatewayId(), 90);
    }

    private static NotificationMessage msg(String userId, long version) {
        GameEvent play = new GameEvent("nba-1", "nba-1:" + version, "SCORE_UPDATE", "LAL", 1234L, "LAL scores!",
            "LAL", "BOS", 2, 0, "IN_PROGRESS", "NBA", 10).withVersion(version);
        return testsupport.TestMessages.of(play, userId);
    }

    private static void settle() throws InterruptedException {
        Thread.sleep(300);
    }

    @Test
    void userOnAGetsItAndBSeesNothing() throws Exception {
        GatewayChannel a = gateway(), b = gateway();
        FakeSocket onA = new FakeSocket(), onB = new FakeSocket();
        a.register("u1", onA);
        registry(a).add("u1");
        b.register("u1", onB);   // B holds a socket for u1 but is not in conn:u1, so it must hear nothing
        delivery.deliver(msg("u1", 1));
        settle();
        assertEquals(1, onA.sent.size());
        assertTrue(onA.sent.get(0).startsWith("{\"type\":\"notification\""), "the socket gets the API message itself: " + onA.sent.get(0));
        assertTrue(onA.sent.get(0).contains("\"text\":\"LAL scores!\""), onA.sent.get(0));
        assertEquals(0, onB.sent.size());
    }

    @Test
    void userOnBothGatewaysGetsItOnBoth() throws Exception {
        GatewayChannel a = gateway(), b = gateway();
        FakeSocket onA = new FakeSocket(), onB = new FakeSocket();
        a.register("u1", onA);
        registry(a).add("u1");
        b.register("u1", onB);
        registry(b).add("u1");
        delivery.deliver(msg("u1", 1));
        settle();
        assertEquals(1, onA.sent.size());
        assertEquals(1, onB.sent.size());
    }

    @Test
    void lastSocketCloseReportsLast() throws Exception {
        GatewayChannel a = gateway();
        FakeSocket tab1 = new FakeSocket(), tab2 = new FakeSocket();
        a.register("u1", tab1);
        a.register("u1", tab2);
        assertFalse(a.unregister("u1", tab1), "another tab is still open here");
        assertTrue(a.unregister("u1", tab2));
        assertTrue(a.users().isEmpty());
    }

    @Test
    void closeAllSendsCode4000() throws Exception {
        GatewayChannel a = gateway();
        FakeSocket s1 = new FakeSocket(), s2 = new FakeSocket();
        a.register("u1", s1);
        a.register("u2", s2);
        a.closeAll(4000, "server shutting down");
        assertEquals(4000, s1.closedWith);
        assertEquals(4000, s2.closedWith);
    }

    @Test
    void channelResubscribesAfterRedisDropsItsConnection() throws Exception {
        // Review finding M8: after a Redis restart the subscriber kept retrying on its dead
        // connection, so the gateway renewed its users' routing entries while hearing nothing.
        GatewayChannel a = gateway();
        FakeSocket onA = new FakeSocket();
        a.register("u1", onA);
        registry(a).add("u1");
        jedis.clientKill(new redis.clients.jedis.params.ClientKillParams().type(redis.clients.jedis.args.ClientType.PUBSUB));
        long deadline = System.currentTimeMillis() + 10_000;
        long v = 1;
        while (onA.sent.isEmpty() && System.currentTimeMillis() < deadline) {
            delivery.deliver(msg("u1", v++));
            Thread.sleep(500);
        }
        assertFalse(onA.sent.isEmpty(), "the gateway hears its channel again after Redis drops the connection");
    }

    @Test
    void messageForDisconnectedUserIsDropped() throws Exception {
        // Review Focus 5: the user's last tab closed after Stage 2 looked up conn:, before the message arrived.
        GatewayChannel a = gateway();
        FakeSocket u1 = new FakeSocket(), u2 = new FakeSocket();
        a.register("u1", u1);
        a.register("u2", u2);
        registry(a).add("u1");
        registry(a).add("u2");
        a.unregister("u1", u1);
        delivery.deliver(msg("u1", 1));
        delivery.deliver(msg("u2", 1));
        settle();
        assertEquals(0, u1.sent.size());
        assertEquals(1, u2.sent.size(), "one user's gone socket must not affect another's delivery");
    }

    /** A tab whose peer stopped reading: a blocking send never returns, an async one never completes. */
    static final class StalledSocket implements ClientSocket {
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        volatile Integer closedWith;
        @Override public boolean isOpen() { return closedWith == null; }
        @Override public void send(String text) {
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        @Override public void sendAsync(String text, Runnable done) { }
        @Override public void close(int code, String reason) { closedWith = code; }
    }

    /** Final review, Important 2: one tab that stopped reading must not freeze every other tab on the gateway. */
    @Test
    void stalledTabDoesNotBlockOtherUsers() throws Exception {
        GatewayChannel a = gateway();
        StalledSocket stalled = new StalledSocket();
        FakeSocket other = new FakeSocket();
        a.register("u1", new SocketSession("u1", stalled, 0L));
        a.register("u2", new SocketSession("u2", other, 0L));
        try {
            for (int i = 0; i < SocketSession.MAX_IN_FLIGHT + 10; i++) {
                jedis.publish("gw:" + a.gatewayId(), "{\"userId\":\"u1\",\"payload\":{\"type\":\"x\",\"n\":" + i + "}}");
            }
            jedis.publish("gw:" + a.gatewayId(), "{\"userId\":\"u2\",\"payload\":{\"type\":\"notification\"}}");
            long until = System.currentTimeMillis() + 3000;
            while (other.sent.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);
            assertEquals(1, other.sent.size(), "u2's push waited behind u1's stalled socket");
            assertEquals(1013, stalled.closedWith, "a tab too far behind is closed, so it reconnects and refetches");
        } finally {
            stalled.release.countDown();
        }
    }

    /** What Stage 2 publishes for one push: the API notification, detected 250 ms before now. */
    private static String pushJson(String userId, String traceparent) {
        com.google.gson.JsonObject n = new com.google.gson.JsonObject();
        n.addProperty("id", "nba-1:SCORE:1");
        n.addProperty("detectedAt", java.time.Instant.ofEpochMilli(System.currentTimeMillis() - 250).toString());
        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        payload.addProperty("type", "notification");
        payload.add("notification", n);
        com.google.gson.JsonObject m = new com.google.gson.JsonObject();
        m.addProperty("userId", userId);
        m.add("payload", payload);
        if (traceparent != null) m.addProperty("traceparent", traceparent);
        return m.toString();
    }

    @Test
    void sendSpanAndLatency() throws Exception {
        try (TelemetryTestSupport otel = TelemetryTestSupport.install()) {
            GatewayChannel a = gateway();
            FakeSocket socket = new FakeSocket();
            a.register("u1", socket);
            Span deliver = Traces.start("deliver", SpanKind.CONSUMER, Context.root());
            deliver.end();
            jedis.publish("gw:" + a.gatewayId(), pushJson("u1", Traces.traceparent(deliver)));
            settle();
            assertEquals(1, socket.sent.size());
            assertFalse(socket.sent.get(0).contains("traceparent"), "the tab gets the payload only");
            var send = otel.spans("send").get(0);
            assertEquals(deliver.getSpanContext().getSpanId(), send.getParentSpanId());
            assertEquals(1L, send.getAttributes().get(AttributeKey.longKey("send.sockets")));
            var point = otel.latency("gateway").orElseThrow();
            assertEquals(1, point.getCount());
            assertTrue(point.getSum() >= 0.25, "about 250 ms since detectedAt, got " + point.getSum());
            assertEquals(deliver.getSpanContext().getTraceId(), point.getExemplars().get(0).getSpanContext().getTraceId());
            assertEquals(1, otel.counter("socket.sends", null, null));
        }
    }

    @Test
    void garbageTraceparentStillSends() throws Exception {
        try (TelemetryTestSupport otel = TelemetryTestSupport.install()) {
            GatewayChannel a = gateway();
            FakeSocket socket = new FakeSocket();
            a.register("u1", socket);
            jedis.publish("gw:" + a.gatewayId(), pushJson("u1", "abc"));
            settle();
            assertEquals(1, socket.sent.size());
            assertFalse(otel.spans("send").get(0).getParentSpanContext().isValid());
            assertEquals(1, otel.latencyCount("gateway"));
        }
    }

    @Test
    void unreadCountHasNoSpan() throws Exception {
        try (TelemetryTestSupport otel = TelemetryTestSupport.install()) {
            GatewayChannel a = gateway();
            FakeSocket socket = new FakeSocket();
            a.register("u1", socket);
            jedis.publish("gw:" + a.gatewayId(), "{\"userId\":\"u1\",\"payload\":{\"type\":\"unread_count\",\"count\":3}}");
            settle();
            assertEquals(1, socket.sent.size());
            assertTrue(otel.spans("send").isEmpty());
            assertEquals(0, otel.latencyCount("gateway"));
            assertEquals(0, otel.counter("socket.sends", null, null));
        }
    }

    @Test
    void noOpenSocketNoLatency() throws Exception {
        try (TelemetryTestSupport otel = TelemetryTestSupport.install()) {
            GatewayChannel a = gateway();
            FakeSocket socket = new FakeSocket();
            a.register("u1", socket);
            socket.close(1000, "tab closed");
            jedis.publish("gw:" + a.gatewayId(), pushJson("u1", null));
            settle();
            assertTrue(socket.sent.isEmpty());
            assertEquals(0, otel.latencyCount("gateway"));
            assertEquals(0, otel.counter("socket.sends", null, null));
        }
    }

    @Test
    void holdsTracksSockets() throws Exception {
        GatewayChannel a = gateway();
        FakeSocket one = new FakeSocket(), two = new FakeSocket();
        assertFalse(a.holds("u1"));
        a.register("u1", one);
        a.register("u1", two);
        assertTrue(a.holds("u1"));
        a.unregister("u1", one);
        assertTrue(a.holds("u1"), "one tab still open");
        a.unregister("u1", two);
        assertFalse(a.holds("u1"));
    }
}
