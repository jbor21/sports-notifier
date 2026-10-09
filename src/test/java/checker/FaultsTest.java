package checker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A self-test run passes only when the checker caught exactly the injected fault (Phase 4.1 spec, 6.4). */
class FaultsTest {

    private static Finding f(String kind, String user) {
        return new Finding(kind, user, "g", "m", "");
    }

    @Test
    void passesWhenThePrimaryKindIsFoundForTheUser() {
        assertTrue(Faults.passes("srem", "u1", List.of(f("history_missing", "u1"), f("socket_missing", "u1"))));
        assertTrue(Faults.passes("dup", "u1", List.of(f("socket_duplicate", "u1"))));
        assertTrue(Faults.passes("follow", "u1", List.of(f("follow_changed", "u1"))));
    }

    @Test
    void failsWithNoFindings() {
        assertFalse(Faults.passes("srem", "u1", List.of()));
    }

    @Test
    void failsWhenAnotherUserIsAffected() {
        assertFalse(Faults.passes("dup", "u1", List.of(f("socket_duplicate", "u1"), f("socket_missing", "u2"))));
    }

    @Test
    void failsWithoutThePrimaryKind() {
        assertFalse(Faults.passes("srem", "u1", List.of(f("socket_missing", "u1"))));
    }

    @Test
    void unknownFaultRejected() {
        assertThrows(IllegalArgumentException.class, () -> Faults.primaryKind("kill"));
    }
}
