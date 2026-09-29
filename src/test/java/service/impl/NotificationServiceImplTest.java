package service.impl;

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

    @Test
    void sendNotification_single_storesInHistory() {
        Notification notif = new Notification(null, "g1", "Score update!", 1000L);
        service.sendNotification("user1", notif);

        List<Notification> history = service.getNotificationHistory("user1", 10);
        assertEquals(1, history.size());
        assertEquals("g1", history.get(0).getGameId());
        assertEquals("Score update!", history.get(0).getMessage());
    }

    @Test
    void sendNotification_bulk_storesForAllUsers() {
        Notification notif = new Notification(null, "g1", "Game on!", 1000L);
        service.sendNotification(List.of("user1", "user2", "user3"), notif);

        assertEquals(1, service.getNotificationHistory("user1", 10).size());
        assertEquals(1, service.getNotificationHistory("user2", 10).size());
        assertEquals(1, service.getNotificationHistory("user3", 10).size());
    }

    @Test
    void getNotificationHistory_returnsNewestFirst() {
        service.sendNotification("user1", new Notification(null, "g1", "first", 1000L));
        service.sendNotification("user1", new Notification(null, "g1", "second", 2000L));
        service.sendNotification("user1", new Notification(null, "g1", "third", 3000L));

        List<Notification> history = service.getNotificationHistory("user1", 10);
        assertEquals("third", history.get(0).getMessage());
        assertEquals("second", history.get(1).getMessage());
        assertEquals("first", history.get(2).getMessage());
    }

    @Test
    void getNotificationHistory_respectsLimit() {
        for (int i = 0; i < 5; i++) {
            service.sendNotification("user1", new Notification(null, "g1", "msg" + i, i));
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
