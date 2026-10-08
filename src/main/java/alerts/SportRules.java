package alerts;

import java.util.List;
import java.util.regex.Pattern;

/** The per-sport numbers decision 15 fixes. */
public final class SportRules {

    /** 5:00, in tenths of a second. */
    public static final int FIVE_MINUTES = 3000;

    private static final Pattern SHOOTOUT = Pattern.compile("\\bSO\\b");

    private SportRules() {}

    /** Periods in regulation: 4 quarters, 3 periods, 9 innings. */
    public static int regulation(String league) {
        if ("NHL".equals(league)) return 3;
        if ("MLB".equals(league)) return 9;
        return 4;
    }

    /** The largest score gap that still counts as close late. */
    public static int closeGap(String league) {
        return switch (league == null ? "" : league) {
            case "NBA" -> 5;
            case "NFL" -> 8;
            case "NHL" -> 1;
            case "MLB" -> 2;
            default -> -1;
        };
    }

    /**
     * The late-game half of close game late (spec 3.7): the last regulation period with 5:00 or less,
     * or the 8th or 9th inning. Never overtime. An unknown clock (-1) is not late.
     */
    public static boolean isLate(Play p) {
        if (!p.live()) return false;
        if ("MLB".equals(p.league())) return p.period() == 8 || p.period() == 9;
        return p.period() == regulation(p.league()) && p.clockTenths() >= 0 && p.clockTenths() <= FIVE_MINUTES;
    }

    /** Clock sports need the late condition on two plays in a row; MLB has no clock to glitch. */
    public static boolean hasClock(String league) {
        return !"MLB".equals(league);
    }

    /** The periods whose end alerts (spec 3.6). Halftime and the last regulation period are separate. */
    public static List<Integer> endOfPeriodPeriods(String league) {
        return switch (league == null ? "" : league) {
            case "NFL", "NBA" -> List.of(1, 3);
            case "NHL" -> List.of(1, 2);
            default -> List.of();
        };
    }

    public static boolean hasHalftime(String league) {
        return "NFL".equals(league) || "NBA".equals(league);
    }

    /**
     * NHL outside the postseason, at period 5 or with "SO" in ESPN's status text. A best guess: the
     * recordings have no shootout (spec 3.9). In the postseason period 5 is double overtime.
     */
    public static boolean isShootout(Play p) {
        if (!"NHL".equals(p.league()) || p.seasonType() == 3) return false;
        return p.period() >= 5 || (p.detail() != null && SHOOTOUT.matcher(p.detail()).find());
    }
}
