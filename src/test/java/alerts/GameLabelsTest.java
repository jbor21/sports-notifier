package alerts;

import catalog.Teams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameLabelsTest {

    private static final GameLabels LABELS = new GameLabels(Teams.load());

    @Test
    void nflLabels() {
        assertEquals("Q1", GameLabels.periodLabel(PlayBuilder.nfl("g").period(1).build()));
        assertEquals("Half", GameLabels.periodLabel(PlayBuilder.nfl("g").period(2).stage("HALFTIME").build()));
        assertEquals("OT", GameLabels.periodLabel(PlayBuilder.nfl("g").period(5).build()));
        assertEquals("2OT", GameLabels.periodLabel(PlayBuilder.nfl("g").period(6).build()));
        assertNull(GameLabels.periodLabel(PlayBuilder.nfl("g").pre().build()));
    }

    @Test
    void nhlLabels() {
        assertEquals("3rd", GameLabels.periodLabel(PlayBuilder.nhl("g").period(3).build()));
        assertEquals("OT", GameLabels.periodLabel(PlayBuilder.nhl("g").period(4).build()));
        assertEquals("SO", GameLabels.periodLabel(PlayBuilder.nhl("g").period(5).build()));
        assertEquals("2OT", GameLabels.periodLabel(PlayBuilder.nhl("g").period(5).season(3).build()));
    }

    @Test
    void mlbLabels() {
        assertEquals("Top 4th", GameLabels.periodLabel(PlayBuilder.mlb("g").period(4).half("TOP").build()));
        assertEquals("Mid 4th", GameLabels.periodLabel(PlayBuilder.mlb("g").period(4).half("MIDDLE").build()));
        assertEquals("Bot 4th", GameLabels.periodLabel(PlayBuilder.mlb("g").period(4).half("BOTTOM").build()));
        assertEquals("End 4th", GameLabels.periodLabel(PlayBuilder.mlb("g").period(4).half("END").build()));
        assertEquals("Top 11th", GameLabels.periodLabel(PlayBuilder.mlb("g").period(11).half("TOP").build()));
        assertEquals("Bot 1st", GameLabels.periodLabel(PlayBuilder.mlb("g").period(1).half("BOTTOM").build()));
        assertEquals("Top 2nd", GameLabels.periodLabel(PlayBuilder.mlb("g").period(2).build()));
        assertEquals("Top 3rd", GameLabels.periodLabel(PlayBuilder.mlb("g").period(3).build()));
    }

    @Test
    void clockFormats() {
        assertEquals("4:12", GameLabels.clock(PlayBuilder.nfl("g").clock(2520).build()));
        assertEquals("1:00", GameLabels.clock(PlayBuilder.nfl("g").clock(600).build()));
        // Under a minute reads like the rest, "0:42", not ESPN's "42.3" (user, 2026-10-08).
        assertEquals("0:42", GameLabels.clock(PlayBuilder.nba("g").clock(423).build()));
        assertEquals("0:00", GameLabels.clock(PlayBuilder.nba("g").clock(5).build()));
        assertEquals("0:59", GameLabels.clock(PlayBuilder.nba("g").clock(599).build()));
        assertNull(GameLabels.clock(PlayBuilder.mlb("g").build()));
        assertNull(GameLabels.clock(PlayBuilder.nfl("g").stage("END_PERIOD").clock(0).build()));
        assertNull(GameLabels.clock(PlayBuilder.nfl("g").clock(-1).build()));
        assertNull(GameLabels.clock(PlayBuilder.nfl("g").fin().clock(0).build()));
    }

    @Test
    void scoreLineLeaderFirst() {
        assertEquals("Raiders 20, Bears 17 · Q4 4:12",
            LABELS.scoreLine(PlayBuilder.nfl("g").period(4).clock(2520).score(17, 20).build()));
    }

    @Test
    void scoreLineTiedAwayFirst() {
        assertEquals("Raiders 14, Bears 14 · Half",
            LABELS.scoreLine(PlayBuilder.nfl("g").period(2).stage("HALFTIME").clock(0).score(14, 14).build()));
    }

    @Test
    void scoreLineWithoutPosition() {
        assertEquals("Bears 20, Raiders 17",
            LABELS.scoreLine(PlayBuilder.nfl("g").fin().period(4).clock(0).score(20, 17).build()));
    }

    @Test
    void unknownTeamUsesAbbreviation() {
        assertEquals("AFC", LABELS.shortName("NFL", "AFC"));
        assertEquals("AFC 3, NFC 0 · Q1 15:00",
            LABELS.scoreLine(PlayBuilder.nfl("g").teams("AFC", "NFC").score(3, 0).build()));
    }

    @Test
    void finalRegulationIsFinal() {
        assertEquals("Final", GameLabels.periodLabel(PlayBuilder.nfl("g").fin().period(4).clock(0).build()));
        assertEquals("Final", GameLabels.periodLabel(PlayBuilder.mlb("g").fin().period(9).build()));
        assertEquals("Final", GameLabels.periodLabel(PlayBuilder.nhl("g").fin().period(3).clock(0).build()));
    }

    @Test
    void finalOvertimeLabels() {
        assertEquals("Final/OT", GameLabels.periodLabel(PlayBuilder.nfl("g").fin().period(5).build()));
        assertEquals("Final/2OT", GameLabels.periodLabel(PlayBuilder.nba("g").fin().period(6).build()));
        assertEquals("Final/OT", GameLabels.periodLabel(PlayBuilder.nhl("g").fin().period(4).build()));
        assertEquals("Final/SO", GameLabels.periodLabel(PlayBuilder.nhl("g").fin().period(5).build()));
        assertEquals("Final/2OT", GameLabels.periodLabel(PlayBuilder.nhl("g").fin().period(5).season(3).build()));
        assertEquals("Final/10", GameLabels.periodLabel(PlayBuilder.mlb("g").fin().period(10).build()));
    }

    @Test
    void betweenPeriodsSaysEnd() {
        assertEquals("End Q1", GameLabels.periodLabel(PlayBuilder.nfl("g").period(1).stage("END_PERIOD").build()));
        assertEquals("End 2nd", GameLabels.periodLabel(PlayBuilder.nhl("g").period(2).stage("END_PERIOD").build()));
        assertEquals("End OT", GameLabels.periodLabel(PlayBuilder.nhl("g").period(4).stage("END_PERIOD").build()));
        assertEquals("End 7th", GameLabels.periodLabel(PlayBuilder.mlb("g").period(7).half("END").build()));
        assertEquals("Mid 7th", GameLabels.periodLabel(PlayBuilder.mlb("g").period(7).half("MIDDLE").build()));
    }

    @Test
    void scheduledPostponedCanceledAreNull() {
        assertNull(GameLabels.periodLabel(PlayBuilder.nfl("g").pre().build()));
        assertNull(GameLabels.periodLabel(PlayBuilder.nfl("g").pre().status("POSTPONED").stage("DELAYED").build()));
        assertNull(GameLabels.periodLabel(PlayBuilder.nfl("g").fin().status("CANCELED").period(2).build()));
    }

    @Test
    void finalPositionIsNull() {
        assertNull(GameLabels.position(PlayBuilder.nhl("g").fin().period(4).clock(0).build()));
        assertEquals("End Q1", GameLabels.position(PlayBuilder.nfl("g").period(1).stage("END_PERIOD").clock(0).build()));
    }

    @Test
    void lineLabels() {
        assertEquals("Q1", GameLabels.lineLabel("NFL", 1, 2));
        assertEquals("OT", GameLabels.lineLabel("NBA", 5, 2));
        assertEquals("2OT", GameLabels.lineLabel("NFL", 6, 2));
        assertEquals("3", GameLabels.lineLabel("NHL", 3, 2));
        assertEquals("OT", GameLabels.lineLabel("NHL", 4, 2));
        assertEquals("SO", GameLabels.lineLabel("NHL", 5, 2));
        assertEquals("2OT", GameLabels.lineLabel("NHL", 5, 3));
        assertEquals("10", GameLabels.lineLabel("MLB", 10, 2));
    }
}
