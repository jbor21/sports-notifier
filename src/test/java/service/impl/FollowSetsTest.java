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

    private static Set<String> enabledKeys(Follow f) {
        Set<String> out = new TreeSet<>();
        f.alerts().forEach((type, s) -> { if (s.enabled()) out.add(FollowSets.key(f.kind(), f.targetId(), type)); });
        return out;
    }
}
