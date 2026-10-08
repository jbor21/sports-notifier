package service.impl;

import catalog.AlertCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The 3.1 play types onto catalog switches, until 3.3's detectors (spec section 6). */
class AlertMappingTest {

    private static final AlertMapping MAPPING = new AlertMapping(AlertCatalog.load());

    @Test
    void gameStartAndEnd() {
        for (String league : List.of("NFL", "NBA", "MLB", "NHL")) {
            assertEquals(List.of("GAME_START"), MAPPING.switchesFor(league, "GAME_START"), league);
            assertEquals(List.of("FINAL"), MAPPING.switchesFor(league, "GAME_END"), league);
            assertEquals("GAME_START", MAPPING.headlineType(league, "GAME_START"));
            assertEquals("FINAL", MAPPING.headlineType(league, "GAME_END"));
        }
    }

    @Test
    void scoresReachTheSportsScoringSwitches() {
        assertEquals(List.of("TOUCHDOWN", "TRY_RESULT", "FIELD_GOAL", "SAFETY"), MAPPING.switchesFor("NFL", "SCORE_UPDATE"));
        assertEquals(List.of("RUN_SCORED"), MAPPING.switchesFor("MLB", "SCORE_UPDATE"));
        assertEquals(List.of("GOAL"), MAPPING.switchesFor("NHL", "SCORE_UPDATE"));
        assertEquals(List.of(), MAPPING.switchesFor("NBA", "SCORE_UPDATE"), "no alert per basket");
        assertEquals("SCORE", MAPPING.headlineType("NFL", "SCORE_UPDATE"), "a touchdown and a field goal look alike until 3.3");
        assertEquals("RUN_SCORED", MAPPING.headlineType("MLB", "SCORE_UPDATE"));
        assertEquals("GOAL", MAPPING.headlineType("NHL", "SCORE_UPDATE"));
    }

    @Test
    void syncAndUnknownReachNobody() {
        assertEquals(List.of(), MAPPING.switchesFor("NFL", "GAME_SYNC"));
        assertEquals(List.of(), MAPPING.switchesFor("XFL", "GAME_START"));
        assertEquals(List.of(), MAPPING.switchesFor(null, "GAME_START"));
    }
}
