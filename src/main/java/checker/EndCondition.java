package checker;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * When a run is over (Phase 4.1 spec, 5): the feed is done, every game's record is final, and every
 * consumer group has had zero lag continuously for {@code steady}. Zero lag at one instant is not
 * enough: a worker between polls, or mid-rebalance, shows zero while records are on their way, and
 * Stage 2's last pub/sub messages still need a moment to reach the sockets.
 */
public final class EndCondition {

    public enum Kind { WAITING, DONE, TIMED_OUT }

    public record State(Kind kind, String reason) {}

    private final LongSupplier clock;
    private final BooleanSupplier feedDone;
    private final Supplier<List<String>> notFinal;
    private final Supplier<Map<String, Long>> lag;
    private final long steadyMillis;
    private final long timeoutMillis;
    private long feedDoneAt = -1;
    private long quietSince = -1;

    public EndCondition(LongSupplier clock, BooleanSupplier feedDone, Supplier<List<String>> notFinal,
                        Supplier<Map<String, Long>> lag, Duration steady, Duration timeout) {
        this.clock = clock;
        this.feedDone = feedDone;
        this.notFinal = notFinal;
        this.lag = lag;
        this.steadyMillis = steady.toMillis();
        this.timeoutMillis = timeout.toMillis();
    }

    /** Called about once a second. */
    public State poll() {
        long now = clock.getAsLong();
        if (!feedDone.getAsBoolean()) {
            quietSince = -1;
            return new State(Kind.WAITING, "replay still running");
        }
        if (feedDoneAt < 0) feedDoneAt = now;
        List<String> open = notFinal.get();
        Map<String, Long> lagging = new TreeMap<>();
        lag.get().forEach((k, v) -> { if (v > 0) lagging.put(k, v); });
        boolean quiet = open.isEmpty() && lagging.isEmpty();
        if (!quiet) {
            quietSince = -1;
        } else if (quietSince < 0) {
            quietSince = now;
        }
        if (quiet && now - quietSince >= steadyMillis) return new State(Kind.DONE, "");
        String reason = "not final: " + open + "; lagging: " + lagging
                + (quiet ? "; zero lag for " + (now - quietSince) / 1000 + " s" : "");
        if (now - feedDoneAt >= timeoutMillis) return new State(Kind.TIMED_OUT, reason);
        return new State(Kind.WAITING, reason);
    }
}
