package service.impl;

import com.google.gson.Gson;
import model.Notification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NotificationServiceImplTest {

    private JedisPool pool;
    private Jedis jedis;
    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        service = new NotificationServiceImpl(pool);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    /** History is written by Stage 2's deliver script; here it is seeded the same way, newest first. */
    private void deliver(String userId, String message, long ts) {
        jedis.lpush("notifications:" + userId, new Gson().toJson(new Notification(userId, "g1", message, ts)));
    }

    @Test
    void getNotificationHistory_returnsNewestFirst() {
        deliver("user1", "first", 1000L);
        deliver("user1", "second", 2000L);
        deliver("user1", "third", 3000L);

        List<Notification> history = service.getNotificationHistory("user1", 10);
        assertEquals("third", history.get(0).getMessage());
        assertEquals("second", history.get(1).getMessage());
        assertEquals("first", history.get(2).getMessage());
    }

    @Test
    void getNotificationHistory_respectsLimit() {
        for (int i = 0; i < 5; i++) {
            deliver("user1", "msg" + i, i);
        }

        List<Notification> history = service.getNotificationHistory("user1", 3);
        assertEquals(3, history.size());
    }

    @Test
    void getNotificationHistory_returnsEmptyListIfNone() {
        List<Notification> history = service.getNotificationHistory("nobody", 10);
        assertNotNull(history);
        assertTrue(history.isEmpty());
    }
}
