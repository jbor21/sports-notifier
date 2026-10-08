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
        assertEquals("42.3", GameLabels.clock(PlayBuilder.nba("g").clock(423).build()));
        assertEquals("0.5", GameLabels.clock(PlayBuilder.nba("g").clock(5).build()));
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
}
