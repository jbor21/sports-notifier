package checker;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RunIdTest {

    @Test
    void sixBase36Chars() {
        for (long s : new long[]{Instant.now().getEpochSecond(), Instant.parse("2030-01-01T00:00:00Z").getEpochSecond()}) {
            String id = RunId.of(s);
            assertTrue(id.matches("[0-9a-z]{6}"), id);
            assertEquals(s, Long.parseLong(id, 36));
        }
    }

    @Test
    void usernameFitsTwentyChars() {
        String u = RunId.username(RunId.of(Instant.now().getEpochSecond()), 99_999);
        assertTrue(u.matches("[a-z0-9_]{3,20}"), u);
    }

    @Test
    void gameMarker() {
        assertEquals("-rpabc123-", RunId.gameMarker("abc123"));
    }
}
