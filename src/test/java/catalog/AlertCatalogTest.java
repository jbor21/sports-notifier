package catalog;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The catalog file against DESIGN.md decision 15's tables, row by row. */
class AlertCatalogTest {

    private static final AlertCatalog CATALOG = AlertCatalog.load();

    /**
     * Decision 15, transcribed separately from alerts.json: "league type team league game availability",
     * with "-" where the alert does not apply to that follow kind. FINAL starts on for league follows
     * until the daily summary ships (docs/api/openapi.yaml). Rows marked "?" in the tables are coming_soon.
     */
    private static final List<String> EVERY_SPORT = List.of(
            "STARTING_SOON off off off available",
            "GAME_START on off on available",
            "DELAYED on off on available",
            "LEAD_CHANGE off off off available",
            "CLOSE_LATE on on on available",
            "COMEBACK on on on available",
            "OVERTIME on on on available",
            "FINAL on on on available",
            "DAILY_SUMMARY - on - coming_soon");

    private static final Map<String, List<String>> PER_SPORT = Map.of(
            "NFL", List.of(
                    "TOUCHDOWN on off on available",
                    "TRY_RESULT on off on available",
                    "FIELD_GOAL on off on available",
                    "SAFETY on off on available",
                    "END_OF_PERIOD on off on available",
                    "HALFTIME on off on available",
                    "TWO_MINUTE_WARNING off off off available",
                    "RED_ZONE off off off coming_soon",
                    "CHANCE_TO_WIN on on on coming_soon"),
            "NBA", List.of(
                    "END_OF_PERIOD on off on available",
                    "HALFTIME on off on available",
                    "BIG_RUN off off off available",
                    "BIG_LEAD off off off available",
                    "TIE_LATE off off off available"),
            "MLB", List.of(
                    "RUN_SCORED on off on available",
                    "END_OF_INNING off off off available",
                    "BIG_INNING off off off available",
                    "TIE_LATE off off off available",
                    "BASES_LOADED off off off coming_soon",
                    "NO_HITTER on on on coming_soon",
                    "WALK_OFF on on on available"),
            "NHL", List.of(
                    "GOAL on off on available",
                    "END_OF_PERIOD on off on available",
                    "TIE_LATE off off off available",
                    "BIG_LEAD off off off available",
                    "SHUTOUT_WATCH off off off available",
                    "SHOOTOUT on on on available"));

    @Test
    void matchesDecision15() {
        for (String league : List.of("NFL", "NBA", "MLB", "NHL")) {
            Map<String, String> expected = new HashMap<>();
            for (String row : concat(EVERY_SPORT, PER_SPORT.get(league))) {
                String[] f = row.split(" ");
                expected.put(f[0], row);
            }
            Map<String, String> actual = new HashMap<>();
            for (AlertType t : CATALOG.forLeague(league)) {
                actual.put(t.type(), t.type() + " " + cell(t, FollowKind.TEAM) + " " + cell(t, FollowKind.LEAGUE)
                        + " " + cell(t, FollowKind.GAME) + " " + t.availability());
            }
            assertEquals(expected, actual, league);
        }
    }

    @Test
    void scoringTypesPerLeague() {
        assertEquals(List.of("TOUCHDOWN", "TRY_RESULT", "FIELD_GOAL", "SAFETY"), CATALOG.scoringTypes("NFL"));
        assertEquals(List.of("RUN_SCORED"), CATALOG.scoringTypes("MLB"));
        assertEquals(List.of("GOAL"), CATALOG.scoringTypes("NHL"));
        assertEquals(List.of(), CATALOG.scoringTypes("NBA"));
    }

    @Test
    void defaultsForAFollowKind() {
        Map<String, AlertSetting> team = CATALOG.defaults("MLB", FollowKind.TEAM);
        assertTrue(team.get("GAME_START").enabled());
        assertTrue(team.get("RUN_SCORED").enabled());
        assertFalse(team.get("LEAD_CHANGE").enabled());
        assertFalse(team.containsKey("DAILY_SUMMARY"), "not offered to team follows");
        assertTrue(CATALOG.defaults("MLB", FollowKind.LEAGUE).containsKey("DAILY_SUMMARY"));
    }

    @Test
    void problemCodes() {
        AlertSetting on = new AlertSetting(true, Map.of());
        assertEquals(Optional.of("unknown_type"), CATALOG.problem("NBA", FollowKind.TEAM, "TOUCHDOWN", on));
        assertEquals(Optional.of("unknown_type"), CATALOG.problem("NFL", FollowKind.TEAM, "NOPE", on));
        assertEquals(Optional.of("not_for_this_follow"), CATALOG.problem("NFL", FollowKind.TEAM, "DAILY_SUMMARY", on));
        assertEquals(Optional.empty(), CATALOG.problem("NFL", FollowKind.LEAGUE, "DAILY_SUMMARY", on));
        assertEquals(Optional.of("bad_option"), innings(List.of(0)));
        assertEquals(Optional.of("bad_option"), innings(List.of(10)));
        assertEquals(Optional.of("bad_option"), innings(List.of("9")));
        assertEquals(Optional.of("bad_option"), innings("9"));
        assertEquals(Optional.empty(), innings(List.of(3, 6, 9)));
        assertEquals(Optional.empty(), innings(List.of()));
        assertEquals(Optional.of("bad_option"), CATALOG.problem("MLB", FollowKind.TEAM, "END_OF_INNING",
                new AlertSetting(true, Map.of("colors", List.of(1)))), "an option the alert doesn't have");
        assertEquals(Optional.of("bad_option"), CATALOG.problem("NFL", FollowKind.TEAM, "TOUCHDOWN",
                new AlertSetting(true, Map.of("innings", List.of(1)))), "options on an alert without any");
    }

    @Test
    void unknownLeagueHasNoAlerts() {
        assertEquals(List.of(), CATALOG.forLeague("XFL"));
        assertEquals(Set.of(), CATALOG.defaults("XFL", FollowKind.TEAM).keySet());
    }

    private static Optional<String> innings(Object value) {
        return CATALOG.problem("MLB", FollowKind.TEAM, "END_OF_INNING", new AlertSetting(true, Map.of("innings", value)));
    }

    private static String cell(AlertType t, FollowKind kind) {
        if (!t.appliesTo().contains(kind)) return "-";
        return Boolean.TRUE.equals(t.defaults().get(kind)) ? "on" : "off";
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    @Test
    void noAlertTypeIsCalledAny() {
        // sub:<kind>:<target>:ANY holds everyone who follows the target; a switch by that name would collide.
        for (String league : java.util.List.of("NFL", "NBA", "MLB", "NHL")) {
            assertTrue(AlertCatalog.load().forLeague(league).stream().noneMatch(t -> "ANY".equals(t.type())), league);
        }
    }
}
