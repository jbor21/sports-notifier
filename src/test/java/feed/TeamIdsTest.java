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
}
