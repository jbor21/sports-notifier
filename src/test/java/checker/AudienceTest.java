package checker;

import alerts.Alert;
import alerts.Play;
import alerts.PlayBuilder;
import alerts.Side;
import catalog.AlertSetting;
import catalog.FollowKind;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import service.impl.AlertAudience;
import service.impl.Follow;
import service.impl.FollowSets;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The checker's audience, computed in Java from follows (Phase 4.1 spec, 6.1; 3.3 spec, 5.1). */
class AudienceTest {

    /** Chicago (home) and Las Vegas (away): team ids nfl-chi and nfl-lv. */
    private static final Play NFL = PlayBuilder.nfl("nfl-1").live().period(2).clock(5000).score(0, 6).build();
    private static final Alert TD = Alert.of("nfl-1:SCORE:away:1", "TOUCHDOWN", Alert.P_SCORE, Side.AWAY, "Touchdown");

    private int ids;

    private Follow follow(String user, FollowKind kind, String target, String league, Map<String, AlertSetting> alerts) {
        return new Follow("f" + (++ids), user, kind, target, league, alerts, null, Instant.EPOCH);
    }

    private static Map<String, AlertSetting> on(String... types) {
        Map<String, AlertSetting> m = new LinkedHashMap<>();
        for (String t : List.of("TOUCHDOWN", "FIELD_GOAL", "LEAD_CHANGE", "FINAL")) m.put(t, new AlertSetting(false, Map.of()));
        for (String t : types) m.put(t, new AlertSetting(true, Map.of()));
        return m;
    }

    @Test
    void teamFollowerWithSwitchOn() {
        List<Follow> f = List.of(
            follow("u1", FollowKind.TEAM, "nfl-lv", "NFL", on("TOUCHDOWN")),
            follow("u2", FollowKind.TEAM, "nfl-lv", "NFL", on("FINAL")));
        assertEquals(Set.of("u1"), Audience.of(NFL, TD, f));
    }

    @Test
    void bothTeamsSwitchesCombined() {
        List<Follow> f = List.of(
            follow("u1", FollowKind.TEAM, "nfl-chi", "NFL", on()),
            follow("u1", FollowKind.TEAM, "nfl-lv", "NFL", on("TOUCHDOWN")));
        assertEquals(Set.of("u1"), Audience.of(NFL, TD, f));
    }

    @Test
    void gameFollowWinsOverTeam() {
        List<Follow> f = List.of(
            follow("u1", FollowKind.TEAM, "nfl-lv", "NFL", on("TOUCHDOWN")),
            follow("u1", FollowKind.GAME, "nfl-1", "NFL", on()),
            follow("u2", FollowKind.TEAM, "nfl-lv", "NFL", on()),
            follow("u2", FollowKind.GAME, "nfl-1", "NFL", on("TOUCHDOWN")));
        assertEquals(Set.of("u2"), Audience.of(NFL, TD, f));
    }

    @Test
    void teamWinsOverLeague() {
        List<Follow> f = List.of(
            follow("u1", FollowKind.LEAGUE, "NFL", "NFL", on("TOUCHDOWN")),
            follow("u1", FollowKind.TEAM, "nfl-chi", "NFL", on()),
            follow("u2", FollowKind.LEAGUE, "NFL", "NFL", on("TOUCHDOWN")));
        assertEquals(Set.of("u2"), Audience.of(NFL, TD, f));
    }

    @Test
    void gameFollowAllOffHearsNothing() {
        List<Follow> f = List.of(
            follow("u1", FollowKind.TEAM, "nfl-lv", "NFL", on("TOUCHDOWN", "FIELD_GOAL", "LEAD_CHANGE", "FINAL")),
            follow("u1", FollowKind.GAME, "nfl-1", "NFL", on()));
        assertEquals(Set.of(), Audience.of(NFL, TD, f));
    }

    @Test
    void otherLeagueIgnored() {
        List<Follow> f = List.of(follow("u1", FollowKind.LEAGUE, "NBA", "NBA", on("TOUCHDOWN")));
        assertEquals(Set.of(), Audience.of(NFL, TD, f));
    }

    @Test
    void inningsOption() {
        Play mlb = PlayBuilder.mlb("mlb-1").live().period(7).build();         // Cubs at home: mlb-chc
        Alert end7 = new Alert("mlb-1:END_OF_INNING:7", "END_OF_INNING", List.of("END_OF_INNING", "END_OF_INNING:7"),
            Alert.P_PERIOD, null, "End of the 7th");
        Map<String, AlertSetting> seventh = Map.of("END_OF_INNING", new AlertSetting(true, Map.of("innings", List.of(7))));
        Map<String, AlertSetting> ninth = Map.of("END_OF_INNING", new AlertSetting(true, Map.of("innings", List.of(9))));
        Map<String, AlertSetting> every = Map.of("END_OF_INNING", new AlertSetting(true, Map.of()));
        List<Follow> f = List.of(
            follow("u7", FollowKind.TEAM, "mlb-chc", "MLB", seventh),
            follow("u9", FollowKind.TEAM, "mlb-chc", "MLB", ninth),
            follow("all", FollowKind.TEAM, "mlb-chc", "MLB", every));
        assertEquals(Set.of("u7", "all"), Audience.of(mlb, end7, f));
    }

    @Test
    void expiredFollowIgnored() {
        Follow gone = new Follow("fx", "u1", FollowKind.GAME, "nfl-1", "NFL", on("TOUCHDOWN"),
            Instant.ofEpochMilli(NFL.detectedAtMillis()).minusSeconds(1), Instant.EPOCH);
        assertEquals(Set.of(), Audience.of(NFL, TD, List.of(gone)));
    }

    @Test
    void matchesRedisSetsOnRandomFollows() {
        Random rnd = new Random(7);
        List<String> types = List.of("TOUCHDOWN", "FIELD_GOAL", "LEAD_CHANGE", "FINAL");
        String[][] targets = {{"LEAGUE", "NFL"}, {"LEAGUE", "NBA"}, {"TEAM", "nfl-chi"}, {"TEAM", "nfl-lv"},
            {"TEAM", "nfl-kc"}, {"GAME", "nfl-1"}, {"GAME", "nfl-2"}};
        Map<String, Follow> unique = new HashMap<>();             // one follow per user and target, as Postgres has
        for (int i = 0; i < 300; i++) {
            String user = "u" + rnd.nextInt(60);
            String[] t = targets[rnd.nextInt(targets.length)];
            Map<String, AlertSetting> alerts = new LinkedHashMap<>();
            for (String type : types) alerts.put(type, new AlertSetting(rnd.nextBoolean(), Map.of()));
            String league = t[1].startsWith("nba") || t[1].equals("NBA") ? "NBA" : "NFL";
            unique.put(user + " " + t[1], follow(user, FollowKind.valueOf(t[0]), t[1], league, alerts));
        }
        List<Follow> follows = new ArrayList<>(unique.values());

        redis.clients.jedis.JedisPool pool = new redis.clients.jedis.JedisPool(
            new redis.clients.jedis.JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
            FollowSets sets = new FollowSets(pool);
            follows.forEach(sets::writeState);
            AlertAudience audience = new AlertAudience();
            List<Play> plays = List.of(NFL,
                PlayBuilder.nfl("nfl-2").teams("KC", "CHI").live().period(3).build(),
                PlayBuilder.nfl("nfl-3").teams("DAL", "NYG").live().period(1).build());
            List<Alert> alerts = List.of(TD,
                Alert.of("x:FG", "FIELD_GOAL", Alert.P_SCORE, Side.HOME, "FG"),
                Alert.of("x:LEAD", "LEAD_CHANGE", Alert.P_LEAD, Side.HOME, "Lead"),
                Alert.of("x:FINAL", "FINAL", Alert.P_FLOW, null, "Final"),
                new Alert("x:OVERTURNED", "SCORE_OVERTURNED", List.of("TOUCHDOWN", "FIELD_GOAL"), Alert.P_SCORE, Side.HOME, "Corrected"));
            for (Play play : plays) {
                for (Alert alert : alerts) {
                    AlertAudience.Built b = audience.build(jedis, play, List.of(alert));
                    try {
                        assertEquals(jedis.smembers(b.keys().get(0)), Audience.of(play, alert, follows),
                            play.gameId() + " " + alert.type());
                    } finally {
                        audience.drop(jedis, b);
                    }
                }
            }
        } finally {
            pool.close();
        }
    }
}
