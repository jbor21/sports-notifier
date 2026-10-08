package games;

import alerts.GameLabels;
import alerts.Play;
import catalog.Teams;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import feed.TeamIds;
import model.GameEvent;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds the API's {@code GameSummary} and {@code GameDetail} (docs/api/openapi.yaml). The one builder
 * behind {@code /games}, {@code /games/{id}}, and the socket's {@code game_update}, so a card and a
 * live update can't disagree.
 *
 * Inputs are the {@code game:<id>} hash the accept script writes and the {@code sched:<id>} hash the
 * schedule writes, which use the same field names. The record wins for everything it has; the
 * schedule stands in for games with no record and adds venue and broadcast.
 */
public final class GameSummaries {

    private final Teams teams;

    public GameSummaries(Teams teams) {
        this.teams = teams;
    }

    public static String apiStatus(String status) {
        if (status == null) return "in_progress";
        return switch (status) {
            case "SCHEDULED" -> "scheduled";
            case "FINAL" -> "final";
            case "DELAYED" -> "delayed";
            case "POSTPONED" -> "postponed";
            case "CANCELED" -> "canceled";
            case "SUSPENDED" -> "suspended";
            default -> "in_progress";
        };
    }

    /** @param game the {@code game:} hash, or empty; @param sched the {@code sched:} hash, or empty. Not both empty. */
    public JsonObject summary(String gameId, Map<String, String> game, Map<String, String> sched) {
        Map<String, String> base = game.isEmpty() ? sched : game;
        Play p = playOf(gameId, base);
        JsonObject g = new JsonObject();
        g.addProperty("id", gameId);
        g.addProperty("leagueId", p.league());
        g.addProperty("status", apiStatus(p.status()));
        long start = longOf(game.get("startTime"));
        if (start <= 0) start = longOf(sched.get("startTime"));
        g.add("startTime", time(start));
        g.add("home", side(p.league(), p.homeTeam(), p.homeScore()));
        g.add("away", side(p.league(), p.awayTeam(), p.awayScore()));
        boolean started = !"PRE".equals(p.phase()) && p.period() > 0;
        g.add("period", started ? new com.google.gson.JsonPrimitive(p.period()) : JsonNull.INSTANCE);
        g.addProperty("periodLabel", GameLabels.periodLabel(p));
        g.addProperty("clock", GameLabels.clock(p));
        g.addProperty("version", game.isEmpty() ? 0 : longOf(game.get("version")));
        g.add("updatedAt", time(game.isEmpty() ? longOf(sched.get("fetchedAt")) : longOf(game.get("lastTimestamp"))));
        return g;
    }

    /** {@link #summary} plus the line score, venue, and broadcast. */
    public JsonObject detail(String gameId, Map<String, String> game, Map<String, String> sched) {
        JsonObject d = summary(gameId, game, sched);
        Map<String, String> base = game.isEmpty() ? sched : game;
        Play p = playOf(gameId, base);
        JsonArray lines = new JsonArray();
        String raw = base.get("lineScore");
        if (raw != null && !raw.isBlank()) {
            for (JsonElement el : JsonParser.parseString(raw).getAsJsonArray()) {
                JsonObject in = el.getAsJsonObject();
                int period = in.get("period").getAsInt();
                JsonObject col = new JsonObject();
                col.addProperty("period", period);
                col.addProperty("label", GameLabels.lineLabel(p.league(), period, p.seasonType()));
                col.addProperty("home", in.get("home").getAsInt());
                col.addProperty("away", in.get("away").getAsInt());
                lines.add(col);
            }
        }
        d.add("lineScore", lines);
        d.addProperty("venue", blankToNull(sched.get("venue")));
        d.addProperty("broadcast", blankToNull(sched.get("broadcast")));
        return d;
    }

    /** A live play, as the summary of the record the accept script wrote for it. */
    public JsonObject fromLive(GameEvent play, long version) {
        Map<String, String> m = new HashMap<>();
        m.put("league", play.getLeague());
        m.put("homeTeam", play.getHomeTeam());
        m.put("awayTeam", play.getAwayTeam());
        m.put("homeScore", String.valueOf(play.getHomeScore()));
        m.put("awayScore", String.valueOf(play.getAwayScore()));
        m.put("status", play.getGameStatus());
        m.put("phase", play.getPhase());
        m.put("period", String.valueOf(play.getPeriod()));
        m.put("clockTenths", String.valueOf(play.getClockTenths()));
        m.put("half", play.getHalf());
        m.put("outs", String.valueOf(play.getOuts()));
        m.put("stage", play.getStage());
        m.put("seasonType", String.valueOf(play.getSeasonType()));
        m.put("detail", play.getDetail());
        m.put("startTime", String.valueOf(play.getStartTime()));
        m.put("version", String.valueOf(version));
        m.put("lastTimestamp", String.valueOf(play.getTimestamp()));
        m.values().removeIf(v -> v == null);
        return summary(play.getGameId(), m, Map.of());
    }

    /** The hash as a play, so the labels read it the way the alerts do. */
    private static Play playOf(String gameId, Map<String, String> f) {
        GameEvent e = new GameEvent(gameId, null, null, null, longOf(f.get("lastTimestamp")), null,
            f.get("homeTeam"), f.get("awayTeam"), (int) longOf(f.get("homeScore")), (int) longOf(f.get("awayScore")),
            f.get("status"), f.get("league"), 0L)
            .withPosition(blankToNull(f.get("phase")), (int) longOf(f.get("period")), (int) longOf(f.get("clockTenths")),
                blankToNull(f.get("half")), (int) longOf(f.get("outs")), blankToNull(f.get("stage")),
                (int) longOf(f.get("seasonType")), blankToNull(f.get("detail")));
        return Play.of(e);
    }

    private JsonObject side(String league, String abbreviation, int score) {
        String id = TeamIds.of(league, abbreviation);
        JsonObject team = new JsonObject();
        team.addProperty("id", id);
        team.addProperty("abbreviation", abbreviation);
        Teams.Team known = teams.find(id).orElse(null);
        team.addProperty("displayName", known == null ? abbreviation : known.displayName());
        if (known != null && known.logoUrl() != null) team.addProperty("logoUrl", known.logoUrl());
        if (known != null && known.primaryColor() != null) team.addProperty("primaryColor", known.primaryColor());
        JsonObject side = new JsonObject();
        side.add("team", team);
        side.addProperty("score", score);
        return side;
    }

    private static JsonElement time(long millis) {
        return millis > 0 ? new com.google.gson.JsonPrimitive(Instant.ofEpochMilli(millis).toString()) : JsonNull.INSTANCE;
    }

    private static long longOf(String s) {
        if (s == null || s.isBlank()) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
