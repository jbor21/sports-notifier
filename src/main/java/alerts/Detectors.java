package alerts;

import java.util.ArrayList;
import java.util.List;

/** The detector chain: one play in, the moment's alerts, the final, and the new memory out. */
public final class Detectors {

    /**
     * @param moment     every alert of this play except the final, headline order ({@link Alert#ORDER})
     * @param finalAlert the final, or null
     * @param after      the memory to store once the notifications are confirmed
     */
    public record Result(List<Alert> moment, Alert finalAlert, GameMemory after) {}

    private final List<Detector> chain;

    public Detectors(List<Detector> chain) {
        this.chain = List.copyOf(chain);
    }

    /** Every wave 1 detector, in the order they run. */
    public static Detectors standard(GameLabels labels) {
        return new Detectors(standardChain(labels));
    }

    /** The wave 1 detectors themselves, for a chain that adds to them (tests). */
    public static List<Detector> standardChain(GameLabels labels) {
        return List.of(
            new GameStartDetector(labels),
            new ScoreDetector(labels),
            new LeadChangeDetector(labels),
            new CloseLateDetector(labels),
            new PeriodEndDetector(labels),
            new OvertimeDetector(labels),
            new ShootoutDetector(labels),
            new DelayDetector(labels),
            new FinalDetector(labels));
    }

    private GameMemory baseline(Play now) {
        GameMemory after = new GameMemory();
        for (Detector d : chain) d.baseline(now, after);
        after.takePosition(now);
        return after;
    }

    /** @param before the stored memory, or null when the game has none (the play becomes the baseline) */
    public Result run(GameMemory before, Play now) {
        if (before == null) return new Result(List.of(), null, baseline(now));
        if ("PRE".equals(before.phase) && now.live() && now.period() > 1) {
            // Last seen before the game, now seen past its first period (the poller or Stage 1 was
            // down): what happened in between is not news any more. Start over from here, keeping
            // the counters so later ids stay unique.
            GameMemory after = baseline(now);
            after.keepCountersFrom(before);
            return new Result(List.of(), null, after);
        }
        GameMemory after = before.copy();
        List<Alert> out = new ArrayList<>();
        for (Detector d : chain) d.detect(before, now, after, out);
        Alert finalAlert = null;
        for (Detector d : chain) {
            Alert f = d.finalAlert(before, now, after);
            if (f != null && finalAlert == null) finalAlert = f;
        }
        after.takePosition(now);
        out.sort(Alert.ORDER);
        return new Result(List.copyOf(out), finalAlert, after);
    }
}
