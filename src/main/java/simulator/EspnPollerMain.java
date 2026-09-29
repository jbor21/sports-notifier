package simulator;

import com.google.gson.*;
import model.GameEvent;
import util.GameEventParser;
import util.SimpleHttp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Polls the ESPN unofficial scoreboard API for NBA, NFL, MLB, and NHL.
 * Diffs game state between polls to synthesize GAME_START, SCORE_UPDATE,
 * and GAME_END events, then forwards them to the Event Processing Service.
 *
 * No API key required. Poll interval defaults to 30s (configurable via
 * ESPN_POLL_INTERVAL_MS env var).
 */
public final class EspnPollerMain {

    private static final String EPS_URL =
        System.getenv().getOrDefault("EPS_URL", "http://127.0.0.1:8083");
    private static final long POLL_MS =
        Long.parseLong(System.getenv().getOrDefault("ESPN_POLL_INTERVAL_MS", "30000"));

    // { sport path, league path, league label for our system }
    private static final String[][] LEAGUES = {
        {"basketball", "nba", "NBA"},
        {"football",   "nfl", "NFL"},
        {"baseball",   "mlb", "MLB"},
        {"hockey",     "nhl", "NHL"},
    };

    // gameId → last known state
    private static final Map<String, Snapshot> prev = new ConcurrentHashMap<>();

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[ESPN Poller] Starting — poll interval: " + POLL_MS + "ms");
        System.out.println("[ESPN Poller] EPS target: " + EPS_URL);

        ScheduledExecutorService exec = Executors.newScheduledThreadPool(LEAGUES.length);
        for (String[] lg : LEAGUES) {
            final String sport = lg[0], league = lg[1], label = lg[2];
            // stagger initial polls by 2s per league so we don't hammer ESPN all at once
            long initialDelay = (league.equals("nba") ? 0 : league.equals("nfl") ? 2 :
                                  league.equals("mlb") ? 4 : 6) * 1000L;
            exec.scheduleAtFixedRate(
                () -> pollLeague(sport, league, label),
                initialDelay, POLL_MS, TimeUnit.MILLISECONDS);
        }

        Thread.currentThread().join(); // run forever until killed
    }

    // ── Poll ─────────────────────────────────────────────────────
    private static void pollLeague(String sport, String leagueId, String label) {
        String url = "https://site.api.espn.com/apis/site/v2/sports/"
            + sport + "/" + leagueId + "/scoreboard";
        try {
            String body = SimpleHttp.get(url).body();
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray events = root.getAsJsonArray("events");
            if (events == null) return;
            int active = 0;
            for (JsonElement e : events) {
                if (processGame(e.getAsJsonObject(), label)) active++;
            }
            System.out.println("[ESPN Poller] " + label + " — " + events.size()
                + " games on scoreboard, " + active + " in progress");
        } catch (Exception e) {
            System.err.println("[ESPN Poller " + label + "] poll error: " + e.getMessage());
        }
    }

    // ── Process one game object from ESPN ─────────────────────────
    private static boolean processGame(JsonObject event, String league) {
        String rawId = getString(event, "id");
        if (rawId == null) return false;
        String gameId = "espn-" + rawId;

        JsonArray comps = event.getAsJsonArray("competitions");
        if (comps == null || comps.isEmpty()) return false;
        JsonObject comp = comps.get(0).getAsJsonObject();

        // ── Status ───────────────────────────────────────────────
        JsonObject status = comp.getAsJsonObject("status");
        if (status == null) return false;
        JsonObject stype = status.getAsJsonObject("type");
        if (stype == null) return false;
        String statusName = getString(stype, "name");         // e.g. STATUS_IN_PROGRESS
        String statusDesc = getString(stype, "description");  // e.g. "In Progress"
        boolean completed = stype.has("completed") && stype.get("completed").getAsBoolean();

        // ── Teams & scores ────────────────────────────────────────
        JsonArray competitors = comp.getAsJsonArray("competitors");
        if (competitors == null || competitors.size() < 2) return false;

        String homeTeam = null, awayTeam = null;
        int homeScore = 0, awayScore = 0;
        for (JsonElement c : competitors) {
            JsonObject competitor = c.getAsJsonObject();
            String side = getString(competitor, "homeAway");
            JsonObject team = competitor.getAsJsonObject("team");
            if (team == null) continue;
            String abbr = getString(team, "abbreviation");
            int score = parseScore(getString(competitor, "score"));
            if ("home".equals(side)) { homeTeam = abbr; homeScore = score; }
            else                     { awayTeam = abbr; awayScore = score; }
        }
        if (homeTeam == null || awayTeam == null) return false;

        boolean inProgress = isInProgress(statusName);
        long now = System.currentTimeMillis();

        Snapshot last = prev.get(gameId);
        Snapshot curr = new Snapshot(statusName, homeScore, awayScore, completed);
        prev.put(gameId, curr);

        if (last == null) {
            // First time we see this game
            if (inProgress) {
                // Joined mid-game — emit START then current score
                post(gameId, "GAME_START", homeTeam, homeTeam, awayTeam, 0, 0,
                    "IN_PROGRESS", league, homeTeam + " vs " + awayTeam + " has started", now);
                if (homeScore > 0 || awayScore > 0) {
                    post(gameId, "SCORE_UPDATE", homeTeam, homeTeam, awayTeam,
                        homeScore, awayScore, "IN_PROGRESS", league,
                        homeTeam + " " + homeScore + " - " + awayScore + " " + awayTeam, now);
                }
                return true;
            }
            if (completed) {
                // Already finished — emit START + END so it shows as a final card in the UI
                post(gameId, "GAME_START", homeTeam, homeTeam, awayTeam, 0, 0,
                    "IN_PROGRESS", league, homeTeam + " vs " + awayTeam + " has started", now - 10000);
                post(gameId, "GAME_END", homeTeam, homeTeam, awayTeam,
                    homeScore, awayScore, "FINAL", league,
                    "Final: " + homeTeam + " " + homeScore + " - " + awayScore + " " + awayTeam, now);
                return false; // don't count as active
            }
            // Scheduled — just record state, wait for it to go live
            return false;
        }

        boolean wasInProgress = isInProgress(last.statusName);

        // GAME_START: transitioned from scheduled → in progress
        if (!wasInProgress && !last.completed && inProgress) {
            post(gameId, "GAME_START", homeTeam, homeTeam, awayTeam, 0, 0,
                "IN_PROGRESS", league, homeTeam + " vs " + awayTeam + " has started", now);
        }

        // SCORE_UPDATE: scores changed while active
        if (inProgress && (homeScore != last.homeScore || awayScore != last.awayScore)) {
            String scorer = homeScore > last.homeScore ? homeTeam : awayTeam;
            post(gameId, "SCORE_UPDATE", scorer, homeTeam, awayTeam,
                homeScore, awayScore, "IN_PROGRESS", league,
                scorer + " scores! " + homeTeam + " " + homeScore + " - " + awayScore + " " + awayTeam,
                now);
        }

        // GAME_END: transitioned to final
        if (!last.completed && completed) {
            post(gameId, "GAME_END", homeTeam, homeTeam, awayTeam,
                homeScore, awayScore, "FINAL", league,
                "Final: " + homeTeam + " " + homeScore + " - " + awayScore + " " + awayTeam, now);
        }

        return inProgress || completed;
    }

    // ── Helpers ───────────────────────────────────────────────────
    private static boolean isInProgress(String statusName) {
        if (statusName == null) return false;
        return statusName.equals("STATUS_IN_PROGRESS")
            || statusName.equals("STATUS_HALFTIME")
            || statusName.equals("STATUS_END_PERIOD")
            || statusName.equals("STATUS_SECOND_HALF")
            || statusName.contains("IN_PROGRESS");
    }

    private static void post(String gameId, String type, String team,
                              String home, String away, int hs, int as,
                              String status, String league, String desc, long ts) {
        try {
            GameEvent ev = new GameEvent(gameId, uuid(), type, team, ts,
                desc, home, away, hs, as, status, league);
            SimpleHttp.postJson(EPS_URL + "/events", GameEventParser.toJson(ev));
            System.out.println("[ESPN Poller] " + type + " " + gameId + " :: " + desc);
        } catch (Exception e) {
            System.err.println("[ESPN Poller] Failed to post " + type + " for " + gameId + ": " + e.getMessage());
        }
    }

    private static int parseScore(String s) {
        if (s == null) return 0;
        try { return (int) Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private static String uuid() { return UUID.randomUUID().toString(); }

    // ── State snapshot ────────────────────────────────────────────
    private static final class Snapshot {
        final String statusName;
        final int homeScore;
        final int awayScore;
        final boolean completed;

        Snapshot(String statusName, int homeScore, int awayScore, boolean completed) {
            this.statusName = statusName;
            this.homeScore = homeScore;
            this.awayScore = awayScore;
            this.completed = completed;
        }
    }
}
