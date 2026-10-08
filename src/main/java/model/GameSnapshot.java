package model;

import java.util.List;

/**
 * One game at one moment, as read from a score feed, independent of which feed it came from.
 *
 * @param gameId          our game id, e.g. {@code nfl-401872948} (see {@code feed.GameIds})
 * @param league          {@code NBA}, {@code NFL}, {@code MLB}, or {@code NHL}
 * @param homeTeam        home team abbreviation
 * @param awayTeam        away team abbreviation
 * @param homeScore       home score now
 * @param awayScore       away score now
 * @param phase           before, live, or final
 * @param period          quarter, period, or inning; overtime keeps counting
 * @param half            MLB half-inning; {@link Half#TOP} for other sports
 * @param clockTenths     tenths of a second left in the period; 0 for MLB; -1 if the feed sent no clock
 * @param outs            MLB outs in the half-inning (0–3); 0 for other sports
 * @param stage           in play, delayed, end of period, or halftime
 * @param status          normalized status: SCHEDULED, IN_PROGRESS, END_PERIOD, HALFTIME, DELAYED,
 *                        POSTPONED, SUSPENDED, FINAL, or CANCELED
 * @param startTimeMillis scheduled start, epoch milliseconds; 0 if unknown
 * @param seasonType      1 preseason, 2 regular season, 3 postseason (ESPN's season.type); 2 if unknown
 * @param detail          the feed's short status text, e.g. {@code Bot 4th}, {@code Final/SO}; null if none
 * @param lineScore       points per period so far, in order; empty if the feed sent none
 */
public record GameSnapshot(String gameId, String league, String homeTeam, String awayTeam,
                           int homeScore, int awayScore, Phase phase, int period, Half half,
                           int clockTenths, int outs, Stage stage, String status, long startTimeMillis,
                           int seasonType, String detail, List<LinePeriod> lineScore) {

    public GameSnapshot {
        lineScore = lineScore == null ? List.of() : List.copyOf(lineScore);
    }

    /** A snapshot with no line score. */
    public GameSnapshot(String gameId, String league, String homeTeam, String awayTeam,
                        int homeScore, int awayScore, Phase phase, int period, Half half,
                        int clockTenths, int outs, Stage stage, String status, long startTimeMillis,
                        int seasonType, String detail) {
        this(gameId, league, homeTeam, awayTeam, homeScore, awayScore, phase, period, half,
            clockTenths, outs, stage, status, startTimeMillis, seasonType, detail, List.of());
    }

    /** A regular-season snapshot with no status text. */
    public GameSnapshot(String gameId, String league, String homeTeam, String awayTeam,
                        int homeScore, int awayScore, Phase phase, int period, Half half,
                        int clockTenths, int outs, Stage stage, String status, long startTimeMillis) {
        this(gameId, league, homeTeam, awayTeam, homeScore, awayScore, phase, period, half,
            clockTenths, outs, stage, status, startTimeMillis, 2, null);
    }

    /** Digit = ordinal: 0 before, 1 live, 2 final. */
    public enum Phase { PRE, LIVE, FINAL }

    /** MLB half-inning. Digit = ordinal. */
    public enum Half { TOP, MIDDLE, BOTTOM, END }

    /** Digit = ordinal: 0 in play, 1 delayed, 2 end of period, 3 halftime. */
    public enum Stage { IN_PLAY, DELAYED, END_PERIOD, HALFTIME }
}
