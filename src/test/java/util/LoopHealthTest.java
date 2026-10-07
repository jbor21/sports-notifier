package util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** A worker's /health: alive only while its loop thread runs and keeps coming around. */
class LoopHealthTest {

    private final AtomicLong now = new AtomicLong(1_000_000);

    @Test
    void healthyWhileTheLoopRunsAndBeats() throws Exception {
        LoopHealth health = new LoopHealth(now::get, 60_000);
        CountDownLatch stop = new CountDownLatch(1);
        Thread loop = new Thread(() -> { try { stop.await(); } catch (InterruptedException ignored) {} });
        loop.start();
        health.attach(loop);
        health.beat();
        now.addAndGet(59_999);
        assertTrue(health.isHealthy());
        stop.countDown();
        loop.join();
    }

    @Test
    void unhealthyWhenTheLoopThreadDied() throws Exception {
        LoopHealth health = new LoopHealth(now::get, 60_000);
        Thread loop = new Thread(() -> {});
        loop.start();
        health.attach(loop);
        loop.join();
        health.beat();
        assertFalse(health.isHealthy(), "a dead loop must fail the probe so Kubernetes restarts the pod");
    }

    @Test
    void unhealthyWhenTheLoopStopsComingAround() throws Exception {
        LoopHealth health = new LoopHealth(now::get, 60_000);
        CountDownLatch stop = new CountDownLatch(1);
        Thread loop = new Thread(() -> { try { stop.await(); } catch (InterruptedException ignored) {} });
        loop.start();
        health.attach(loop);
        health.beat();
        now.addAndGet(60_001);
        assertFalse(health.isHealthy(), "a loop stuck for a minute is as bad as a dead one");
        stop.countDown();
        loop.join();
    }

    @Test
    void unhealthyBeforeALoopIsAttached() {
        assertFalse(new LoopHealth(now::get, 60_000).isHealthy());
    }
}
