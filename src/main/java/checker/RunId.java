package checker;

/** A run's id and the names it gives what it creates (Phase 4.1 spec, 1). */
public final class RunId {

    private RunId() {}

    /** The run's start in epoch seconds, base 36: six characters until the year 2059. */
    public static String of(long epochSeconds) {
        return Long.toString(epochSeconds, 36);
    }

    /** {@code ck_<runId>_<n>}: at most 20 characters of {@code [a-z0-9_]}, as signup requires. */
    public static String username(String runId, int n) {
        return "ck_" + runId + "_" + n;
    }

    /** What every replayed game id of the run contains: {@code -rp<runId>-}. */
    public static String gameMarker(String runId) {
        return "-rp" + runId + "-";
    }
}
