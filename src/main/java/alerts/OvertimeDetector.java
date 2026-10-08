package alerts;

import java.util.List;

/**
 * Overtime starts, and fires again for each further period (spec 3.8). MLB fires once, when the 10th
 * starts. An NHL shootout is not an overtime: it has its own alert.
 */
public final class OvertimeDetector implements Detector {

    private final GameLabels labels;

    public OvertimeDetector(GameLabels labels) {
        this.labels = labels;
    }

    @Override
    public void detect(GameMemory before, Play now, GameMemory after, List<Alert> out) {
        int current = current(now);
        if (current == 0) return;
        int regulation = SportRules.regulation(now.league());
        for (int p = regulation + 1; p <= current; p++) {
            String flag = "OVERTIME:" + p;
            if (after.fired(flag)) continue;
            after.fire(flag);
            if (p == current) {
                out.add(Alert.of(AlertIds.overtime(now.gameId(), p), "OVERTIME", Alert.P_OVERTIME, null,
                    name(now.league(), p - regulation) + ": " + labels.score(now)));
            }
        }
    }

    @Override
    public void baseline(Play now, GameMemory after) {
        int current = current(now);
        for (int p = SportRules.regulation(now.league()) + 1; p <= current; p++) after.fire("OVERTIME:" + p);
    }

    /** The overtime period that alerts now, or 0. MLB only ever counts the 10th. */
    private static int current(Play now) {
        if (!now.live() || now.period() <= SportRules.regulation(now.league()) || SportRules.isShootout(now)) return 0;
        return "MLB".equals(now.league()) ? 10 : now.period();
    }

    private static String name(String league, int n) {
        if ("MLB".equals(league)) return "Extra innings";
        return switch (n) {
            case 1 -> "Overtime";
            case 2 -> "Double overtime";
            case 3 -> "Triple overtime";
            default -> n + "OT";
        };
    }
}
