package games;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Which days {@code GET /games} serves, and a day's bounds in a timezone (Phase 3.4 spec, 5.3). */
public final class GameDays {

    /** The schedule covers yesterday through 7 days ahead. */
    static final int DAYS_BACK = 1;
    static final int DAYS_AHEAD = 7;

    private GameDays() {}

    /** Local midnight to the next local midnight, so a DST day is 23 or 25 hours, not 24. */
    public record Range(long fromMillis, long toMillis) {}

    public static final class OutOfRange extends Exception {
        OutOfRange(String message) { super(message); }
    }

    public static LocalDate today(ZoneId tz, Instant now) {
        return LocalDate.ofInstant(now, tz);
    }

    public static Range range(LocalDate date, ZoneId tz, Instant now) throws OutOfRange {
        LocalDate today = today(tz, now);
        LocalDate first = today.minusDays(DAYS_BACK), last = today.plusDays(DAYS_AHEAD);
        if (date.isBefore(first) || date.isAfter(last)) {
            throw new OutOfRange("Games are listed from " + first + " to " + last + " in " + tz.getId() + ".");
        }
        return new Range(date.atStartOfDay(tz).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(tz).toInstant().toEpochMilli());
    }
}
