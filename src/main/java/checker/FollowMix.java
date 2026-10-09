package checker;

import catalog.AlertCatalog;
import catalog.AlertType;
import catalog.FollowKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

/**
 * The synthetic users' follows (Phase 4.1 spec, 3.1), seeded so a run repeats. The six rows cover every
 * branch of the audience formula; a user's row is {@code n % 20}, so the shares are exact per 20 users.
 */
public final class FollowMix {

    public record GameRef(String gameId, String league, String homeTeamId, String awayTeamId) {}

    /** {@code alerts} is the PATCH body's {@code alerts}, or null to keep the catalog defaults. */
    public record FollowPlan(String kind, String targetId, String league, JsonObject alerts) {}

    /** @param row 1–6, the spec's table rows, in its order */
    public record UserPlan(int n, int row, boolean twoTabs, List<FollowPlan> follows) {}

    private static final double SWITCH_ON = 0.7;

    private FollowMix() {}

    public static List<UserPlan> plan(long seed, int users, List<GameRef> games, AlertCatalog catalog) {
        Random rnd = new Random(seed);
        List<UserPlan> out = new ArrayList<>();
        for (int n = 0; n < users; n++) {
            int b = n % 20;
            int row = b < 8 ? 1 : b < 11 ? 2 : b < 14 ? 3 : b < 16 ? 4 : b < 18 ? 5 : 6;
            GameRef g = games.get(rnd.nextInt(games.size()));
            String team = rnd.nextBoolean() ? g.homeTeamId() : g.awayTeamId();
            List<FollowPlan> follows = switch (row) {
                case 1 -> List.of(random(rnd, catalog, "team", team, g.league()));
                case 2 -> List.of(random(rnd, catalog, "team", g.homeTeamId(), g.league()),
                                  random(rnd, catalog, "team", g.awayTeamId(), g.league()));
                case 3 -> List.of(random(rnd, catalog, "team", team, g.league()),
                                  random(rnd, catalog, "game", g.gameId(), g.league()));
                case 4 -> List.of(random(rnd, catalog, "league", g.league(), g.league()),
                                  random(rnd, catalog, "team", team, g.league()));
                case 5 -> List.of(random(rnd, catalog, "league", g.league(), g.league()));
                default -> List.of(random(rnd, catalog, "team", team, g.league()),
                                   new FollowPlan("game", g.gameId(), g.league(), allOff(catalog, g.league())));
            };
            out.add(new UserPlan(n, row, n % 10 == 3, follows));
        }
        return out;
    }

    /** Every available switch this follow kind offers, each on with probability 0.7; innings 1–3 of 1–9. */
    private static FollowPlan random(Random rnd, AlertCatalog catalog, String kind, String target, String league) {
        JsonObject alerts = new JsonObject();
        for (AlertType t : available(catalog, league, kind)) {
            JsonObject s = new JsonObject();
            boolean on = rnd.nextDouble() < SWITCH_ON;
            s.addProperty("enabled", on);
            if (on && "END_OF_INNING".equals(t.type())) {
                TreeSet<Integer> innings = new TreeSet<>();
                int count = 1 + rnd.nextInt(3);
                while (innings.size() < count) innings.add(1 + rnd.nextInt(9));
                JsonArray a = new JsonArray();
                innings.forEach(a::add);
                JsonObject options = new JsonObject();
                options.add("innings", a);
                s.add("options", options);
            }
            alerts.add(t.type(), s);
        }
        return new FollowPlan(kind, target, league, alerts);
    }

    private static JsonObject allOff(AlertCatalog catalog, String league) {
        JsonObject alerts = new JsonObject();
        for (AlertType t : available(catalog, league, "game")) {
            JsonObject s = new JsonObject();
            s.addProperty("enabled", false);
            alerts.add(t.type(), s);
        }
        return alerts;
    }

    private static List<AlertType> available(AlertCatalog catalog, String league, String kind) {
        FollowKind k = FollowKind.valueOf(kind.toUpperCase());
        return catalog.forLeague(league).stream()
                .filter(t -> "available".equals(t.availability()) && t.appliesTo().contains(k)).toList();
    }
}
