package service.impl;

import catalog.AlertCatalog;
import catalog.FollowKind;
import catalog.Teams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import testsupport.PostgresTestSupport;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** A save leaves the user in exactly the sets of the follow's enabled switches (spec 5.2, 5.3). */
class FollowSetsTest {

    private JedisPool pool;
    private Jedis jedis;
    private FollowService follows;
    private FollowSets sets;
    private String bob;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        bob = new AccountService(PostgresTestSupport.dataSource()).signup("bob", "password-123", null).body().id();
        sets = new FollowSets(pool);
        follows = new FollowService(PostgresTestSupport.dataSource(), pool, AlertCatalog.load(), Teams.load(), id -> Optional.empty());
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    @Test
    void keyNamesTargetAndAlert() {
        assertEquals("sub:team:mlb-chw:GAME_START", FollowSets.key(FollowKind.TEAM, "mlb-chw", "GAME_START"));
        assertEquals("sub:league:NFL:FINAL", FollowSets.key(FollowKind.LEAGUE, "NFL", "FINAL"));
        assertEquals("sub:game:mlb-1:FINAL", FollowSets.key(FollowKind.GAME, "mlb-1", "FINAL"));
    }

    @Test
    void createPatchDeleteMoveTheUser() {
        Follow f = follows.create(bob, "team", "mlb-chw").body();
        assertEquals(enabledKeys(f), memberOf());
        assertTrue(memberOf().contains("sub:team:mlb-chw:RUN_SCORED"));
        assertFalse(memberOf().contains("sub:team:mlb-chw:LEAD_CHANGE"));

        follows.patch(bob, f.id(), FollowServiceTest.body("{\"alerts\":{\"LEAD_CHANGE\":{\"enabled\":true},\"RUN_SCORED\":{\"enabled\":false}}}"));
        Follow patched = follows.get(bob, f.id()).body();
        assertEquals(enabledKeys(patched), memberOf());
        assertTrue(memberOf().contains("sub:team:mlb-chw:LEAD_CHANGE"));
        assertFalse(memberOf().contains("sub:team:mlb-chw:RUN_SCORED"));

        follows.delete(bob, f.id());
        assertEquals(Set.of(), memberOf());
    }

    @Test
    void writeStateTwiceIsTheSame() {
        Follow f = follows.create(bob, "team", "mlb-chw").body();
        Set<String> once = memberOf();
        sets.writeState(f);
        sets.writeState(f);
        assertEquals(once, memberOf());
    }

    /** Every sub:* set Bob is in. */
    private Set<String> memberOf() {
        Set<String> out = new TreeSet<>();
        for (String key : jedis.keys("sub:*")) {
            if (jedis.sismember(key, bob)) out.add(key);
        }
        return out;
    }

    /** The enabled switches' sets, plus the follow's ANY set. */
    private static Set<String> enabledKeys(Follow f) {
        Set<String> out = new TreeSet<>();
        f.alerts().forEach((type, s) -> { if (s.enabled()) out.add(FollowSets.key(f.kind(), f.targetId(), type)); });
        out.add(FollowSets.key(f.kind(), f.targetId(), "ANY"));
        return out;
    }

    private void patch(Follow f, String json) {
        assertEquals(200, follows.patch(bob, f.id(), FollowServiceTest.body(json)).status());
    }

    @Test
    void anySetHoldsEveryFollower() {
        Follow f = follows.create(bob, "team", "mlb-chw").body();
        StringBuilder off = new StringBuilder("{\"alerts\":{");
        f.alerts().keySet().forEach(t -> off.append('"').append(t).append("\":{\"enabled\":false},"));
        off.setLength(off.length() - 1);
        patch(f, off.append("}}").toString());
        assertEquals(Set.of("sub:team:mlb-chw:ANY"), memberOf());
    }

    @Test
    void deleteRemovesFromAny() {
        Follow f = follows.create(bob, "team", "mlb-chw").body();
        assertTrue(jedis.sismember("sub:team:mlb-chw:ANY", bob));
        follows.delete(bob, f.id());
        assertFalse(jedis.sismember("sub:team:mlb-chw:ANY", bob));
    }

    @Test
    void chosenInningsGoInInningSets() {
        Follow f = follows.create(bob, "team", "mlb-chw").body();
        patch(f, "{\"alerts\":{\"END_OF_INNING\":{\"enabled\":true,\"options\":{\"innings\":[3,7]}}}}");
        Set<String> in = memberOf();
        assertTrue(in.contains("sub:team:mlb-chw:END_OF_INNING:3"));
        assertTrue(in.contains("sub:team:mlb-chw:END_OF_INNING:7"));
        assertFalse(in.contains("sub:team:mlb-chw:END_OF_INNING"));
        assertFalse(in.contains("sub:team:mlb-chw:END_OF_INNING:4"));
    }

    @Test
    void everyInningGoesInPlainSet() {
        Follow f = follows.create(bob, "team", "mlb-chw").body();
        patch(f, "{\"alerts\":{\"END_OF_INNING\":{\"enabled\":true}}}");
        Set<String> in = memberOf();
        assertTrue(in.contains("sub:team:mlb-chw:END_OF_INNING"));
        assertTrue(in.stream().noneMatch(k -> k.startsWith("sub:team:mlb-chw:END_OF_INNING:")));
    }

    @Test
    void switchingFromChosenToEveryMovesSets() {
        Follow f = follows.create(bob, "team", "mlb-chw").body();
        patch(f, "{\"alerts\":{\"END_OF_INNING\":{\"enabled\":true,\"options\":{\"innings\":[3]}}}}");
        patch(f, "{\"alerts\":{\"END_OF_INNING\":{\"enabled\":true,\"options\":{\"innings\":[]}}}}");
        Set<String> in = memberOf();
        assertTrue(in.contains("sub:team:mlb-chw:END_OF_INNING"));
        assertFalse(in.contains("sub:team:mlb-chw:END_OF_INNING:3"));
    }

    @Test
    void disabledInningsAreInNoInningSet() {
        Follow f = follows.create(bob, "team", "mlb-chw").body();
        patch(f, "{\"alerts\":{\"END_OF_INNING\":{\"enabled\":false,\"options\":{\"innings\":[3]}}}}");
        assertTrue(memberOf().stream().noneMatch(k -> k.contains("END_OF_INNING")));
    }

    @Test
    void isBuiltNeedsCurrentVersion() {
        assertFalse(FollowSets.isBuilt(jedis));
        jedis.hset(util.RedisKeys.SUB_BUILT, "version", "1");
        assertFalse(FollowSets.isBuilt(jedis));
        FollowSets.markBuilt(jedis, "R");
        assertTrue(FollowSets.isBuilt(jedis));
        assertEquals("2", jedis.hget(util.RedisKeys.SUB_BUILT, "version"));
    }

    @Test
    void legacyStringMarkerIsNotBuilt() {
        jedis.set(util.RedisKeys.SUB_BUILT, "2026-10-08T12:00:00Z");
        assertFalse(FollowSets.isBuilt(jedis));
    }
}
