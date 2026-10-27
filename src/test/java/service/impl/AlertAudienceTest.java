package service.impl;

import alerts.Alert;
import alerts.Play;
import alerts.PlayBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The most specific follow wins (Phase 3.3 spec, 5.1). Game nfl-1: Bears (nfl-chi) home, Raiders (nfl-lv) away. */
class AlertAudienceTest {

    private static final Play NFL = PlayBuilder.nfl("nfl-1").period(2).clock(5000).score(6, 0).build();
    private static final Alert TD = Alert.of("nfl-1:SCORE:home:1", "TOUCHDOWN", Alert.P_SCORE, null, "td");

    private Jedis jedis;
    private final AlertAudience audience = new AlertAudience();

    @BeforeEach
    void setUp() {
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
    }

    @AfterEach
    void tearDown() {
        jedis.close();
    }

    /** Follows {@code target} (e.g. {@code sub:team:nfl-chi}) with these switches on. */
    private void follow(String user, String target, String... switches) {
        jedis.sadd(target + ":ANY", user);
        for (String s : switches) jedis.sadd(target + ":" + s, user);
    }

    private Set<String> audienceOf(Play play, Alert alert) {
        AlertAudience.Built b = audience.build(jedis, play, List.of(alert));
        try {
            return jedis.smembers(b.keys().get(0));
        } finally {
            audience.drop(jedis, b);
        }
    }

    private static Play play(String gameId) {
        return PlayBuilder.nfl(gameId).period(2).clock(5000).score(6, 0).build();
    }

    @Test
    void replayReachesOnlySyntheticFollowers() {
        follow("ck", "sub:team:nfl-chi", "TOUCHDOWN");
        follow("real", "sub:team:nfl-chi", "TOUCHDOWN");
        jedis.sadd(util.RedisKeys.SYNTHETIC_USERS, "ck");
        assertEquals(Set.of("ck"), audienceOf(play("nfl-rpx-1"), TD));
    }

    @Test
    void replayWithoutSyntheticSetReachesNobody() {
        follow("ck", "sub:team:nfl-chi", "TOUCHDOWN");
        follow("real", "sub:team:nfl-chi", "TOUCHDOWN");
        assertEquals(Set.of(), audienceOf(play("nfl-rpx-1"), TD));
    }

    @Test
    void liveAndSimulatedGamesIgnoreTheSet() {
        follow("ck", "sub:team:nfl-chi", "TOUCHDOWN");
        follow("real", "sub:team:nfl-chi", "TOUCHDOWN");
        jedis.sadd(util.RedisKeys.SYNTHETIC_USERS, "ck");
        assertEquals(Set.of("ck", "real"), audienceOf(play("nfl-1"), TD));
        assertEquals(Set.of("ck", "real"), audienceOf(play("nfl-sim9-1"), TD));
    }

    @Test
    void gameFollowWithAlertOffSilencesTeamFollow() {
        follow("u1", "sub:team:nfl-chi", "TOUCHDOWN");
        follow("u1", "sub:game:nfl-1");                       // follows the game with touchdowns off
        follow("u2", "sub:team:nfl-chi", "TOUCHDOWN");
        assertEquals(Set.of("u2"), audienceOf(NFL, TD));
    }

    @Test
    void gameFollowWithAlertOnReaches() {
        follow("u1", "sub:game:nfl-1", "TOUCHDOWN");
        assertEquals(Set.of("u1"), audienceOf(NFL, TD));
    }

    @Test
    void bothTeamsUnionTheirSwitches() {
        follow("u1", "sub:team:nfl-chi");                     // touchdowns off for the Bears
        follow("u1", "sub:team:nfl-lv", "TOUCHDOWN");         // on for the Raiders
        assertEquals(Set.of("u1"), audienceOf(NFL, TD));
    }

    @Test
    void leagueSkippedWhenUserFollowsGame() {
        follow("u1", "sub:league:NFL", "TOUCHDOWN");
        follow("u1", "sub:game:nfl-1");
        assertEquals(Set.of(), audienceOf(NFL, TD));
    }

    @Test
    void leagueSkippedWhenUserFollowsEitherTeam() {
        follow("u1", "sub:league:NFL", "TOUCHDOWN");
        follow("u1", "sub:team:nfl-lv");
        follow("u2", "sub:league:NFL", "TOUCHDOWN");
        assertEquals(Set.of("u2"), audienceOf(NFL, TD));
    }

    @Test
    void reachedOnceThroughSeveralFollows() {
        follow("u1", "sub:game:nfl-1", "TOUCHDOWN");
        follow("u1", "sub:team:nfl-chi", "TOUCHDOWN");
        follow("u1", "sub:team:nfl-lv", "TOUCHDOWN");
        follow("u1", "sub:league:NFL", "TOUCHDOWN");
        AlertAudience.Built b = audience.build(jedis, NFL, List.of(TD));
        assertEquals(1, jedis.scard(b.keys().get(0)));
        audience.drop(jedis, b);
    }

    @Test
    void chosenInningReaches() {
        Play mlb = PlayBuilder.mlb("mlb-1").period(5).half("TOP").build();
        follow("u1", "sub:team:mlb-chc", "END_OF_INNING:4");
        follow("u2", "sub:team:mlb-chc", "END_OF_INNING");
        Alert fourth = new Alert("mlb-1:END_OF_INNING:4", "END_OF_INNING", List.of("END_OF_INNING", "END_OF_INNING:4"), Alert.P_PERIOD, null, "e");
        Alert fifth = new Alert("mlb-1:END_OF_INNING:5", "END_OF_INNING", List.of("END_OF_INNING", "END_OF_INNING:5"), Alert.P_PERIOD, null, "e");
        assertEquals(Set.of("u1", "u2"), audienceOf(mlb, fourth));
        assertEquals(Set.of("u2"), audienceOf(mlb, fifth));
    }

    @Test
    void genericScoreReachesAnyOfThreeSwitches() {
        follow("u1", "sub:team:nfl-chi", "TOUCHDOWN");
        follow("u2", "sub:team:nfl-chi", "FIELD_GOAL");
        follow("u3", "sub:team:nfl-lv", "SAFETY");
        follow("u4", "sub:team:nfl-lv", "GAME_START");
        Alert generic = new Alert("nfl-1:SCORE:home:1", "SCORE", List.of("TOUCHDOWN", "FIELD_GOAL", "SAFETY"), Alert.P_SCORE, null, "g");
        assertEquals(Set.of("u1", "u2", "u3"), audienceOf(NFL, generic));
    }

    @Test
    void unknownTeamIsSkipped() {
        Play noHome = PlayBuilder.nfl("nfl-1").teams(null, "LV").build();
        follow("u1", "sub:league:NFL", "TOUCHDOWN");
        follow("u2", "sub:team:nfl-lv", "TOUCHDOWN");
        assertEquals(Set.of("u1", "u2"), audienceOf(noHome, TD));
    }

    @Test
    void oneKeyPerAlertInOrder() {
        follow("u1", "sub:team:nfl-chi", "TOUCHDOWN");
        follow("u2", "sub:team:nfl-chi", "LEAD_CHANGE");
        Alert lead = Alert.of("nfl-1:LEAD_CHANGE:1", "LEAD_CHANGE", Alert.P_LEAD, null, "l");
        AlertAudience.Built b = audience.build(jedis, NFL, List.of(TD, lead));
        assertEquals(2, b.keys().size());
        assertEquals(Set.of("u1"), jedis.smembers(b.keys().get(0)));
        assertEquals(Set.of("u2"), jedis.smembers(b.keys().get(1)));
        audience.drop(jedis, b);
    }

    @Test
    void tempKeysExpireAndDropDeletesThem() {
        follow("u1", "sub:team:nfl-chi", "TOUCHDOWN");
        follow("u2", "sub:league:NFL", "TOUCHDOWN");
        AlertAudience.Built b = audience.build(jedis, NFL, List.of(TD));
        for (String k : b.keys()) {
            assertTrue(k.startsWith("fanout:nfl-1:v1:"), k);
            long ttl = jedis.ttl(k);
            assertTrue(ttl > 0 && ttl <= 60, k + " ttl " + ttl);
        }
        for (String k : b.temps()) assertTrue(jedis.ttl(k) <= 60, k);
        audience.drop(jedis, b);
        assertEquals(Set.of(), jedis.keys("fanout:*"));
    }
}
