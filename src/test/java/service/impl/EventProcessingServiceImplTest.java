package service.impl;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import telemetry.Traces;
import testsupport.TelemetryTestSupport;
import model.GameEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.ProcessResult;
import testsupport.TestPublisher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventProcessingServiceImplTest {

    private JedisPool pool;
    private Jedis jedis;
    private EventProcessingServiceImpl service;
    private TestPublisher sent;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        sent = new TestPublisher(true);
        service = new EventProcessingServiceImpl(pool, sent, System::currentTimeMillis);
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
        assertEquals(1, sent.plays.size());
        assertEquals("10", jedis.hget("game:g1", "lastSeq"));
        assertEquals("LAL", jedis.hget("game:g1", "homeTeam"));
        assertEquals("2", jedis.hget("game:g1", "homeScore"));
    }

    @Test
    void versionRisesOnlyOnAccept() {
        // version orders accepted plays for everything after the script; seq can't, because a heal can lower it.
        service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));
        long first = Long.parseLong(jedis.hget("game:g1", "version"));
        service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));   // duplicate
        service.processEvent(ev("e0", 5, 1, 0, "SCORE_UPDATE"));    // stale
        assertEquals(String.valueOf(first), jedis.hget("game:g1", "version"));
        service.processEvent(ev("e2", 20, 4, 0, "SCORE_UPDATE"));
        assertEquals(String.valueOf(first + 1), jedis.hget("game:g1", "version"));
    }

    @Test
    void versionSurvivesTheGameRecordExpiring() {
        // game:<id> expires a day after a final, but Stage 1 remembers the last version for 8 days.
        // If the counter restarted at 1, every play of a reused game id (a rescheduled game) would
        // be dropped by Stage 1 as already handled.
        service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));
        jedis.del("game:g1");
        service.processEvent(ev("e2", 20, 4, 0, "SCORE_UPDATE"));
        assertEquals(sent.plays.get(0).getVersion() + 1, sent.plays.get(1).getVersion(), "the counter carried on");
        long ttl = jedis.ttl("version:g1");
        assertTrue(ttl > 8 * 86_400, "the counter outlives Stage 1's 8-day memory: " + ttl);
    }

    /**
     * Postgres outlives Redis now (make down, an AWS teardown). If the counter restarted at 1, the
     * replayed game's notifications would reuse old ids (<gameId>:v<version>) and history's primary
     * key would silently drop them; an open UI would also ignore the new versions as stale. So a
     * missing counter starts from the clock (Phase 3.2 spec, 7.2; final review, Important 2).
     */
    @Test
    void counterReseededAfterARedisResetIsHigherThanBefore() {
        long[] clock = {1_791_000_000_000L};
        EventProcessingServiceImpl eps = new EventProcessingServiceImpl(pool, sent, () -> clock[0]);
        for (int n = 1; n <= 50; n++) eps.processEvent(ev("e" + n, 10 + n, n, 0, "SCORE_UPDATE"));
        long before = sent.plays.get(sent.plays.size() - 1).getVersion();
        jedis.flushDB();                       // Redis came back empty
        clock[0] += 1_000;                     // a second later
        eps.processEvent(ev("again-1", 11, 1, 0, "SCORE_UPDATE"));
        long after = sent.plays.get(sent.plays.size() - 1).getVersion();
        assertTrue(after > before, "reseeded " + after + " must be above the old " + before);
        assertEquals(String.valueOf(after), jedis.hget("game:g1", "version"), "stored exactly, not rounded");
    }

    @Test
    void publishedPlayCarriesItsVersion() {
        service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));
        service.processEvent(ev("e2", 20, 4, 0, "SCORE_UPDATE"));
        assertEquals(sent.plays.get(0).getVersion() + 1, sent.plays.get(sent.plays.size() - 1).getVersion());
    }

    @Test
    void sameIdTwiceIsDuplicate() {
        service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));
        assertEquals(ProcessResult.DUPLICATE, service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE")));
        assertEquals(1, sent.plays.size());
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
    void acceptedPlaySavesPosition() {
        GameEvent e = ev("e1", 10, 2, 0, "GAME_SYNC").withPosition("LIVE", 4, 2520, "TOP", 0, "IN_PLAY", 3, "4:12 - 4th");
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(e));
        Map<String, String> g = jedis.hgetAll("game:g1");
        assertEquals("LIVE", g.get("phase"));
        assertEquals("4", g.get("period"));
        assertEquals("2520", g.get("clockTenths"));
        assertEquals("TOP", g.get("half"));
        assertEquals("0", g.get("outs"));
        assertEquals("IN_PLAY", g.get("stage"));
        assertEquals("3", g.get("seasonType"));
        assertEquals("4:12 - 4th", g.get("detail"));
    }

    @Test
    void gameSyncSavesStateAndIsSentToo() {
        // Every accepted play goes to the log, GAME_SYNC included; Stage 1 notifies nobody for it.
        GameEvent sync = new GameEvent("g1", "e1", "GAME_SYNC", "LAL", 1000L, "LAL vs BOS: HALFTIME",
            "LAL", "BOS", 50, 48, "HALFTIME", "NBA", 10);
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(sync));
        assertEquals(List.of("GAME_SYNC"), sent.types());
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
        assertTrue(sent.plays.isEmpty());
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
        com.google.gson.JsonObject game = new GameStateServiceImpl(pool, new games.GameSummaries(catalog.Teams.load()))
            .detail("g1").orElseThrow();
        assertEquals("in_progress", game.get("status").getAsString());
        assertEquals(2, game.getAsJsonObject("home").get("score").getAsInt());
        assertEquals("BOS", game.getAsJsonObject("away").getAsJsonObject("team").get("abbreviation").getAsString());
    }

    private static final long DAY = 86_400_000L;

    private GameEvent scheduled(String gameId, String eventId, long seq, long startTime) {
        return new GameEvent(gameId, eventId, "GAME_SYNC", "LAL", 1000L, "desc",
            "LAL", "BOS", 3, 1, "IN_PROGRESS", "NBA", seq)
            .withSchedule(startTime, List.of(new model.LinePeriod(1, 3, 1)));
    }

    @Test
    void savesStartTimeAndLineScore() {
        long start = System.currentTimeMillis() - 3_600_000L;
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(scheduled("g1", "e1", 10, start)));
        assertEquals(String.valueOf(start), jedis.hget("game:g1", "startTime"));
        assertEquals("[{\"period\":1,\"home\":3,\"away\":1}]", jedis.hget("game:g1", "lineScore"));
    }

    @Test
    void indexesGamesStartAndTrims() {
        long now = System.currentTimeMillis();
        service.processEvent(scheduled("old", "o1", 10, now - 4 * DAY));
        service.processEvent(scheduled("recent", "r1", 10, now - 2 * DAY));
        service.processEvent(scheduled("g1", "e1", 10, now));
        assertNull(jedis.zscore("games:start", "old"), "starts older than 3 days are trimmed");
        assertEquals((double) (now - 2 * DAY), jedis.zscore("games:start", "recent"));
        assertEquals((double) now, jedis.zscore("games:start", "g1"));
    }

    @Test
    void zeroStartTimeNotIndexed() {
        service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));
        assertNull(jedis.zscore("games:start", "g1"));
    }

    @Test
    void acceptStoresTraceparentInOutbox() {
        TestPublisher held = new TestPublisher(false);
        EventProcessingServiceImpl eps = new EventProcessingServiceImpl(pool, held, System::currentTimeMillis);
        try (TelemetryTestSupport otel = TelemetryTestSupport.install()) {
            Span outer = Traces.start("poll", SpanKind.CLIENT, Context.root());
            try (Scope s = outer.makeCurrent()) {
                assertEquals(ProcessResult.ACCEPTED, eps.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE")));
            }
            outer.end();
            var accept = otel.spans("accept").get(0);
            assertEquals(outer.getSpanContext().getSpanId(), accept.getParentSpanId());
            assertEquals(SpanKind.SERVER, accept.getKind());
            assertEquals("accepted", accept.getAttributes().get(AttributeKey.stringKey("accept.result")));
            String stored = jedis.hget("outbox:g1", "traceparent");
            assertNotNull(stored);
            assertTrue(stored.contains(accept.getTraceId()) && stored.contains(accept.getSpanId()));
            assertEquals(1, otel.latencyCount("accepted"));
            assertEquals(1, otel.counter("plays.accepted", "result", "accepted"));
        }
    }

    @Test
    void telemetryOffStoresNoTraceparent() {
        TestPublisher held = new TestPublisher(false);
        new EventProcessingServiceImpl(pool, held, System::currentTimeMillis).processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));
        assertFalse(jedis.hexists("outbox:g1", "traceparent"));
        assertEquals(java.util.Arrays.asList((String) null), held.traceparents());
    }

    @Test
    void duplicateCountsButNoLatency() {
        try (TelemetryTestSupport otel = TelemetryTestSupport.install()) {
            service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));
            service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE"));
            assertEquals(1, otel.counter("plays.accepted", "result", "duplicate"));
            assertEquals(1, otel.latencyCount("accepted"));
        }
    }
}
