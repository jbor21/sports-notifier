package feed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TeamIdsTest {

    @Test
    void leagueQualifiedAndLowerCase() {
        assertEquals("mlb-chw", TeamIds.of("MLB", "CHW"));
        assertEquals("nba-lal", TeamIds.of("nba", "LAL"));
    }

    @Test
    void missingPartIsNull() {
        assertNull(TeamIds.of(null, "CHW"));
        assertNull(TeamIds.of("MLB", null));
        assertNull(TeamIds.of("MLB", " "));
    }

    @Test
    void scoreboardAbbreviationThatDiffersFromTheTeamList() {
        // ESPN's NHL scoreboard says UTA; its team list (teams.json) says UTAH. Followers of nhl-utah
        // never matched a Utah game (found replaying the recordings, 2026-10-08).
        assertEquals("nhl-utah", TeamIds.of("NHL", "UTA"));
        assertEquals("nhl-utah", TeamIds.of("NHL", "UTAH"));
        assertEquals("nba-utah", TeamIds.of("NBA", "UTAH"));
        assertTrue(catalog.Teams.load().find(TeamIds.of("NHL", "UTA")).isPresent());
    }
}
