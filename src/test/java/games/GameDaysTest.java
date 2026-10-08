package games;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class GameDaysTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    @Test
    void yesterdayToSevenAhead() throws Exception {
        GameDays.Range y = GameDays.range(LocalDate.of(2026, 10, 7), UTC, NOW);
        assertEquals(Instant.parse("2026-10-07T00:00:00Z").toEpochMilli(), y.fromMillis());
        assertEquals(Instant.parse("2026-10-08T00:00:00Z").toEpochMilli(), y.toMillis());
        GameDays.range(LocalDate.of(2026, 10, 15), UTC, NOW);
        GameDays.OutOfRange before = assertThrows(GameDays.OutOfRange.class,
            () -> GameDays.range(LocalDate.of(2026, 10, 6), UTC, NOW));
        assertTrue(before.getMessage().contains("2026-10-07") && before.getMessage().contains("2026-10-15"), before.getMessage());
        assertThrows(GameDays.OutOfRange.class, () -> GameDays.range(LocalDate.of(2026, 10, 16), UTC, NOW));
    }

    @Test
    void laTimezoneLateUtc() throws Exception {
        Instant now = Instant.parse("2026-10-09T03:30:00Z");
        ZoneId la = ZoneId.of("America/Los_Angeles");
        assertEquals(LocalDate.of(2026, 10, 8), GameDays.today(la, now));
        GameDays.range(LocalDate.of(2026, 10, 7), la, now);   // yesterday there, allowed
        GameDays.Range r = GameDays.range(LocalDate.of(2026, 10, 8), la, now);
        assertEquals(Instant.parse("2026-10-08T07:00:00Z").toEpochMilli(), r.fromMillis());
    }

    @Test
    void dstDayIsTwentyFiveHours() throws Exception {
        ZoneId ny = ZoneId.of("America/New_York");
        GameDays.Range r = GameDays.range(LocalDate.of(2026, 11, 1), ny, Instant.parse("2026-10-30T12:00:00Z"));
        assertEquals(25 * 3_600_000L, r.toMillis() - r.fromMillis());
    }
}
