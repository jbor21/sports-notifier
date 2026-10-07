package service.impl;

import model.GameEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Who hears about a play: Phase 2's set operations, moved out of the course worker for Stage 1. */
class AudienceTest {

    private JedisPool pool;
    private Jedis jedis;
    private Audience audience;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        audience = new Audience(pool);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    private void follow(String userId, String setKey, String... types) {
        jedis.sadd(setKey, userId);
        for (String type : types) jedis.sadd("sub:notif:" + type, userId);
    }

    private static GameEvent lalBos(String type, String team) {
        return new GameEvent("game-LAL-BOS", "e1", type, team, 1000L, "LAL scores! LAL 2 - 0 BOS",
            "LAL", "BOS", 2, 0, "IN_PROGRESS", "NBA", 10).withVersion(3);
    }

    /** Every user id the audience yields, in a list, so a duplicate would show. */
    private List<String> usersFor(GameEvent play) {
        List<String> all = new ArrayList<>();
        audience.forEachPage(play, all::addAll);
        return all;
    }

    @Test
    void teamGameAndLeagueFollowersAreIncluded() {
        follow("team-fan", "sub:team:LAL", "SCORE_UPDATE");
        follow("game-fan", "sub:game:game-LAL-BOS", "SCORE_UPDATE");
        follow("league-fan", "sub:league:NBA", "SCORE_UPDATE");
        assertEquals(Set.of("team-fan", "game-fan", "league-fan"), new HashSet<>(usersFor(lalBos("SCORE_UPDATE", "LAL"))));
    }

    @Test
    void awayFollowerGetsHomeTeamEvents() {
        follow("bos-fan", "sub:team:BOS", "SCORE_UPDATE", "GAME_START");
        assertEquals(List.of("bos-fan"), usersFor(lalBos("SCORE_UPDATE", "LAL")));
        assertEquals(List.of("bos-fan"), usersFor(lalBos("GAME_START", "LAL")));
    }

    @Test
    void followerOfBothTeamsAppearsOnce() {
        follow("both", "sub:team:LAL", "SCORE_UPDATE");
        follow("both", "sub:team:BOS", "SCORE_UPDATE");
        assertEquals(List.of("both"), usersFor(lalBos("SCORE_UPDATE", "LAL")));
    }

    @Test
    void followerWithoutTheTypeIsLeftOut() {
        follow("bos-fan", "sub:team:BOS", "GAME_START");
        assertTrue(usersFor(lalBos("SCORE_UPDATE", "LAL")).isEmpty());
    }

    @Test
    void unrelatedTeamFollowerIsLeftOut() {
        follow("mia-fan", "sub:team:MIA", "SCORE_UPDATE");
        assertTrue(usersFor(lalBos("SCORE_UPDATE", "LAL")).isEmpty());
    }

    @Test
    void twoThousandFollowersComeBackOnceEach() {
        for (int i = 0; i < 2000; i++) follow("u" + i, "sub:team:BOS", "SCORE_UPDATE");
        List<String> users = usersFor(lalBos("SCORE_UPDATE", "LAL"));
        assertEquals(2000, users.size());
        assertEquals(2000, new HashSet<>(users).size());
    }

    @Test
    void redisErrorWhileBuildingFailsInsteadOfWideningTheAudience() {
        // If the union succeeded and the type filter failed silently, every team follower would get
        // the play whether or not they asked for that event type.
        follow("bos-fan", "sub:team:BOS", "GAME_START");
        jedis.del("sub:notif:SCORE_UPDATE");
        jedis.set("sub:notif:SCORE_UPDATE", "not a set");          // SINTERSTORE fails with WRONGTYPE
        List<String> paged = new ArrayList<>();
        assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
            () -> audience.forEachPage(lalBos("SCORE_UPDATE", "LAL"), paged::addAll));
        assertTrue(paged.isEmpty());
    }

    @Test
    void temporarySetIsNamedByVersionAndRemoved() {
        follow("bos-fan", "sub:team:BOS", "SCORE_UPDATE");
        List<String> seenKeys = new ArrayList<>();
        audience.forEachPage(lalBos("SCORE_UPDATE", "LAL"), page -> seenKeys.addAll(jedis.keys("fanout:*")));
        assertEquals(List.of("fanout:game-LAL-BOS:v3"), seenKeys);
        assertTrue(jedis.keys("fanout:*").isEmpty());
    }
}
