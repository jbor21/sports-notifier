package feed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameIdsTest {

    @Test
    void replayIdsAreRecognized() {
        assertTrue(GameIds.isReplay("nfl-rpabc123-401872971"));
        assertFalse(GameIds.isReplay("nfl-401872971"));
        assertFalse(GameIds.isReplay("nhl-sim1700000000-3"));
        assertFalse(GameIds.isReplay(null));
        assertEquals("nfl-rpabc-401", GameIds.replay("NFL", "abc", "401"));
        assertTrue(GameIds.isReplay(GameIds.replay("NHL", "x", "1")));
    }
}
