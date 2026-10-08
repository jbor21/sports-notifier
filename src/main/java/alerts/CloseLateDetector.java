package alerts;

import java.util.List;

/**
 * Close game late (spec 3.7): once per game, never in overtime. The clock sports need the late
 * condition on this play and the one before: a single snapshot with a wrong clock (the recordings
 * show 0:00 mid-period) must not start it. That costs one poll.
 */
public final class CloseLateDetector implements Detector {

    static final String FLAG = "CLOSE_LATE";

    private final GameLabels labels;

    public CloseLateDetector(GameLabels labels) {
        this.labels = labels;
    }

    @Override
    public void detect(GameMemory before, Play now, GameMemory after, List<Alert> out) {
        if (after.fired(FLAG) || !holds(now)) return;
        if (SportRules.hasClock(now.league()) && !before.wasLate) return;
        after.fire(FLAG);
        out.add(Alert.of(AlertIds.once(now.gameId(), FLAG), FLAG, Alert.P_CLOSE, null, "Close game late: " + labels.scoreLine(now)));
    }

    @Override
    public void baseline(Play now, GameMemory after) {
        if (holds(now)) after.fire(FLAG);
    }

    private static boolean holds(Play p) {
        return SportRules.isLate(p) && Math.abs(p.homeScore() - p.awayScore()) <= SportRules.closeGap(p.league());
    }
}
