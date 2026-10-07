package service.impl;

import catalog.AlertCatalog;
import catalog.AlertSetting;
import catalog.Teams;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import testsupport.PostgresTestSupport;
import util.ApiResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The follows API (Phase 3.2 spec, section 5.1). */
class FollowServiceTest {

    static final String GAME_ID = "mlb-401907992";
    static final Map<String, String> GAME = Map.of("homeTeam", "CHW", "awayTeam", "CLE", "league", "MLB", "status", "SCHEDULED");

    private JedisPool pool;
    private Jedis jedis;
    private FollowService follows;
    private String bob;
    private String alice;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        AccountService accounts = new AccountService(PostgresTestSupport.dataSource());
        bob = accounts.signup("bob", "password-123", null).body().id();
        alice = accounts.signup("alice", "password-123", null).body().id();
        follows = new FollowService(PostgresTestSupport.dataSource(), pool, AlertCatalog.load(), Teams.load(),
                id -> GAME_ID.equals(id) ? Optional.of(GAME) : Optional.empty());
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    @Test
    void createFillsCatalogDefaults() {
        ApiResult<Follow> r = follows.create(bob, "team", "mlb-chw");
        assertEquals(201, r.status());
        Follow f = r.body();
        assertEquals("MLB", f.league());
        assertTrue(f.alerts().get("GAME_START").enabled());
        assertTrue(f.alerts().get("RUN_SCORED").enabled());
        assertTrue(f.alerts().get("FINAL").enabled());
        assertFalse(f.alerts().get("LEAD_CHANGE").enabled());
        assertFalse(f.alerts().containsKey("DAILY_SUMMARY"));
        JsonObject api = follows.toApi(f);
        assertEquals("Chicago White Sox", api.getAsJsonObject("target").get("displayName").getAsString());
        assertEquals("team", api.getAsJsonObject("target").get("kind").getAsString());
        assertEquals("MLB", api.get("leagueId").getAsString());
    }

    @Test
    void followTwiceReturns200SameId() {
        String id = follows.create(bob, "team", "mlb-chw").body().id();
        ApiResult<Follow> again = follows.create(bob, "team", "mlb-chw");
        assertEquals(200, again.status());
        assertEquals(id, again.body().id());
        assertEquals(1, follows.list(bob).body().size());
    }

    @Test
    void unknownTargets() {
        assertEquals(404, follows.create(bob, "team", "mlb-xyz").status());
        assertEquals(404, follows.create(bob, "game", "mlb-1").status());
        assertEquals(404, follows.create(bob, "league", "XFL").status());
        ApiResult<Follow> badKind = follows.create(bob, "player", "mlb-chw");
        assertEquals(422, badKind.status());
        assertTrue(badKind.error().fields().containsKey("target.kind"));
    }

    @Test
    void gameAndLeagueFollows() {
        Follow game = follows.create(bob, "game", GAME_ID).body();
        assertEquals("MLB", game.league());
        assertEquals("CLE @ CHW", follows.toApi(game).getAsJsonObject("target").get("displayName").getAsString());
        Follow league = follows.create(bob, "league", "NFL").body();
        assertTrue(league.alerts().containsKey("DAILY_SUMMARY"));
        assertTrue(league.alerts().get("CLOSE_LATE").enabled());
    }

    @Test
    void otherUsersFollowIs404() {
        String id = follows.create(bob, "team", "mlb-chw").body().id();
        assertEquals(404, follows.get(alice, id).status());
        assertEquals(404, follows.patch(alice, id, body("{\"alerts\":{\"LEAD_CHANGE\":{\"enabled\":true}}}")).status());
        assertEquals(404, follows.delete(alice, id).status());
        assertEquals(404, follows.get(bob, "not-a-uuid").status());
        assertFalse(follows.get(bob, id).body().alerts().get("LEAD_CHANGE").enabled());
    }

    @Test
    void patchReplacesOnlyGivenTypes() {
        String id = follows.create(bob, "team", "mlb-chw").body().id();
        ApiResult<Follow> r = follows.patch(bob, id, body(
                "{\"alerts\":{\"LEAD_CHANGE\":{\"enabled\":true},\"END_OF_INNING\":{\"enabled\":true,\"options\":{\"innings\":[3,6,9]}}}}"));
        assertEquals(200, r.status());
        Map<String, AlertSetting> a = r.body().alerts();
        assertTrue(a.get("LEAD_CHANGE").enabled());
        assertEquals(List.of(3.0, 6.0, 9.0), a.get("END_OF_INNING").options().get("innings"));
        assertTrue(a.get("GAME_START").enabled(), "untouched");
        assertEquals(a, follows.get(bob, id).body().alerts());
    }

    @Test
    void patchProblems() {
        String id = follows.create(bob, "team", "mlb-chw").body().id();
        Map<String, String> cases = Map.of(
                "{}", "alerts",
                "{\"alerts\":[]}", "alerts",
                "{\"alerts\":{\"LEAD_CHANGE\":true}}", "alerts.LEAD_CHANGE",
                "{\"alerts\":{\"LEAD_CHANGE\":{}}}", "alerts.LEAD_CHANGE",
                "{\"alerts\":{\"LEAD_CHANGE\":{\"enabled\":\"yes\"}}}", "alerts.LEAD_CHANGE",
                "{\"alerts\":{\"TOUCHDOWN\":{\"enabled\":true}}}", "alerts.TOUCHDOWN",
                "{\"alerts\":{\"END_OF_INNING\":{\"enabled\":true,\"options\":{\"innings\":[\"9\"]}}}}", "alerts.END_OF_INNING",
                "{\"alerts\":{\"END_OF_INNING\":{\"enabled\":true,\"options\":{\"innings\":[0]}}}}", "alerts.END_OF_INNING",
                "{\"alerts\":{\"END_OF_INNING\":{\"enabled\":true,\"options\":[]}}}", "alerts.END_OF_INNING");
        cases.forEach((json, field) -> {
            ApiResult<Follow> r = follows.patch(bob, id, body(json));
            assertEquals(422, r.status(), json);
            assertTrue(r.error().fields().containsKey(field), json + " should name " + field + ", got " + r.error().fields());
        });
        Follow before = follows.get(bob, id).body();
        ApiResult<Follow> empty = follows.patch(bob, id, body("{\"alerts\":{}}"));
        assertEquals(200, empty.status());
        assertEquals(before.alerts(), empty.body().alerts());
        assertEquals(before.alerts(), follows.get(bob, id).body().alerts(), "no rejected patch changed anything");
    }

    @Test
    void deleteRacingPatchLeavesNoSets() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 100; i++) {
                String id = follows.create(bob, "team", "mlb-chw").body().id();
                CyclicBarrier gate = new CyclicBarrier(2);
                Future<Integer> del = threads.submit(() -> { gate.await(); return follows.delete(bob, id).status(); });
                Future<Integer> patch = threads.submit(() -> {
                    gate.await();
                    return follows.patch(bob, id, body("{\"alerts\":{\"LEAD_CHANGE\":{\"enabled\":true}}}")).status();
                });
                assertEquals(204, del.get(10, TimeUnit.SECONDS));
                assertTrue(List.of(200, 404).contains(patch.get(10, TimeUnit.SECONDS)));
                assertEquals(404, follows.delete(bob, id).status(), "second delete");
                assertEquals(404, follows.patch(bob, id, body("{\"alerts\":{}}")).status(), "patch after delete");
                for (String key : jedis.keys("sub:team:mlb-chw:*")) {
                    assertFalse(jedis.sismember(key, bob), "iteration " + i + ": still in " + key);
                }
            }
        } finally {
            threads.shutdownNow();
        }
    }

    static JsonObject body(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
