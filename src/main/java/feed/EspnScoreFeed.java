package feed;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import model.GameSnapshot;
import model.LinePeriod;
import model.GameSnapshot.Half;
import model.GameSnapshot.Phase;
import model.GameSnapshot.Stage;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads ESPN's scoreboard into {@link GameSnapshot}s.
 *
 * What ESPN's JSON looked like in 60 hours of recordings, 2026-10-02 to 10-05 (site.web.api.espn.com,
 * 49 games across all four leagues):
 * - status.type.name: STATUS_SCHEDULED, STATUS_IN_PROGRESS, STATUS_HALFTIME, STATUS_END_PERIOD,
 *   STATUS_DELAYED, STATUS_FINAL seen live. STATUS_RAIN_DELAY, STATUS_POSTPONED, STATUS_SUSPENDED,
 *   and STATUS_CANCELED are ESPN's other names, not yet seen.
 * - A delay before the start arrives with state "in" and period 0.
 * - status.clock is seconds left in the period, status.period the quarter/period/inning.
 * - event.season.type is 1 preseason, 2 regular season, 3 postseason.
 * - MLB half-inning is in status.type.shortDetail ("Top 4th", "Mid 4th", "Bot 4th", "End 4th")
 *   and outs in competitions[0].situation.outs. At the third out ESPN shows "Top 1st" with 0 outs
 *   for one poll before "Mid 1st"; that snapshot is rejected as stale and the next one heals it.
 */
public final class EspnScoreFeed implements ScoreFeed {

    private static final Set<String> LOGGED_UNKNOWN = ConcurrentHashMap.newKeySet();

    private final EspnClient client;

    public EspnScoreFeed(EspnClient client) {
        this.client = client;
    }

    @Override
    public List<GameSnapshot> fetch(String league) throws FeedBlockedException, IOException {
        return parse(client.scoreboard(league), league);
    }

    /** One bad game is logged and skipped; it never costs the rest of the league. */
    public static List<GameSnapshot> parse(String json, String league) {
        List<GameSnapshot> games = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) return games;
        JsonArray events = arr(root.getAsJsonObject(), "events");
        if (events == null) return games;
        for (JsonElement e : events) {
            try {
                GameSnapshot g = parseGame(e.getAsJsonObject(), league);
                if (g != null) games.add(g);
            } catch (RuntimeException ex) {
                System.err.println("[EspnScoreFeed " + league + "] skipped a game: " + ex);
            }
        }
        return games;
    }

    /**
     * A {@code scoreboard?dates=} response as schedule entries: each game's snapshot plus its venue and
     * broadcast, which only the schedule keeps. Same per-game rules as {@link #parse}.
     */
    public static List<ScheduleEntry> parseSchedule(String league, String json) {
        List<ScheduleEntry> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) return out;
        JsonArray events = arr(root.getAsJsonObject(), "events");
        if (events == null) return out;
        for (JsonElement e : events) {
            try {
                JsonObject event = e.getAsJsonObject();
                GameSnapshot g = parseGame(event, league);
                if (g == null) continue;
                JsonObject comp = arr(event, "competitions").get(0).getAsJsonObject();
                out.add(new ScheduleEntry(g, str(obj(comp, "venue"), "fullName"), broadcast(comp)));
            } catch (RuntimeException ex) {
                System.err.println("[EspnScoreFeed " + league + "] skipped a scheduled game: " + ex);
            }
        }
        return out;
    }

    /** The first network name ESPN lists, e.g. {@code ESPN}; null if none. */
    private static String broadcast(JsonObject comp) {
        JsonArray broadcasts = arr(comp, "broadcasts");
        if (broadcasts == null || broadcasts.isEmpty()) return null;
        JsonArray names = arr(broadcasts.get(0).getAsJsonObject(), "names");
        return names == null || names.isEmpty() || names.get(0).isJsonNull() ? null : names.get(0).getAsString();
    }

    private static GameSnapshot parseGame(JsonObject event, String league) {
        String id = str(event, "id");
        JsonArray comps = arr(event, "competitions");
        if (id == null || comps == null || comps.isEmpty()) return null;
        JsonObject comp = comps.get(0).getAsJsonObject();
        JsonObject status = obj(comp, "status");
        JsonObject type = obj(status, "type");
        JsonArray competitors = arr(comp, "competitors");
        if (status == null || type == null || competitors == null) return null;

        String home = null, away = null;
        Integer homeScore = null, awayScore = null;
        List<Integer> homeLines = List.of(), awayLines = List.of();
        for (JsonElement c : competitors) {
            JsonObject competitor = c.getAsJsonObject();
            String abbr = str(obj(competitor, "team"), "abbreviation");
            Integer score = parseScore(str(competitor, "score"));
            List<Integer> lines = lineValues(competitor);
            if ("home".equals(str(competitor, "homeAway"))) { home = abbr; homeScore = score; homeLines = lines; }
            else                                            { away = abbr; awayScore = score; awayLines = lines; }
        }
        if (home == null || away == null) return null;

        int period = status.has("period") ? status.get("period").getAsInt() : 0;
        Mapped m = mapStatus(str(type, "name"), str(type, "state"),
            type.has("completed") && type.get("completed").getAsBoolean(), period, league);
        // Before the start a missing score is 0-0. During or after the game it is unknown: treating
        // it as 0 would record 0-0 and make the next good snapshot look like a score (a false alert).
        if (homeScore == null || awayScore == null) {
            if (m.phase != Phase.PRE) {
                System.err.println("[EspnScoreFeed " + league + "] " + GameIds.of(league, id) + ": score missing, skipped this poll");
                return null;
            }
            homeScore = homeScore == null ? 0 : homeScore;
            awayScore = awayScore == null ? 0 : awayScore;
        }
        // No clock is "unknown" (-1), not 0:00: a zero would make a 4th quarter look like its last second.
        int clockTenths = status.has("clock") && !status.get("clock").isJsonNull()
            ? (int) Math.round(status.get("clock").getAsDouble() * 10)
            : (m.phase == Phase.PRE ? 0 : -1);

        boolean mlb = "MLB".equals(league);
        Half half = mlb ? halfFrom(str(type, "shortDetail")) : Half.TOP;
        JsonObject situation = obj(comp, "situation");
        int outs = mlb && situation != null && situation.has("outs") ? situation.get("outs").getAsInt() : 0;
        String date = str(event, "date");

        return new GameSnapshot(GameIds.of(league, id), league, home, away, homeScore, awayScore,
            m.phase, period, half, mlb ? 0 : clockTenths, outs, m.stage, m.status,
            startMillis(date), seasonType(event), str(type, "shortDetail"), lineScore(homeLines, awayLines));
    }

    /** ESPN's per-period points for one side, in period order; empty when it sends none. */
    private static List<Integer> lineValues(JsonObject competitor) {
        JsonArray lines = arr(competitor, "linescores");
        if (lines == null) return List.of();
        List<Integer> out = new ArrayList<>();
        for (JsonElement l : lines) {
            JsonObject o = l.getAsJsonObject();
            out.add(o.has("value") && !o.get("value").isJsonNull() ? (int) Math.round(o.get("value").getAsDouble()) : 0);
        }
        return out;
    }

    /** Pairs the two sides by period; a side with fewer columns scored 0 in the rest. */
    private static List<LinePeriod> lineScore(List<Integer> home, List<Integer> away) {
        List<LinePeriod> out = new ArrayList<>();
        for (int i = 0; i < Math.max(home.size(), away.size()); i++) {
            out.add(new LinePeriod(i + 1, i < home.size() ? home.get(i) : 0, i < away.size() ? away.get(i) : 0));
        }
        return out;
    }

    private record Mapped(String status, Phase phase, Stage stage) {}

    private static Mapped mapStatus(String name, String state, boolean completed, int period, String league) {
        if ("STATUS_CANCELED".equals(name)) return new Mapped("CANCELED", Phase.FINAL, Stage.IN_PLAY);
        if (completed) return new Mapped("FINAL", Phase.FINAL, Stage.IN_PLAY);
        if (name != null) {
            switch (name) {
                case "STATUS_SCHEDULED":   return new Mapped("SCHEDULED", Phase.PRE, Stage.IN_PLAY);
                case "STATUS_IN_PROGRESS": return new Mapped("IN_PROGRESS", Phase.LIVE, Stage.IN_PLAY);
                case "STATUS_END_PERIOD":  return new Mapped("END_PERIOD", Phase.LIVE, Stage.END_PERIOD);
                case "STATUS_HALFTIME":    return new Mapped("HALFTIME", Phase.LIVE, Stage.HALFTIME);
                // A delay before the start also arrives with state "in", but at period 0.
                case "STATUS_DELAYED":
                case "STATUS_RAIN_DELAY":
                    return new Mapped("DELAYED", "in".equals(state) && period > 0 ? Phase.LIVE : Phase.PRE, Stage.DELAYED);
                // Postponed and suspended are "delayed at the current position", never final:
                // a suspended game that resumes days later must keep getting bigger seq values.
                case "STATUS_POSTPONED":   return new Mapped("POSTPONED", Phase.PRE, Stage.DELAYED);
                case "STATUS_SUSPENDED":   return new Mapped("SUSPENDED", Phase.LIVE, Stage.DELAYED);
                default:
                    if (LOGGED_UNKNOWN.add(name)) {
                        System.err.println("[EspnScoreFeed " + league + "] unknown status " + name + ", using state " + state);
                    }
            }
        }
        if ("pre".equals(state)) return new Mapped("SCHEDULED", Phase.PRE, Stage.IN_PLAY);
        if ("post".equals(state)) return new Mapped("SUSPENDED", Phase.LIVE, Stage.DELAYED);
        return new Mapped("IN_PROGRESS", Phase.LIVE, Stage.IN_PLAY);
    }

    /** 1 preseason, 2 regular season, 3 postseason; 2 when ESPN leaves it out. */
    private static int seasonType(JsonObject event) {
        JsonObject season = obj(event, "season");
        return season != null && season.has("type") && season.get("type").isJsonPrimitive() ? season.get("type").getAsInt() : 2;
    }

    /** ESPN writes "2026-10-03T17:00Z", without seconds, which Instant.parse rejects. 0 if unreadable. */
    private static long startMillis(String date) {
        if (date == null) return 0L;
        try {
            return OffsetDateTime.parse(date).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }

    private static Half halfFrom(String shortDetail) {
        if (shortDetail == null) return Half.TOP;
        String d = shortDetail.toLowerCase(Locale.ROOT);
        if (d.startsWith("mid")) return Half.MIDDLE;
        if (d.startsWith("bot")) return Half.BOTTOM;
        if (d.startsWith("end")) return Half.END;
        return Half.TOP;
    }

    /** null when missing or unreadable. */
    private static Integer parseScore(String s) {
        if (s == null) return null;
        try { return (int) Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : null;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
