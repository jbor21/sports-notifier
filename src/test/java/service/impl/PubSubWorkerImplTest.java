package service.impl;

import model.GameEvent;
import model.Notification;
import model.UserPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PubSubWorkerImplTest {

    private JedisPool pool;
    private Jedis jedis;
    private Jedis blockingClient;
    private NotificationServiceImpl notificationService;
    private UserPreferenceServiceImpl userPreferenceService;
    private PubSubWorkerImpl worker;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        blockingClient = new Jedis("localhost", 6379);
        blockingClient.select(1);
        notificationService = new NotificationServiceImpl(pool);
        userPreferenceService = new UserPreferenceServiceImpl(pool);
        worker = new PubSubWorkerImpl(userPreferenceService, notificationService, pool, blockingClient, "test-worker");
    }

    @AfterEach
    void tearDown() {
        blockingClient.close();
        jedis.close();
        pool.close();
    }

    @Test
    void fanOutNotifications_sendsToTeamSubscriber() {
        jedis.sadd("sub:team:Lakers", "user1");
        jedis.sadd("sub:notif:SCORE_UPDATE", "user1");

        GameEvent event = new GameEvent("g1", "e1", "SCORE_UPDATE", "Lakers",
            1000L, "Score!", null, null, 3, 1, null, "NBA");

        worker.fanOutNotifications(event);

        List<Notification> history = notificationService.getNotificationHistory("user1", 10);
        assertEquals(1, history.size());
        assertEquals("g1", history.get(0).getGameId());
        assertEquals("Score!", history.get(0).getMessage());
    }

    @Test
    void fanOutNotifications_sendsToGameSubscriber() {
        jedis.sadd("sub:game:g1", "user2");
        jedis.sadd("sub:notif:SCORE_UPDATE", "user2");

        GameEvent event = new GameEvent("g1", "e1", "SCORE_UPDATE", "Lakers",
            1000L, "Score!", null, null, 3, 1, null, "NBA");

        worker.fanOutNotifications(event);

        assertEquals(1, notificationService.getNotificationHistory("user2", 10).size());
    }

    @Test
    void fanOutNotifications_sendsToLeagueSubscriber() {
        jedis.sadd("sub:league:NBA", "user3");
        jedis.sadd("sub:notif:SCORE_UPDATE", "user3");

        GameEvent event = new GameEvent("g1", "e1", "SCORE_UPDATE", "Lakers",
            1000L, "Score!", null, null, 3, 1, null, "NBA");

        worker.fanOutNotifications(event);

        assertEquals(1, notificationService.getNotificationHistory("user3", 10).size());
    }

    @Test
    void fanOutNotifications_skipsUserWithoutMatchingNotifType() {
        jedis.sadd("sub:team:Lakers", "user1");

        GameEvent event = new GameEvent("g1", "e1", "SCORE_UPDATE", "Lakers",
            1000L, "Score!", null, null, 3, 1, null, "NBA");

        worker.fanOutNotifications(event);

        assertEquals(0, notificationService.getNotificationHistory("user1", 10).size());
    }

    @Test
    void fanOutNotifications_deduplicatesSubscribersAcrossSets() {
        jedis.sadd("sub:team:Lakers", "user1");
        jedis.sadd("sub:game:g1", "user1");
        jedis.sadd("sub:notif:SCORE_UPDATE", "user1");

        GameEvent event = new GameEvent("g1", "e1", "SCORE_UPDATE", "Lakers",
            1000L, "Score!", null, null, 3, 1, null, "NBA");

        worker.fanOutNotifications(event);

        assertEquals(1, notificationService.getNotificationHistory("user1", 10).size());
    }

    @Test
    void fanOutNotifications_doesNothingWhenNoSubscribers() {
        GameEvent event = new GameEvent("g1", "e1", "SCORE_UPDATE", "Lakers",
            1000L, "Score!", null, null, 3, 1, null, "NBA");

        assertDoesNotThrow(() -> worker.fanOutNotifications(event));
    }
}
