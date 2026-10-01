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

class GameStateServiceImplTest {

    private JedisPool pool;
    private Jedis jedis;
    private GameStateServiceImpl service;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        service = new GameStateServiceImpl(pool);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    @Test
    void getGameState_returnsNullIfGameNotSeen() {
        assertNull(service.getGameState("unknown-game"));
    }

    @Test
    void updateGameState_gameStart_initializesState() {
        GameEvent event = new GameEvent("g1", "e1", "GAME_START", "Lakers",
            1000L, "Game started", "Lakers", "Celtics", 0, 0, "IN_PROGRESS", "NBA");

        service.updateGameState(event);
        GameState state = service.getGameState("g1");

        assertNotNull(state);
        assertEquals("Lakers", state.getHomeTeam());
        assertEquals("Celtics", state.getAwayTeam());
        assertEquals("IN_PROGRESS", state.getStatus());
        assertEquals(0, state.getHomeScore());
        assertEquals(0, state.getAwayScore());
    }

    @Test
    void updateGameState_scoreUpdate_setsFullScores() {
        GameEvent start = new GameEvent("g1", "e1", "GAME_START", "Lakers",
            1000L, "Start", "Lakers", "Celtics", 0, 0, "IN_PROGRESS", "NBA");
        service.updateGameState(start);

        GameEvent score = new GameEvent("g1", "e2", "SCORE_UPDATE", "Lakers",
            2000L, "Score!", null, null, 3, 1, null, null);
        service.updateGameState(score);

        GameState state = service.getGameState("g1");
        assertEquals(3, state.getHomeScore());
        assertEquals(1, state.getAwayScore());
    }

    @Test
    void updateGameState_gameEnd_setsFinalStatus() {
        GameEvent start = new GameEvent("g1", "e1", "GAME_START", "Lakers",
            1000L, "Start", "Lakers", "Celtics", 0, 0, "IN_PROGRESS", "NBA");
        service.updateGameState(start);

        GameEvent end = new GameEvent("g1", "e3", "GAME_END", "Lakers",
            5000L, "Final", null, null, 110, 98, "FINAL", null);
        service.updateGameState(end);

        GameState state = service.getGameState("g1");
        assertEquals("FINAL", state.getStatus());
        assertEquals(110, state.getHomeScore());
        assertEquals(98, state.getAwayScore());
    }

    @Test
    void updateGameState_ignoresStaleEvent() {
        GameEvent first = new GameEvent("g1", "e1", "GAME_START", "Lakers",
            2000L, "Start", "Lakers", "Celtics", 0, 0, "IN_PROGRESS", "NBA");
        service.updateGameState(first);

        GameEvent stale = new GameEvent("g1", "e0", "SCORE_UPDATE", "Lakers",
            500L, "Old score", null, null, 99, 99, null, null);
        service.updateGameState(stale);

        GameState state = service.getGameState("g1");
        assertEquals(0, state.getHomeScore());
        assertEquals(0, state.getAwayScore());
    }

    @Test
    void updateGameState_ignoresDuplicateTimestamp() {
        GameEvent first = new GameEvent("g1", "e1", "GAME_START", "Lakers",
            1000L, "Start", "Lakers", "Celtics", 0, 0, "IN_PROGRESS", "NBA");
        service.updateGameState(first);

        GameEvent duplicate = new GameEvent("g1", "e1", "SCORE_UPDATE", "Lakers",
            1000L, "Dup", null, null, 50, 50, null, null);
        service.updateGameState(duplicate);

        GameState state = service.getGameState("g1");
        assertEquals(0, state.getHomeScore());
    }
}
