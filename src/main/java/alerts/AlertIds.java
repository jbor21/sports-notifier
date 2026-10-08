package alerts;

/**
 * Alert ids (spec 2.4): {@code <gameId>:<TYPE>[:<what makes it distinct>]}. Built from the game and
 * the detectors' counters, never from the play's version: a moment re-detected on a later play (after
 * a Redis rollback) must keep its id so Stage 2's dedup drops it.
 */
public final class AlertIds {

    private AlertIds() {}

    /** n = the team's scoring-alerts-fired counter, which a reversal never lowers. */
    public static String score(String gameId, Side side, int n) {
        return gameId + ":SCORE:" + side.key() + ":" + n;
    }

    /** n = the touchdown's counter value. */
    public static String tryResult(String gameId, Side side, int n) {
        return gameId + ":TRY_RESULT:" + side.key() + ":" + n;
    }

    public static String tryLate(String gameId, Side side, int n) {
        return tryResult(gameId, side, n) + ":late";
    }

    public static String leadChange(String gameId, int n) {
        return gameId + ":LEAD_CHANGE:" + n;
    }

    public static String endOfPeriod(String gameId, int period) {
        return gameId + ":END_OF_PERIOD:" + period;
    }

    public static String endOfInning(String gameId, int inning) {
        return gameId + ":END_OF_INNING:" + inning;
    }

    public static String overtime(String gameId, int period) {
        return gameId + ":OVERTIME:" + period;
    }

    public static String delayed(String gameId, int n) {
        return gameId + ":DELAYED:" + n;
    }

    public static String resumed(String gameId, int n) {
        return gameId + ":RESUMED:" + n;
    }

    /** Once-per-game alerts: GAME_START, HALFTIME, CLOSE_LATE, SHOOTOUT, FINAL. */
    public static String once(String gameId, String type) {
        return gameId + ":" + type;
    }
}
