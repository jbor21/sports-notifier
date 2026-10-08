package alerts;

import java.util.ArrayList;
import java.util.List;

/**
 * End of period, halftime, end of inning (spec 3.6). Read from the period number as well as ESPN's
 * short END_PERIOD/HALFTIME status, because a poll can miss the status; one flag per period stops the
 * second sighting. When polls missed several period ends, only the latest alerts: "End of Q1" at
 * halftime is old news.
 */
public final class PeriodEndDetector implements Detector {

    private final GameLabels labels;

    public PeriodEndDetector(GameLabels labels) {
        this.labels = labels;
    }

    @Override
    public void detect(GameMemory before, Play now, GameMemory after, List<Alert> out) {
        if (!now.live()) return;
        int done = completed(now);
        for (int p = 1; p <= done; p++) {
            String flag = flag(now.league(), p);
            if (after.fired(flag)) continue;
            after.fire(flag);
            if (p == done) {
                Alert a = alertFor(now, p, after);
                if (a != null) out.add(a);
            }
        }
    }

    @Override
    public void baseline(Play now, GameMemory after) {
        if (!now.live()) return;
        for (int p = 1; p <= completed(now); p++) {
            after.fire(flag(now.league(), p));
            if (p == 2 && SportRules.hasHalftime(now.league())) after.fire("HALFTIME");
        }
    }

    /** The last period (or inning) that is over. */
    private static int completed(Play p) {
        if ("MLB".equals(p.league())) return "END".equals(p.half()) ? p.period() : p.period() - 1;
        // Halftime ends the 2nd quarter whatever the period says: ESPN can show HALFTIME with period 3
        // already (nfl-401872965), and reading that as "Q3 is over" sent End of Q3 at the half.
        if ("HALFTIME".equals(p.stage())) return 2;
        return p.betweenPeriods() ? p.period() : p.period() - 1;
    }

    private static String flag(String league, int p) {
        return ("MLB".equals(league) ? "END_OF_INNING:" : "END_OF_PERIOD:") + p;
    }

    private Alert alertFor(Play now, int p, GameMemory after) {
        String league = now.league();
        String score = labels.score(now);
        if ("MLB".equals(league)) {
            // The 9th never alerts: the game is over, or tied and extra innings take it. From the 10th
            // on, an inning that ends tied means another one ("every inning" followers only).
            boolean alerts = p <= 8 || (p >= 10 && now.homeScore() == now.awayScore());
            if (!alerts) return null;
            List<String> switches = new ArrayList<>(List.of("END_OF_INNING"));
            if (p <= 9) switches.add("END_OF_INNING:" + p);
            return new Alert(AlertIds.endOfInning(now.gameId(), p), "END_OF_INNING", List.copyOf(switches),
                Alert.P_PERIOD, null, "End of " + GameLabels.ordinal(p) + ": " + score);
        }
        if (p == 2 && SportRules.hasHalftime(league)) {
            after.fire("HALFTIME");
            return Alert.of(AlertIds.once(now.gameId(), "HALFTIME"), "HALFTIME", Alert.P_PERIOD, null, "Halftime: " + score);
        }
        if (!SportRules.endOfPeriodPeriods(league).contains(p)) return null;
        String name = "NHL".equals(league) ? GameLabels.ordinal(p) : "Q" + p;
        return Alert.of(AlertIds.endOfPeriod(now.gameId(), p), "END_OF_PERIOD", Alert.P_PERIOD, null,
            "End of " + name + ": " + score);
    }
}
