package gateway;

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
        Jedis blocking = new Jedis("localhost", 6379);
        GatewayChannel c = new GatewayChannel(blocking, "gw-" + UUID.randomUUID());
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
        return NotificationMessage.of(play, userId);
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
        assertTrue(onA.sent.get(0).contains("\"message\":\"LAL scores!\""), "the socket gets the notification itself");
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
}
