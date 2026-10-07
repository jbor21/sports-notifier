package service.impl;

import model.GameEvent;
import model.GameState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.ProcessResult;

import static org.junit.jupiter.api.Assertions.*;

class EventProcessingServiceImplTest {

    private JedisPool pool;
    private Jedis jedis;
    private EventProcessingServiceImpl service;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        service = new EventProcessingServiceImpl(pool, new RedisStreamPublisher(pool), System::currentTimeMillis);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    private static GameEvent ev(String id, long seq, int home, int away, String type) {
        return new GameEvent("g1", id, type, "LAL", 1000L, "desc",
            "LAL", "BOS", home, away, "IN_PROGRESS", "NBA", seq);
    }

    @Test
    void acceptedEventIsPublishedAndSaved() {
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE")));
        assertEquals(1L, jedis.xlen("game-events"));
        assertEquals("10", jedis.hget("game:g1", "lastSeq"));
        assertEquals("LAL", jedis.hget("game:g1", "homeTeam"));
        assertEquals("2", jedis.hget("game:g1", "homeScore"));
    }

    @Test
    void versionRisesOnlyOnAccept() {
        // version orders accepted plays for everything after the script; seq can't, because a heal can lower it.
        service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));
        assertEquals("1", jedis.hget("game:g1", "version"));
        service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));   // duplicate
        service.processEvent(ev("e0", 5, 1, 0, "SCORE_UPDATE"));    // stale
        assertEquals("1", jedis.hget("game:g1", "version"));
        service.processEvent(ev("e2", 20, 4, 0, "SCORE_UPDATE"));
        assertEquals("2", jedis.hget("game:g1", "version"));
    }

    @Test
    void publishedPlayCarriesItsVersion() {
        service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));
        service.processEvent(ev("e2", 20, 4, 0, "SCORE_UPDATE"));
        String latest = jedis.xrevrange("game-events", "+", "-", 1).get(0).getFields().get("data");
        assertEquals(2L, util.GameEventParser.parse(latest).getVersion());
    }

    @Test
    void sameIdTwiceIsDuplicate() {
        service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));
        assertEquals(ProcessResult.DUPLICATE, service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE")));
        assertEquals(1L, jedis.xlen("game-events"));
    }

    @Test
    void retryOfAcceptedEventIsDuplicateNotStale() {
        // The id is checked before seq, so a retry is recognised as the same event (decision 5 relies on this).
        service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));
        service.processEvent(ev("e2", 20, 4, 0, "SCORE_UPDATE"));
        assertEquals(ProcessResult.DUPLICATE, service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE")));
    }

    @Test
    void lowerSeqIsStaleAndIdNotClaimed() {
        service.processEvent(ev("e2", 20, 4, 0, "SCORE_UPDATE"));
        assertEquals(ProcessResult.STALE, service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE")));
        assertFalse(jedis.exists("processed:e1"));
        assertEquals("4", jedis.hget("game:g1", "homeScore"));
    }

    @Test
    void equalSeqNewIdIsStale() {
        service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));
        assertEquals(ProcessResult.STALE, service.processEvent(ev("e3", 10, 3, 0, "SCORE_UPDATE")));
    }

    @Test
    void gameStartKeepsCarriedScore() {
        service.processEvent(ev("e1", 10, 3, 1, "GAME_START"));
        assertEquals("3", jedis.hget("game:g1", "homeScore"));
        assertEquals("1", jedis.hget("game:g1", "awayScore"));
    }

    @Test
    void gameSyncSavesStateButPublishesNothing() {
        GameEvent sync = new GameEvent("g1", "e1", "GAME_SYNC", "LAL", 1000L, "LAL vs BOS: HALFTIME",
            "LAL", "BOS", 50, 48, "HALFTIME", "NBA", 10);
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(sync));
        assertEquals(0L, jedis.xlen("game-events"));
        assertEquals("HALFTIME", jedis.hget("game:g1", "status"));
    }

    @Test
    void missingSeqIsInvalid() {
        assertEquals(ProcessResult.INVALID, service.processEvent(ev("e1", 0, 2, 0, "SCORE_UPDATE")));
        assertFalse(jedis.exists("game:g1"));
    }

    @Test
    void missingStatusIsInvalid() {
        GameEvent noStatus = new GameEvent("g1", "e1", "SCORE_UPDATE", "LAL", 1000L, "desc",
            "LAL", "BOS", 2, 0, null, "NBA", 10);
        assertEquals(ProcessResult.INVALID, service.processEvent(noStatus));
    }

    @Test
    void missingGameIdIsInvalid() {
        GameEvent noGame = new GameEvent(null, "e1", "GAME_START", "LAL", 1000L, "desc",
            "LAL", "BOS", 0, 0, "IN_PROGRESS", "NBA", 10);
        assertEquals(ProcessResult.INVALID, service.processEvent(noGame));
        assertEquals(0L, jedis.xlen("game-events"));
    }

    @Test
    void blankEventTypeIsInvalid() {
        assertEquals(ProcessResult.INVALID, service.processEvent(ev("e1", 10, 0, 0, "")));
    }

    @Test
    void malformedJsonIsInvalid() {
        assertEquals(ProcessResult.INVALID, service.processJson("{not json"));
        assertEquals(ProcessResult.INVALID, service.processJson("{\"gameId\":\"g1\",\"seq\":\"abc\"}"));
        assertEquals(ProcessResult.INVALID, service.processJson(""));
    }

    @Test
    void savedStateIsReadableThroughGameStateService() {
        service.processEvent(ev("e1", 10, 2, 1, "SCORE_UPDATE"));
        GameState state = new GameStateServiceImpl(pool).getGameState("g1");
        assertNotNull(state);
        assertEquals("IN_PROGRESS", state.getStatus());
        assertEquals(2, state.getHomeScore());
        assertEquals("BOS", state.getAwayTeam());
    }
}
