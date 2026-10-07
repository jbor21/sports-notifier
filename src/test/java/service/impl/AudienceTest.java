package service.impl;

import catalog.FollowKind;
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

/** Who hears about a play: the union of the play's targets × its switches, built inside Redis (spec section 6). */
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

    private void follow(String userId, FollowKind kind, String target, String... switches) {
        for (String s : switches) jedis.sadd(FollowSets.key(kind, target, s), userId);
    }

    private static GameEvent play(String league, String gameId, String home, String away) {
        return new GameEvent(gameId, "e1", "SCORE_UPDATE", home, 1000L, "score",
            home, away, 7, 3, "IN_PROGRESS", league, 10).withVersion(3);
    }

    private List<String> usersFor(GameEvent play, String... switches) {
        List<String> all = new ArrayList<>();
        audience.forEachPage(play, List.of(switches), all::addAll);
        return all;
    }

    @Test
    void fieldGoalOnlyNflFollowerGetsScore() {
        follow("fg-only", FollowKind.TEAM, "nfl-chi", "FIELD_GOAL");
        assertEquals(List.of("fg-only"),
                usersFor(play("NFL", "nfl-1", "CHI", "LV"), "TOUCHDOWN", "TRY_RESULT", "FIELD_GOAL", "SAFETY"));
    }

    @Test
    void awayTeamGameAndLeagueTargets() {
        follow("away-fan", FollowKind.TEAM, "nhl-bos", "GOAL");
        follow("game-fan", FollowKind.GAME, "nhl-1", "GOAL");
        follow("league-fan", FollowKind.LEAGUE, "NHL", "GOAL");
        follow("other-switch", FollowKind.TEAM, "nhl-bos", "FINAL");
        assertEquals(Set.of("away-fan", "game-fan", "league-fan"),
                new HashSet<>(usersFor(play("NHL", "nhl-1", "TOR", "BOS"), "GOAL")));
    }

    @Test
    void bothTeamsFollowerOnce() {
        follow("both", FollowKind.TEAM, "nhl-tor", "GOAL");
        follow("both", FollowKind.TEAM, "nhl-bos", "GOAL");
        assertEquals(List.of("both"), usersFor(play("NHL", "nhl-1", "TOR", "BOS"), "GOAL"));
    }

    @Test
    void nbaBosFollowerGetsNothingFromMlbBos() {
        follow("celtics-fan", FollowKind.TEAM, "nba-bos", "GAME_START", "FINAL");
        assertTrue(usersFor(play("MLB", "mlb-1", "BOS", "NYY"), "GAME_START").isEmpty());
    }

    @Test
    void noSwitchesNoAudience() {
        follow("fan", FollowKind.TEAM, "nba-lal", "GAME_START");
        assertTrue(usersFor(play("NBA", "nba-1", "LAL", "BOS")).isEmpty());
    }

    @Test
    void twoThousandFollowersComeBackOnceEach() {
        for (int i = 0; i < 2000; i++) follow("u" + i, FollowKind.TEAM, "mlb-chw", "RUN_SCORED");
        List<String> all = usersFor(play("MLB", "mlb-1", "CHW", "CLE"), "RUN_SCORED");
        assertEquals(2000, all.size());
        assertEquals(2000, new HashSet<>(all).size());
    }
}
