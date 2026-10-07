package gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.http.Context;
import feed.EspnClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the {@code GET /api/upcoming} endpoint.
 *
 * Fetches the ESPN public scoreboard (through {@link EspnClient}) for NBA, NFL, MLB, and NHL,
 * filters to only scheduled (not yet started) games, and returns a normalised JSON
 * array. The proxy exists to avoid browser CORS restrictions when the UI calls ESPN
 * directly.
 */
public final class EspnProxyHandler {

    private static final String[] LEAGUES = {"NBA", "NFL", "MLB", "NHL"};

    private final EspnClient espn;

    /** Shares the poller's ESPN host and User-Agent settings, so the two can't drift apart. */
    public EspnProxyHandler(EspnClient espn) {
        this.espn = espn;
    }

    /**
     * Fetches upcoming games from ESPN for all four leagues and returns them as a
     * flat JSON array. Games that have already started or finished are excluded.
     * Errors for individual leagues are silently swallowed so one bad league
     * response does not fail the entire request.
     *
     * @param ctx Javalin request context; response is written as JSON.
     */
    public void handleUpcoming(Context ctx) {
        List<Map<String, Object>> upcoming = new ArrayList<>();
        for (String league : LEAGUES) {
            try {
                String body = espn.scoreboard(league);
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                JsonArray events = root.getAsJsonArray("events");
                if (events == null) continue;
                for (JsonElement elem : events) {
                    Map<String, Object> game = parseScheduledGame(elem.getAsJsonObject(), league);
                    if (game != null) upcoming.add(game);
                }
            } catch (Exception ignored) {}
        }
        ctx.json(upcoming);
    }

    private static Map<String, Object> parseScheduledGame(JsonObject event, String league) {
        JsonArray comps = event.getAsJsonArray("competitions");
        if (comps == null || comps.isEmpty()) return null;
        JsonObject comp = comps.get(0).getAsJsonObject();
        JsonObject stype = comp.getAsJsonObject("status").getAsJsonObject("type");
        if (!"STATUS_SCHEDULED".equals(stype.get("name").getAsString())) return null;

        JsonArray competitors = comp.getAsJsonArray("competitors");
        if (competitors == null) return null;

        String home = null, away = null;
        String homeLogo = null, awayLogo = null;
        String homeFullName = null, awayFullName = null;

        for (JsonElement c : competitors) {
            JsonObject competitor = c.getAsJsonObject();
            String side = competitor.get("homeAway").getAsString();
            JsonObject team = competitor.getAsJsonObject("team");
            String abbr     = team.get("abbreviation").getAsString();
            String logo     = team.has("logo") && !team.get("logo").isJsonNull()
                ? team.get("logo").getAsString() : null;
            String fullName = team.has("displayName") && !team.get("displayName").isJsonNull()
                ? team.get("displayName").getAsString() : null;
            if ("home".equals(side)) { home = abbr; homeLogo = logo; homeFullName = fullName; }
            else                     { away = abbr; awayLogo = logo; awayFullName = fullName; }
        }
        if (home == null || away == null) return null;

        String date = event.has("date") && !event.get("date").isJsonNull()
            ? event.get("date").getAsString() : null;

        Map<String, Object> game = new HashMap<>();
        game.put("gameId",       "espn-" + event.get("id").getAsString());
        game.put("homeTeam",     home);
        game.put("awayTeam",     away);
        game.put("homeLogo",     homeLogo);
        game.put("awayLogo",     awayLogo);
        game.put("homeFullName", homeFullName);
        game.put("awayFullName", awayFullName);
        game.put("league",       league);
        game.put("startTime",    date);
        game.put("status",       "SCHEDULED");
        return game;
    }
}
