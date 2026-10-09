package service.impl;

import alerts.Alert;
import alerts.Detectors;
import alerts.GameMemory;
import alerts.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The per-user combining step Stage 1 and the checker share (Phase 4.1 spec, 6.1). */
class MomentPushesTest {

    private static final Alert TD = Alert.of("nfl-1:SCORE:away:2", "TOUCHDOWN", Alert.P_SCORE, Side.AWAY, "Touchdown");
    private static final Alert LEAD = Alert.of("nfl-1:LEAD_CHANGE:1", "LEAD_CHANGE", Alert.P_LEAD, Side.AWAY, "Lead");
    private static final Alert CLOSE = Alert.of("nfl-1:CLOSE_LATE", "CLOSE_GAME_LATE", Alert.P_CLOSE, null, "Close");
    private static final Alert FINAL = Alert.of("nfl-1:FINAL", "FINAL", Alert.P_FLOW, null, "Final");

    private static Detectors.Result result(List<Alert> moment, Alert fin) {
        return new Detectors.Result(moment, fin, new GameMemory());
    }

    @Test
    void groupsMomentThenFinal() {
        assertEquals(List.of(List.of(TD, LEAD), List.of(FINAL)), MomentPushes.groups(result(List.of(TD, LEAD), FINAL)));
    }

    @Test
    void groupsSkipEmptyMoment() {
        assertEquals(List.of(List.of(FINAL)), MomentPushes.groups(result(List.of(), FINAL)));
    }

    @Test
    void groupsNoFinal() {
        assertEquals(List.of(List.of(TD)), MomentPushes.groups(result(List.of(TD), null)));
        assertEquals(List.of(), MomentPushes.groups(result(List.of(), null)));
    }

    @Test
    void pickKeepsHeadlineOrder() {
        assertEquals(List.of(TD, CLOSE), MomentPushes.pick(List.of(TD, LEAD, CLOSE), List.of(true, false, true)));
    }

    @Test
    void pickNoneIsEmpty() {
        assertTrue(MomentPushes.pick(List.of(TD, LEAD), List.of(false, false)).isEmpty());
    }
}
