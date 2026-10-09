package checker;

import catalog.AlertSetting;
import catalog.FollowKind;
import org.junit.jupiter.api.Test;
import service.impl.Follow;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Follows must not change during a run (Phase 4.1 spec, 3.2). */
class FollowFreezeTest {

    private static final Instant T0 = Instant.parse("2026-10-09T12:00:00Z");
    private static final Instant END = T0.plusSeconds(1200);
    private static final Instant BEFORE = T0.minusSeconds(60);

    private static FollowSnapshot snap(String id, boolean on, Instant expires, Instant updated) {
        Follow f = new Follow(id, "u1", FollowKind.GAME, "nhl-rpabc123-1", "NHL",
            Map.of("GOAL", new AlertSetting(on, Map.of())), expires, BEFORE);
        return new FollowSnapshot(f, updated);
    }

    private static List<Finding> compare(List<FollowSnapshot> start, List<FollowSnapshot> end) {
        return FollowFreeze.compare(start, end, T0, END);
    }

    @Test
    void unchangedPasses() {
        assertEquals(List.of(), compare(List.of(snap("f1", true, null, BEFORE)), List.of(snap("f1", true, null, BEFORE))));
    }

    @Test
    void changedAlertsFails() {
        List<Finding> f = compare(List.of(snap("f1", true, null, BEFORE)), List.of(snap("f1", false, null, BEFORE)));
        assertEquals(1, f.size());
        assertEquals("follow_changed", f.get(0).kind());
        assertEquals("u1", f.get(0).userId());
    }

    @Test
    void deletedFollowFails() {
        assertEquals(1, compare(List.of(snap("f1", true, null, BEFORE)), List.of()).size());
    }

    @Test
    void addedFollowFails() {
        assertEquals(1, compare(List.of(), List.of(snap("f2", true, null, BEFORE))).size());
    }

    @Test
    void updatedAfterT0Fails() {
        // Flipped and flipped back: the alerts are equal again, updated_at is not.
        assertEquals(1, compare(List.of(snap("f1", true, null, BEFORE)), List.of(snap("f1", true, null, T0.plusSeconds(5)))).size());
    }

    @Test
    void expiresAtSetToFutureAllowed() {
        assertEquals(List.of(), compare(List.of(snap("f1", true, null, BEFORE)),
            List.of(snap("f1", true, END.plusSeconds(86_400), BEFORE))));
    }

    @Test
    void expiresAtSetBeforeRunEndFails() {
        assertEquals(1, compare(List.of(snap("f1", true, null, BEFORE)), List.of(snap("f1", true, END.minusSeconds(1), BEFORE))).size());
    }
}
