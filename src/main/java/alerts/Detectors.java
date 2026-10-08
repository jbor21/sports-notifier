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
        return new Detectors(List.of());
    }

    /** @param before the stored memory, or null when the game has none (the play becomes the baseline) */
    public Result run(GameMemory before, Play now) {
        if (before == null) {
            GameMemory after = new GameMemory();
            for (Detector d : chain) d.baseline(now, after);
            after.takePosition(now);
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
