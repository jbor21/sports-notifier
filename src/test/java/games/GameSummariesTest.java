package games;

import catalog.Teams;
import com.google.gson.JsonObject;
import model.GameEvent;
import model.LinePeriod;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GameSummariesTest {

    private static final GameSummaries SUMMARIES = new GameSummaries(Teams.load());
    private static final long START = 1_791_300_000_000L;

    /** A game:<id> hash as the accept script writes it. */
    static Map<String, String> record(String status, String phase, int home, int away) {
        Map<String, String> m = new HashMap<>();
        m.put("league", "NFL"); m.put("homeTeam", "CHI"); m.put("awayTeam", "LV");
        m.put("homeScore", String.valueOf(home)); m.put("awayScore", String.valueOf(away));
        m.put("status", status); m.put("phase", phase); m.put("period", "4"); m.put("clockTenths", "2520");
        m.put("half", "TOP"); m.put("outs", "0"); m.put("stage", "IN_PLAY"); m.put("seasonType", "2");
        m.put("detail", ""); m.put("startTime", String.valueOf(START)); m.put("version", "58");
        m.put("lastTimestamp", String.valueOf(START + 3_600_000L));
        m.put("lineScore", "[{\"period\":1,\"home\":7,\"away\":3},{\"period\":2,\"home\":10,\"away\":17}]");
        return m;
    }

    static Map<String, String> sched(String status) {
        Map<String, String> m = record(status, "PRE", 0, 0);
        m.remove("version"); m.remove("lastTimestamp");
        m.put("period", "0"); m.put("clockTenths", "0"); m.put("lineScore", "[]");
        m.put("venue", "Soldier Field"); m.put("broadcast", "FOX"); m.put("fetchedAt", String.valueOf(START - 1000));
        return m;
    }

    @Test
    void statusMapping() {
        assertEquals("scheduled", GameSummaries.apiStatus("SCHEDULED"));
        assertEquals("in_progress", GameSummaries.apiStatus("IN_PROGRESS"));
        assertEquals("in_progress", GameSummaries.apiStatus("END_PERIOD"));
        assertEquals("in_progress", GameSummaries.apiStatus("HALFTIME"));
        assertEquals("final", GameSummaries.apiStatus("FINAL"));
        assertEquals("delayed", GameSummaries.apiStatus("DELAYED"));
        assertEquals("postponed", GameSummaries.apiStatus("POSTPONED"));
        assertEquals("canceled", GameSummaries.apiStatus("CANCELED"));
        assertEquals("suspended", GameSummaries.apiStatus("SUSPENDED"));
    }

    @Test
    void recordOnly() {
        JsonObject g = SUMMARIES.summary("nfl-1", record("IN_PROGRESS", "LIVE", 17, 20), Map.of());
        assertEquals("nfl-1", g.get("id").getAsString());
        assertEquals("NFL", g.get("leagueId").getAsString());
        assertEquals("in_progress", g.get("status").getAsString());
        assertEquals("2026-10-06T15:20:00Z", g.get("startTime").getAsString());
        assertEquals("nfl-chi", g.getAsJsonObject("home").getAsJsonObject("team").get("id").getAsString());
        assertEquals("Chicago Bears", g.getAsJsonObject("home").getAsJsonObject("team").get("displayName").getAsString());
        assertEquals(20, g.getAsJsonObject("away").get("score").getAsInt());
        assertEquals(4, g.get("period").getAsInt());
        assertEquals("Q4", g.get("periodLabel").getAsString());
        assertEquals("4:12", g.get("clock").getAsString());
        assertEquals(58, g.get("version").getAsLong());
        assertEquals("2026-10-06T16:20:00Z", g.get("updatedAt").getAsString());
    }

    @Test
    void schedOnlyHasVersionZero() {
        JsonObject g = SUMMARIES.summary("nfl-1", Map.of(), sched("SCHEDULED"));
        assertEquals("scheduled", g.get("status").getAsString());
        assertEquals(0, g.get("version").getAsLong());
        assertTrue(g.get("period").isJsonNull());
        assertTrue(g.get("periodLabel").isJsonNull());
        assertTrue(g.get("clock").isJsonNull());
    }

    @Test
    void recordWinsOverSched() {
        JsonObject d = SUMMARIES.detail("nfl-1", record("FINAL", "FINAL", 17, 20), sched("SCHEDULED"));
        assertEquals("final", d.get("status").getAsString());
        assertEquals("Final", d.get("periodLabel").getAsString());
        assertEquals(58, d.get("version").getAsLong());
        assertEquals("Soldier Field", d.get("venue").getAsString());
        assertEquals("FOX", d.get("broadcast").getAsString());
        assertEquals(2, d.getAsJsonArray("lineScore").size());
        JsonObject q2 = d.getAsJsonArray("lineScore").get(1).getAsJsonObject();
        assertEquals("Q2", q2.get("label").getAsString());
        assertEquals(10, q2.get("home").getAsInt());
        assertEquals(17, q2.get("away").getAsInt());
    }

    @Test
    void unknownTeamUsesAbbreviation() {
        Map<String, String> r = record("IN_PROGRESS", "LIVE", 3, 0);
        r.put("homeTeam", "AFC");
        JsonObject team = SUMMARIES.summary("nfl-1", r, Map.of()).getAsJsonObject("home").getAsJsonObject("team");
        assertEquals("nfl-afc", team.get("id").getAsString());
        assertEquals("AFC", team.get("displayName").getAsString());
    }

    @Test
    void lineScoreLabelsPerLeague() {
        Map<String, String> r = record("FINAL", "FINAL", 4, 3);
        r.put("league", "NHL"); r.put("homeTeam", "WPG"); r.put("awayTeam", "BOS"); r.put("period", "4");
        r.put("lineScore", "[{\"period\":1,\"home\":1,\"away\":1},{\"period\":2,\"home\":2,\"away\":1},"
            + "{\"period\":3,\"home\":0,\"away\":1},{\"period\":4,\"home\":1,\"away\":0}]");
        JsonObject d = SUMMARIES.detail("nhl-1", r, Map.of());
        assertEquals("1", d.getAsJsonArray("lineScore").get(0).getAsJsonObject().get("label").getAsString());
        assertEquals("OT", d.getAsJsonArray("lineScore").get(3).getAsJsonObject().get("label").getAsString());
        assertEquals("Final/OT", d.get("periodLabel").getAsString());
        assertFalse(d.has("venue") && !d.get("venue").isJsonNull(), "no schedule entry, no venue");
    }

    @Test
    void liveMatchesSummary() {
        GameEvent play = new GameEvent("nfl-1", "nfl-1:1", "SCORE_UPDATE", "LV", START + 3_600_000L, "d",
            "CHI", "LV", 17, 20, "IN_PROGRESS", "NFL", 1L)
            .withPosition("LIVE", 4, 2520, "TOP", 0, "IN_PLAY", 2, "")
            .withSchedule(START, List.of(new LinePeriod(1, 7, 3), new LinePeriod(2, 10, 17)));
        JsonObject live = SUMMARIES.fromLive(play, 58);
        JsonObject stored = SUMMARIES.summary("nfl-1", record("IN_PROGRESS", "LIVE", 17, 20), Map.of());
        assertEquals(stored, live);
    }

    @Test
    void scheduledHasNoPeriod() {
        JsonObject g = SUMMARIES.summary("nfl-1", record("SCHEDULED", "PRE", 0, 0), Map.of());
        assertTrue(g.get("period").isJsonNull());
        assertTrue(g.get("clock").isJsonNull());
    }
}
