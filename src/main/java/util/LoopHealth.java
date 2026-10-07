package util;

import java.util.function.LongSupplier;

/**
 * Whether a worker's consumer loop is still doing its job, for the pod's /health. The HTTP server
 * runs on its own threads, so "the process answers" says nothing about the loop: with one Stage 1
 * replica, a loop that died or got stuck would stop every notification while the pod looked fine.
 *
 * Healthy means the loop thread is alive and has come around (beat) within maxSilence. A normal
 * pass takes about a second, including while Kafka or Redis is down (the loop keeps retrying), so a
 * minute of silence means it's stuck. /health then answers 503 and Kubernetes restarts the pod.
 */
public final class LoopHealth {

    private final LongSupplier nowMillis;
    private final long maxSilenceMillis;
    private volatile Thread loop;
    private volatile long lastBeat;

    public LoopHealth(LongSupplier nowMillis, long maxSilenceMillis) {
        this.nowMillis = nowMillis;
        this.maxSilenceMillis = maxSilenceMillis;
        this.lastBeat = nowMillis.getAsLong();
    }

    /** The thread that runs the loop. */
    public void attach(Thread loop) {
        this.loop = loop;
    }

    /** Called by the loop on every pass. */
    public void beat() {
        lastBeat = nowMillis.getAsLong();
    }

    public boolean isHealthy() {
        Thread t = loop;
        return t != null && t.isAlive() && nowMillis.getAsLong() - lastBeat <= maxSilenceMillis;
    }
}
