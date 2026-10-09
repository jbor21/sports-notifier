package checker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** When a run is over (Phase 4.1 spec, 5). */
class EndConditionTest {

    private final AtomicLong clock = new AtomicLong(0);
    private final AtomicBoolean feedDone = new AtomicBoolean(true);
    private final AtomicReference<List<String>> notFinal = new AtomicReference<>(List.of());
    private final AtomicReference<Map<String, Long>> lag = new AtomicReference<>(Map.of("stage1/game-events-0", 0L));
    private final EndCondition end = new EndCondition(clock::get, feedDone::get, notFinal::get, lag::get,
        Duration.ofSeconds(10), Duration.ofMinutes(5));

    /** Polls once a second for {@code seconds}, returning the last state. */
    private EndCondition.State run(int seconds) {
        EndCondition.State s = end.poll();
        for (int i = 0; i < seconds; i++) {
            clock.addAndGet(1000);
            s = end.poll();
        }
        return s;
    }

    @Test
    void zeroLagForNineSecondsIsNotDone() {
        assertEquals(EndCondition.Kind.WAITING, run(9).kind());
    }

    @Test
    void tenSecondsSteadyIsDone() {
        assertEquals(EndCondition.Kind.DONE, run(10).kind());
    }

    @Test
    void lagBlipResetsTheClock() {
        run(8);
        lag.set(Map.of("stage2/notifications-7", 3L));
        clock.addAndGet(1000);
        assertEquals(EndCondition.Kind.WAITING, end.poll().kind());
        lag.set(Map.of("stage2/notifications-7", 0L));
        assertEquals(EndCondition.Kind.WAITING, run(9).kind());
        assertEquals(EndCondition.Kind.DONE, run(1).kind());
    }

    @Test
    void notFinalHoldsIt() {
        notFinal.set(List.of("nhl-rpabc123-1"));
        assertEquals(EndCondition.Kind.WAITING, run(30).kind());
        notFinal.set(List.of());
        assertEquals(EndCondition.Kind.DONE, run(10).kind());
    }

    @Test
    void feedNotDoneHoldsIt() {
        feedDone.set(false);
        assertEquals(EndCondition.Kind.WAITING, run(600).kind(), "no timeout before the feed is done");
    }

    @Test
    void timeoutNamesGamesAndPartitions() {
        notFinal.set(List.of("nhl-rpabc123-1"));
        lag.set(Map.of("history/notifications-3", 12L));
        EndCondition.State s = run(300);
        assertEquals(EndCondition.Kind.TIMED_OUT, s.kind());
        assertTrue(s.reason().contains("nhl-rpabc123-1"), s.reason());
        assertTrue(s.reason().contains("history/notifications-3=12"), s.reason());
    }
}
