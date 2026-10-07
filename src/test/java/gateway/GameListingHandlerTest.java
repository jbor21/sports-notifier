package gateway;

import model.GameEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.impl.EventProcessingServiceImpl;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GameListingHandlerTest {

    private JedisPool pool;
    private Jedis jedis;
    private EventProcessingServiceImpl eps;
    private GameListingHandler handler;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        eps = new EventProcessingServiceImpl(pool);
        handler = new GameListingHandler(pool);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    private void accept(String gameId, String type, String status) {
        eps.processEvent(new GameEvent(gameId, gameId + ":1", type, "LAL", 1000L, "d",
            "LAL", "BOS", 1, 0, status, "NBA", 1));
    }

    private List<String> ids() {
        return handler.listGames().stream().map(g -> g.get("gameId")).toList();
    }

    @Test
    void listsRecentGamesWithIds() {
        accept("g1", "GAME_START", "IN_PROGRESS");
        accept("g2", "GAME_END", "FINAL");
        List<Map<String, String>> games = handler.listGames();
        assertEquals(2, games.size());
        assertTrue(ids().containsAll(List.of("g1", "g2")));
        assertEquals("LAL", games.get(0).get("homeTeam"));
    }

    @Test
    void skipsExpiredRecords() {
        accept("g1", "GAME_START", "IN_PROGRESS");
        accept("g2", "GAME_START", "IN_PROGRESS");
        jedis.del("game:g1");
        assertEquals(List.of("g2"), ids());
    }

    @Test
    void leavesOutScheduledGames() {
        accept("g1", "GAME_SYNC", "SCHEDULED");
        accept("g2", "GAME_START", "IN_PROGRESS");
        assertEquals(List.of("g2"), ids());
    }

    @Test
    void ignoresGameKeysNotInRecentList() {
        jedis.hset("game:old", Map.of("status", "FINAL", "homeTeam", "LAL"));
        assertTrue(handler.listGames().isEmpty());
    }
}
