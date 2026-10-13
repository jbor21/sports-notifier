package tools;

import checker.SetupException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A run's set is checked before anything is created, and a bad one is a setup failure (exit 2), not a crash. */
class CheckerSetTest {

    @Test
    void unknownSetIsASetupFailure() {
        SetupException e = assertThrows(SetupException.class, () -> Checker.loadSet("nosuchset"));
        assertTrue(e.getMessage().contains("nosuchset"), e.getMessage());
    }

    @Test
    void knownSetLoads() {
        assertFalse(Checker.loadSet("ci").isEmpty());
    }
}
