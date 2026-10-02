package feed;

import model.GameSnapshot;
import model.GameSnapshot.Half;
import model.GameSnapshot.Phase;
import model.GameSnapshot.Stage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EspnScoreFeedTest {

    private static List<GameSnapshot> parse(String file, String league) throws IOException {
        try (InputStream in = EspnScoreFeedTest.class.getResourceAsStream("/espn/" + file)) {
            assertNotNull(in, "missing fixture " + file);
            return EspnScoreFeed.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), league);
        }
    }

    private static GameSnapshot only(String file, String league) throws IOException {
        List<GameSnapshot> games = parse(file, league);
        assertEquals(1, games.size());
        return games.get(0);
    }

    @Test
    void nflLiveParsesPositionAndScore() throws IOException {
        GameSnapshot g = only("nfl-live.json", "NFL");
        assertEquals("espn-401872964", g.gameId());
        assertEquals("NFL", g.league());
        assertEquals("CLE", g.homeTeam());
        assertEquals("PIT", g.awayTeam());
        assertEquals(24, g.homeScore());
        assertEquals(21, g.awayScore());
        assertEquals(Phase.LIVE, g.phase());
        assertEquals(4, g.period());
        assertEquals(1200, g.clockTenths());
        assertEquals(Stage.IN_PLAY, g.stage());
        assertEquals("IN_PROGRESS", g.status());
    }

    @Test
    void nflScheduledHasStartTime() throws IOException {
        GameSnapshot g = only("nfl-scheduled.json", "NFL");
        assertEquals(Phase.PRE, g.phase());
        assertEquals("SCHEDULED", g.status());
        assertEquals(Instant.parse("2026-10-04T13:30:00Z").toEpochMilli(), g.startTimeMillis());
    }

    @Test
    void halftimeAndEndPeriodStages() throws IOException {
        GameSnapshot half = only("nfl-halftime.json", "NFL");
        assertEquals(Stage.HALFTIME, half.stage());
        assertEquals("HALFTIME", half.status());
        assertEquals(Phase.LIVE, half.phase());
        GameSnapshot end = only("nba-end-period.json", "NBA");
        assertEquals(Stage.END_PERIOD, end.stage());
        assertEquals("END_PERIOD", end.status());
    }

    @Test
    void mlbLiveHasHalfAndOuts() throws IOException {
        GameSnapshot g = only("mlb-live.json", "MLB");
        assertEquals(Phase.LIVE, g.phase());
        assertEquals(4, g.period());
        assertEquals(Half.BOTTOM, g.half());
        assertEquals(2, g.outs());
        assertEquals(0, g.clockTenths());
    }

    @Test
    void mlbDelayedPostponedSuspended() throws IOException {
        GameSnapshot delayed = only("mlb-delayed.json", "MLB");
        assertEquals(Phase.LIVE, delayed.phase());
        assertEquals(Stage.DELAYED, delayed.stage());
        assertEquals("DELAYED", delayed.status());

        GameSnapshot postponed = only("mlb-postponed.json", "MLB");
        assertEquals(Phase.PRE, postponed.phase());
        assertEquals(Stage.DELAYED, postponed.stage());
        assertEquals("POSTPONED", postponed.status());

        GameSnapshot suspended = only("mlb-suspended.json", "MLB");
        assertEquals(Phase.LIVE, suspended.phase());
        assertEquals(Stage.DELAYED, suspended.stage());
        assertEquals("SUSPENDED", suspended.status());
    }

    @Test
    void finalIsPhaseFinal() throws IOException {
        GameSnapshot g = only("nfl-final.json", "NFL");
        assertEquals(Phase.FINAL, g.phase());
        assertEquals("FINAL", g.status());
    }

    @Test
    void canceledIsPhaseFinal() throws IOException {
        GameSnapshot g = only("nhl-canceled.json", "NHL");
        assertEquals(Phase.FINAL, g.phase());
        assertEquals("CANCELED", g.status());
    }

    @Test
    void emptyScoreboardIsEmptyList() throws IOException {
        assertTrue(parse("empty.json", "NBA").isEmpty());
        assertTrue(EspnScoreFeed.parse("{}", "NBA").isEmpty());
    }

    @Test
    void brokenGameIsSkipped() throws IOException {
        List<GameSnapshot> games = parse("broken.json", "NHL");
        assertEquals(1, games.size());
        assertEquals("espn-401891803", games.get(0).gameId());
    }

    @Test
    void unreadableDateKeepsTheGame() {
        String json = "{\"events\":[{\"id\":\"8\",\"date\":\"soon\",\"competitions\":[{"
            + "\"status\":{\"period\":0,\"type\":{\"name\":\"STATUS_SCHEDULED\",\"state\":\"pre\",\"completed\":false}},"
            + "\"competitors\":[{\"homeAway\":\"home\",\"score\":\"0\",\"team\":{\"abbreviation\":\"DET\"}},"
            + "{\"homeAway\":\"away\",\"score\":\"0\",\"team\":{\"abbreviation\":\"NYR\"}}]}]}]}";
        GameSnapshot g = EspnScoreFeed.parse(json, "NHL").get(0);
        assertEquals(0L, g.startTimeMillis());
    }

    @Test
    void unknownStatusFallsBackToState() {
        String json = "{\"events\":[{\"id\":\"9\",\"date\":\"2026-10-02T23:00Z\",\"competitions\":[{"
            + "\"status\":{\"clock\":300.0,\"period\":2,\"type\":{\"name\":\"STATUS_SOMETHING_NEW\",\"state\":\"in\",\"completed\":false}},"
            + "\"competitors\":[{\"homeAway\":\"home\",\"score\":\"1\",\"team\":{\"abbreviation\":\"DET\"}},"
            + "{\"homeAway\":\"away\",\"score\":\"0\",\"team\":{\"abbreviation\":\"NYR\"}}]}]}]}";
        GameSnapshot g = EspnScoreFeed.parse(json, "NHL").get(0);
        assertEquals(Phase.LIVE, g.phase());
        assertEquals("IN_PROGRESS", g.status());
        assertEquals(3000, g.clockTenths());
    }
}
