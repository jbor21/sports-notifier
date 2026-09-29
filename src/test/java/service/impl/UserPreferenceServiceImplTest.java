package service.impl;

import model.UserPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserPreferenceServiceImplTest {

    private JedisPool pool;
    private Jedis jedis;
    private UserPreferenceServiceImpl service;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        service = new UserPreferenceServiceImpl(pool);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    @Test
    void saveAndGetPreferences_roundTrip() {
        UserPreferences prefs = new UserPreferences(
            "user1",
            List.of("Lakers", "Celtics"),
            List.of("game1"),
            List.of("NBA"),
            List.of("SCORE_UPDATE")
        );

        service.savePreferences("user1", prefs);
        UserPreferences retrieved = service.getPreferences("user1");

        assertNotNull(retrieved);
        assertEquals("user1", retrieved.getUserId());
        assertTrue(retrieved.getFollowedTeams().contains("Lakers"));
        assertTrue(retrieved.getFollowedTeams().contains("Celtics"));
        assertTrue(retrieved.getFollowedGames().contains("game1"));
    }

    @Test
    void savePreferences_populatesSubscriptionCache() {
        UserPreferences prefs = new UserPreferences(
            "user1",
            List.of("Lakers"),
            List.of("game1"),
            List.of("NBA"),
            List.of("SCORE_UPDATE")
        );

        service.savePreferences("user1", prefs);

        assertTrue(jedis.sismember("sub:team:Lakers", "user1"));
        assertTrue(jedis.sismember("sub:game:game1", "user1"));
        assertTrue(jedis.sismember("sub:league:NBA", "user1"));
        assertTrue(jedis.sismember("sub:notif:SCORE_UPDATE", "user1"));
    }

    @Test
    void savePreferences_removesOldCacheEntriesOnUpdate() {
        UserPreferences prefs1 = new UserPreferences(
            "user1", List.of("Lakers"), List.of(), List.of(), List.of("SCORE_UPDATE")
        );
        service.savePreferences("user1", prefs1);
        assertTrue(jedis.sismember("sub:team:Lakers", "user1"));

        UserPreferences prefs2 = new UserPreferences(
            "user1", List.of("Celtics"), List.of(), List.of(), List.of("SCORE_UPDATE")
        );
        service.savePreferences("user1", prefs2);

        assertFalse(jedis.sismember("sub:team:Lakers", "user1"));
        assertTrue(jedis.sismember("sub:team:Celtics", "user1"));
    }

    @Test
    void getPreferences_returnsNullIfNotFound() {
        assertNull(service.getPreferences("nonexistent"));
    }

    @Test
    void updateSubscriptionCache_addsToCache() {
        UserPreferences prefs = new UserPreferences(
            "user2", List.of("Warriors"), List.of(), List.of("WNBA"), List.of("GAME_START")
        );

        service.updateSubscriptionCache("user2", prefs);

        assertTrue(jedis.sismember("sub:team:Warriors", "user2"));
        assertTrue(jedis.sismember("sub:league:WNBA", "user2"));
        assertTrue(jedis.sismember("sub:notif:GAME_START", "user2"));
    }
}
