package gateway;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Issue #10: a renewal already running must finish before the conn: entries are removed, or it puts them back. */
class GatewayShutdownTest {

    @Test
    void waitsForARunningRenewalBeforeRemoving() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch started = new CountDownLatch(1);
        renewals.schedule(() -> {
            order.add("renew-start");
            started.countDown();
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
            while (System.nanoTime() < end) Thread.onSpinWait();   // a Redis round trip: ignores the interrupt
            order.add("renew-end");
        }, 0, TimeUnit.MILLISECONDS);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        GatewayShutdown.run(List.of(renewals), Duration.ofSeconds(5), List.of(() -> order.add("removeAll")));
        assertEquals(List.of("renew-start", "renew-end", "removeAll"), order);
    }

    @Test
    void aFailingStepDoesNotStopTheRest() {
        List<String> order = new CopyOnWriteArrayList<>();
        GatewayShutdown.run(List.of(), Duration.ofSeconds(1), List.of(
            () -> order.add("one"),
            () -> { throw new IllegalStateException("redis gone"); },
            () -> order.add("three")));
        assertEquals(List.of("one", "three"), order);
    }
}
