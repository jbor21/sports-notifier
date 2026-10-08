package feed;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches ESPN's schedule for every league over 11 UTC dates, today − 2 through today + 8, and
 * saves it through {@link ScheduleWriter}. The game lists serve yesterday through 7 days ahead in
 * the user's timezone, and the extra day on each side covers any timezone. 4 leagues × 11 dates =
 * 44 requests a run; the ESPN poller runs it every 15 minutes, never with the simulator.
 */
public final class ScheduleLoop {

    static final String[] LEAGUES = {"NBA", "NFL", "MLB", "NHL"};

    /** Where a day's scoreboard comes from: ESPN in production, a fake in tests. */
    @FunctionalInterface
    public interface ScheduleSource {
        String fetch(String league, LocalDate date) throws Exception;
    }

    private final ScheduleSource source;
    private final ScheduleWriter writer;
    private final Clock clock;

    public ScheduleLoop(ScheduleSource source, ScheduleWriter writer, Clock clock) {
        this.source = source;
        this.writer = writer;
        this.clock = clock;
    }

    /** Today − 2 through today + 8. */
    public static List<LocalDate> dates(LocalDate todayUtc) {
        List<LocalDate> out = new ArrayList<>();
        for (int d = -2; d <= 8; d++) out.add(todayUtc.plusDays(d));
        return out;
    }

    /**
     * One pass over every league and date. A failed league or date is logged and skipped; its earlier
     * entries stay until their TTL.
     *
     * @return games written
     */
    public int runOnce() {
        int written = 0;
        for (LocalDate date : dates(LocalDate.now(clock.withZone(ZoneOffset.UTC)))) {
            for (String league : LEAGUES) {
                try {
                    written += writer.write(EspnScoreFeed.parseSchedule(league, source.fetch(league, date)));
                } catch (Exception e) {
                    System.err.println("[Schedule " + league + " " + date + "] skipped: " + e);
                }
            }
        }
        return written;
    }
}
