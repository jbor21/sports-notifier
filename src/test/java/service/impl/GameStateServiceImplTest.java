package service.impl;

import catalog.Teams;
import com.google.gson.JsonObject;
import games.GameQuery;
import games.GameSummaries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The day lists read games:start, then game:<id> and sched:<id> for each id (Phase 3.4 spec, 5.3). */
class GameStateServiceImplTest {

    private static final long DAY_START = 1_791_244_800_000L;   // 2026-10-06T00:00Z
    private static final long HOUR = 3_600_000L;

    private JedisPool pool;
    private Jedis jedis;
    private GameStateServiceImpl svc;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        svc = new GameStateServiceImpl(pool, new GameSummaries(Teams.load()));
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    private void game(String id, String league, String home, String away, long start, String status) {
        jedis.hset("game:" + id, Map.of("league", league, "homeTeam", home, "awayTeam", away, "homeScore", "1",
            "awayScore", "0", "status", status, "phase", "LIVE", "period", "2", "startTime", String.valueOf(start),
            "version", "5"));
        jedis.zadd("games:start", start, id);
    }

    private void sched(String id, String league, String home, String away, long start) {
        jedis.hset("sched:" + id, Map.of("league", league, "homeTeam", home, "awayTeam", away, "homeScore", "0",
            "awayScore", "0", "status", "SCHEDULED", "phase", "PRE", "period", "0", "startTime", String.valueOf(start),
            "venue", "Arena"));
        jedis.zadd("games:start", start, id);
    }

    private static List<String> ids(List<JsonObject> games) {
        return games.stream().map(g -> g.get("id").getAsString()).toList();
    }

    @Test
    void listsTheDaysGamesInStartOrder() {
        game("nfl-2", "NFL", "CHI", "LV", DAY_START + 20 * HOUR, "IN_PROGRESS");
        sched("nba-1", "NBA", "BOS", "LAL", DAY_START + 18 * HOUR);
        sched("nfl-1", "NFL", "KC", "BUF", DAY_START + 20 * HOUR);
        sched("nfl-tomorrow", "NFL", "KC", "BUF", DAY_START + 25 * HOUR);
        assertEquals(List.of("nba-1", "nfl-1", "nfl-2"),
            ids(svc.listDay(new GameQuery(DAY_START, DAY_START + 24 * HOUR, null, null, null))));
    }

    @Test
    void listLeavesOutReplayedGames() {
        game("nfl-1", "NFL", "CHI", "LV", DAY_START + HOUR, "IN_PROGRESS");
        game("nfl-rpx-2", "NFL", "KC", "BUF", DAY_START + 2 * HOUR, "IN_PROGRESS");
        game("nfl-sim9-3", "NFL", "KC", "BUF", DAY_START + 3 * HOUR, "IN_PROGRESS");
        assertEquals(List.of("nfl-1", "nfl-sim9-3"),
            ids(svc.listDay(new GameQuery(DAY_START, DAY_START + 24 * HOUR, null, null, null))));
    }

    @Test
    void skipsIdsWithNeitherHash() {
        jedis.zadd("games:start", DAY_START + HOUR, "nfl-gone");
        sched("nfl-1", "NFL", "KC", "BUF", DAY_START + 2 * HOUR);
        assertEquals(List.of("nfl-1"), ids(svc.listDay(new GameQuery(DAY_START, DAY_START + 24 * HOUR, null, null, null))));
    }

    @Test
    void filtersLeagueTeamStatus() {
        game("nfl-2", "NFL", "CHI", "LV", DAY_START + 20 * HOUR, "IN_PROGRESS");
        sched("nba-1", "NBA", "BOS", "LAL", DAY_START + 18 * HOUR);
        sched("nfl-1", "NFL", "KC", "BUF", DAY_START + 19 * HOUR);
        long from = DAY_START, to = DAY_START + 24 * HOUR;
        assertEquals(List.of("nfl-1", "nfl-2"), ids(svc.listDay(new GameQuery(from, to, "NFL", null, null))));
        assertEquals(List.of("nfl-2"), ids(svc.listDay(new GameQuery(from, to, null, "nfl-lv", null))));
        assertEquals(List.of("nba-1", "nfl-1"), ids(svc.listDay(new GameQuery(from, to, null, null, "scheduled"))));
    }

    @Test
    void detailEmptyWhenUnknown() {
        assertTrue(svc.detail("nfl-nope").isEmpty());
        sched("nfl-1", "NFL", "KC", "BUF", DAY_START);
        assertEquals("Arena", svc.detail("nfl-1").orElseThrow().get("venue").getAsString());
    }
}
