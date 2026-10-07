package model;

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
 * @param clockTenths     tenths of a second left in the period; 0 for MLB
 * @param outs            MLB outs in the half-inning (0–3); 0 for other sports
 * @param stage           in play, delayed, end of period, or halftime
 * @param status          normalized status: SCHEDULED, IN_PROGRESS, END_PERIOD, HALFTIME, DELAYED,
 *                        POSTPONED, SUSPENDED, FINAL, or CANCELED
 * @param startTimeMillis scheduled start, epoch milliseconds; 0 if unknown
 */
public record GameSnapshot(String gameId, String league, String homeTeam, String awayTeam,
                           int homeScore, int awayScore, Phase phase, int period, Half half,
                           int clockTenths, int outs, Stage stage, String status, long startTimeMillis) {

    /** Digit = ordinal: 0 before, 1 live, 2 final. */
    public enum Phase { PRE, LIVE, FINAL }

    /** MLB half-inning. Digit = ordinal. */
    public enum Half { TOP, MIDDLE, BOTTOM, END }

    /** Digit = ordinal: 0 in play, 1 delayed, 2 end of period, 3 halftime. */
    public enum Stage { IN_PLAY, DELAYED, END_PERIOD, HALFTIME }
}
