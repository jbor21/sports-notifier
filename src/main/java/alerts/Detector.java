package alerts;

import java.util.List;

/**
 * One alert family. A pure function: it compares the memory from before this play with the play,
 * adds its alerts to {@code out}, and records what it needs to remember in {@code after}. No Redis,
 * no Kafka, no clock.
 */
public interface Detector {

    void detect(GameMemory before, Play now, GameMemory after, List<Alert> out);

    /**
     * The first play Stage 1 sees for a game: mark as fired whatever already happened, so a lost
     * memory can cost one moment but never re-sends old ones (spec 2.5).
     */
    default void baseline(Play now, GameMemory after) {}

    /** The final, which is never combined with the moment (spec 5.4). Only the final detector has one. */
    default Alert finalAlert(GameMemory before, Play now, GameMemory after) {
        return null;
    }
}
