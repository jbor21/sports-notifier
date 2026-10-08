package alerts;

import catalog.Teams;
import feed.TeamIds;

/** Display parts for a play: period label, clock, team short names, the score line (spec 2.6). */
public final class GameLabels {

    private final Teams teams;

    public GameLabels(Teams teams) {
        this.teams = teams;
    }

    public Teams teams() {
        return teams;
    }

    /**
     * What a score card shows beside the score: {@code Q4}, {@code Half}, {@code End Q1}, {@code 2OT},
     * {@code 3rd}, {@code SO}, {@code Bot 7th}, {@code Final}, {@code Final/OT}, {@code Final/10}.
     * Null before the start and for postponed or canceled games.
     */
    public static String periodLabel(Play p) {
        if ("FINAL".equals(p.phase()) && "FINAL".equals(p.status())) return finalLabel(p);
        return livePeriodLabel(p);
    }

    /** {@code Final}, or {@code Final/} plus the last period's label when it went past regulation. */
    private static String finalLabel(Play p) {
        int n = p.period();
        String league = p.league() == null ? "" : p.league();
        boolean extra = switch (league) {
            case "MLB" -> n > 9;
            case "NHL" -> n > 3;
            default -> n > 4;
        };
        return extra ? "Final/" + lineLabel(league, n, p.seasonType()) : "Final";
    }

    /** The label while the game is on; null otherwise. Alert texts use only this one (see position). */
    private static String livePeriodLabel(Play p) {
        if (!p.live() || p.period() <= 0) return null;
        int n = p.period();
        switch (p.league() == null ? "" : p.league()) {
            case "MLB": {
                String half = switch (p.half() == null ? "TOP" : p.half()) {
                    case "MIDDLE" -> "Mid";
                    case "BOTTOM" -> "Bot";
                    case "END" -> "End";
                    default -> "Top";
                };
                return half + " " + ordinal(n);
            }
            case "NHL": {
                String label = n <= 3 ? ordinal(n) : n == 4 ? "OT" : SportRules.isShootout(p) ? "SO" : (n - 3) + "OT";
                return endOf(p, label);
            }
            default:
                if ("HALFTIME".equals(p.stage())) return "Half";
                return endOf(p, n <= 4 ? "Q" + n : n == 5 ? "OT" : (n - 4) + "OT");
        }
    }

    /** Between periods the clock is gone, so {@code Q1} alone would read as if the quarter were still on. */
    private static String endOf(Play p, String label) {
        return "END_PERIOD".equals(p.stage()) ? "End " + label : label;
    }

    /**
     * One column of a line score: {@code Q1}…{@code Q4}, {@code OT}, {@code 2OT} (NFL, NBA);
     * {@code 1}, {@code 2}, {@code 3}, {@code OT}, {@code SO} or playoff {@code 2OT} (NHL); the inning (MLB).
     */
    public static String lineLabel(String league, int period, int seasonType) {
        switch (league == null ? "" : league) {
            case "MLB":
                return String.valueOf(period);
            case "NHL":
                if (period <= 3) return String.valueOf(period);
                if (period == 4) return "OT";
                return seasonType == 3 ? (period - 3) + "OT" : "SO";
            default:
                if (period <= 4) return "Q" + period;
                return period == 5 ? "OT" : (period - 4) + "OT";
        }
    }

    /** {@code 4:12}, and {@code 0:42} under a minute; null for MLB, between periods, or with no clock. */
    public static String clock(Play p) {
        if (!p.live() || "MLB".equals(p.league()) || p.betweenPeriods() || p.clockTenths() < 0) return null;
        int seconds = p.clockTenths() / 10;
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    /** "Red Sox"; the abbreviation for a team not in teams.json. */
    public String shortName(String league, String abbreviation) {
        return teams.find(TeamIds.of(league, abbreviation)).map(Teams.Team::shortName).orElse(abbreviation);
    }

    public String name(Play p, Side side) {
        return shortName(p.league(), p.team(side));
    }

    /** {@code Raiders 20, Bears 17 · Q4 4:12}: the leader first (away first when tied), then the position. */
    public String scoreLine(Play p) {
        String line = score(p);
        String position = position(p);
        return position == null ? line : line + " · " + position;
    }

    /** {@code Raiders 20, Bears 17}: the score alone, leader first (away first when tied). */
    public String score(Play p) {
        Side first = p.homeScore() > p.awayScore() ? Side.HOME : Side.AWAY;
        return name(p, first) + " " + p.score(first) + ", " + name(p, first.other()) + " " + p.score(first.other());
    }

    /** {@code Raiders at Bears}. */
    public String matchup(Play p) {
        return name(p, Side.AWAY) + " at " + name(p, Side.HOME);
    }

    /** {@code Q4 4:12}, {@code Half}; null when there is nothing to show. */
    public static String position(Play p) {
        String label = livePeriodLabel(p);
        String clock = clock(p);
        if (label == null) return clock;
        return clock == null ? label : label + " " + clock;
    }

    static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = (mod100 >= 11 && mod100 <= 13) ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }
}
