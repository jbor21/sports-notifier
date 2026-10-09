package app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LauncherTest {

    private static final List<String> NAMES = List.of("gateway", "user-pref", "game-state",
        "event-processing", "stage1-worker", "stage2-worker", "notification", "history", "simulator", "espn-poller", "replay", "checker");
    private static final String VALID = "Valid values: " + String.join(", ", NAMES);

    @Test
    void serviceNamesAreExactlyThese() {
        assertEquals(NAMES, List.copyOf(Launcher.SERVICES.keySet()));
    }

    @Test
    void resolvesEveryKnownName() {
        for (String n : NAMES) assertNotNull(Launcher.resolve(n), n);
    }

    @Test
    void rejectsUnknownNameAndListsValidOnes() {
        var e = assertThrows(IllegalArgumentException.class, () -> Launcher.resolve("Gateway"));
        assertEquals("Unknown SERVICE \"Gateway\". " + VALID, e.getMessage());
    }

    @Test
    void rejectsMissingOrBlank() {
        for (String s : new String[] {null, "", "   "}) {
            var e = assertThrows(IllegalArgumentException.class, () -> Launcher.resolve(s));
            assertEquals("SERVICE is not set. " + VALID, e.getMessage());
        }
    }

    @Test
    void replayIsAService() {
        assertNotNull(Launcher.resolve("replay"));
    }

    @Test
    void checkerIsAService() {
        assertNotNull(Launcher.resolve("checker"));
    }
}
