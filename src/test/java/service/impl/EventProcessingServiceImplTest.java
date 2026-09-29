package service.impl;

import model.GameEvent;
import model.GameState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import static org.junit.jupiter.api.Assertions.*;

class EventProcessingServiceImplTest {

    private JedisPool pool;
    private Jedis jedis;
    private GameStateServiceImpl gameStateService;
    private EventProcessingServiceImpl service;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        gameStateService = new GameStateServiceImpl(pool);
        service = new EventProcessingServiceImpl(gameStateService, pool);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    @Test
    void processEvent_publishesToStream() {
        GameEvent event = new GameEvent("g1", "e1", "GAME_START", "Lakers",
            1000L, "Start", "Lakers", "Celtics", 0, 0, "IN_PROGRESS", "NBA");

        service.processEvent(event);

        assertEquals(1L, jedis.xlen("game-events"));
    }

    @Test
    void processEvent_updatesGameState() {
        GameEvent event = new GameEvent("g1", "e1", "GAME_START", "Lakers",
            1000L, "Start", "Lakers", "Celtics", 0, 0, "IN_PROGRESS", "NBA");

        service.processEvent(event);

        GameState state = gameStateService.getGameState("g1");
        assertNotNull(state);
        assertEquals("IN_PROGRESS", state.getStatus());
    }

    @Test
    void processEvent_ignoresNullGameId() {
        GameEvent event = new GameEvent(null, "e1", "GAME_START", "Lakers",
            1000L, "Start", "Lakers", "Celtics", 0, 0, "IN_PROGRESS", "NBA");

        service.processEvent(event);

        assertEquals(0L, jedis.xlen("game-events"));
    }

    @Test
    void processEvent_ignoresBlankEventType() {
        GameEvent event = new GameEvent("g1", "e1", "", "Lakers",
            1000L, "Start", "Lakers", "Celtics", 0, 0, "IN_PROGRESS", "NBA");

        service.processEvent(event);

        assertEquals(0L, jedis.xlen("game-events"));
    }

    @Test
    void processEvent_ignoresStaleEvent() {
        GameEvent first = new GameEvent("g1", "e1", "GAME_START", "Lakers",
            2000L, "Start", "Lakers", "Celtics", 0, 0, "IN_PROGRESS", "NBA");
        service.processEvent(first);

        GameEvent stale = new GameEvent("g1", "e0", "SCORE_UPDATE", "Lakers",
            500L, "Old", null, null, 50, 50, null, null);
        service.processEvent(stale);

        assertEquals(1L, jedis.xlen("game-events"));
    }

    @Test
    void processEvent_ignoresDuplicateTimestamp() {
        GameEvent first = new GameEvent("g1", "e1", "GAME_START", "Lakers",
            1000L, "Start", "Lakers", "Celtics", 0, 0, "IN_PROGRESS", "NBA");
        service.processEvent(first);

        GameEvent dup = new GameEvent("g1", "e1", "SCORE_UPDATE", "Lakers",
            1000L, "Dup", null, null, 50, 50, null, null);
        service.processEvent(dup);

        assertEquals(1L, jedis.xlen("game-events"));
    }
}
