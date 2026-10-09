package checker;

import catalog.AlertCatalog;
import catalog.AlertType;
import catalog.FollowKind;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The seeded synthetic users and their follows (Phase 4.1 spec, 3.1). */
class FollowMixTest {

    private static final AlertCatalog CATALOG = AlertCatalog.load();
    private static final List<FollowMix.GameRef> GAMES = List.of(
        new FollowMix.GameRef("nfl-rpabc123-401872971", "NFL", "nfl-buf", "nfl-ne"),
        new FollowMix.GameRef("nhl-rpabc123-401891803", "NHL", "nhl-det", "nhl-nyr"),
        new FollowMix.GameRef("mlb-rpabc123-401908002", "MLB", "mlb-chc", "mlb-stl"));

    private static List<FollowMix.UserPlan> plan(long seed) {
        return FollowMix.plan(seed, 500, GAMES, CATALOG);
    }

    @Test
    void sameSeedSamePlan() {
        assertEquals(plan(1), plan(1));
        assertNotEquals(plan(1), plan(2));
    }

    @Test
    void sharesWithinTwoPercent() {
        Map<Integer, Long> rows = plan(1).stream().collect(Collectors.groupingBy(FollowMix.UserPlan::row, Collectors.counting()));
        Map<Integer, Double> want = Map.of(1, .40, 2, .15, 3, .15, 4, .10, 5, .10, 6, .10);
        want.forEach((row, share) -> assertEquals(share, rows.getOrDefault(row, 0L) / 500.0, 0.02, "row " + row));
    }

    @Test
    void rowsHaveTheirFollowShapes() {
        for (FollowMix.UserPlan u : plan(1)) {
            List<String> kinds = u.follows().stream().map(FollowMix.FollowPlan::kind).toList();
            List<String> want = switch (u.row()) {
                case 1 -> List.of("team");
                case 2 -> List.of("team", "team");
                case 3, 6 -> List.of("team", "game");
                case 4 -> List.of("league", "team");
                case 5 -> List.of("league");
                default -> fail("row " + u.row());
            };
            assertEquals(want, kinds, "user " + u.n());
        }
    }

    @Test
    void twoTabsAboutTenPercent() {
        long two = plan(1).stream().filter(FollowMix.UserPlan::twoTabs).count();
        assertEquals(0.10, two / 500.0, 0.02);
    }

    @Test
    void onlyTargetsFromTheSet() {
        Set<String> teams = Set.of("nfl-buf", "nfl-ne", "nhl-det", "nhl-nyr", "mlb-chc", "mlb-stl");
        Set<String> games = GAMES.stream().map(FollowMix.GameRef::gameId).collect(Collectors.toSet());
        for (FollowMix.UserPlan u : plan(1)) {
            for (FollowMix.FollowPlan f : u.follows()) {
                Set<String> allowed = switch (f.kind()) {
                    case "team" -> teams;
                    case "game" -> games;
                    default -> Set.of("NFL", "NHL", "MLB");
                };
                assertTrue(allowed.contains(f.targetId()), f.toString());
            }
        }
    }

    @Test
    void onlyAvailableTypesSwitched() {
        for (FollowMix.UserPlan u : plan(1)) {
            for (FollowMix.FollowPlan f : u.follows()) {
                if (f.alerts() == null) continue;
                FollowKind kind = FollowKind.valueOf(f.kind().toUpperCase());
                Set<String> ok = CATALOG.forLeague(f.league()).stream()
                    .filter(t -> "available".equals(t.availability()) && t.appliesTo().contains(kind))
                    .map(AlertType::type).collect(Collectors.toSet());
                assertTrue(ok.containsAll(f.alerts().keySet()), f.toString());
            }
        }
    }

    @Test
    void allOffRowHasEveryAvailableTypeOff() {
        FollowMix.UserPlan u = plan(1).stream().filter(p -> p.row() == 6).findFirst().orElseThrow();
        FollowMix.FollowPlan game = u.follows().get(1);
        Set<String> every = CATALOG.forLeague(game.league()).stream()
            .filter(t -> "available".equals(t.availability()) && t.appliesTo().contains(FollowKind.GAME))
            .map(AlertType::type).collect(Collectors.toSet());
        assertEquals(every, game.alerts().keySet());
        for (String t : every) assertFalse(game.alerts().getAsJsonObject(t).get("enabled").getAsBoolean(), t);
    }

    @Test
    void inningsOneToThree() {
        int seen = 0;
        for (FollowMix.UserPlan u : plan(1)) {
            for (FollowMix.FollowPlan f : u.follows()) {
                if (f.alerts() == null || !f.alerts().has("END_OF_INNING")) continue;
                JsonObject s = f.alerts().getAsJsonObject("END_OF_INNING");
                if (!s.get("enabled").getAsBoolean()) continue;
                var innings = s.getAsJsonObject("options").getAsJsonArray("innings");
                assertTrue(innings.size() >= 1 && innings.size() <= 3, f.toString());
                innings.forEach(i -> assertTrue(i.getAsInt() >= 1 && i.getAsInt() <= 9));
                seen++;
            }
        }
        assertTrue(seen > 0, "some MLB follow turns end of inning on");
    }
}
