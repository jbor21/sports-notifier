package gateway;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The gateway's shutdown, in an order that holds (issue #10): first stop the background loops and
 * wait for a running one to finish, then run the steps. A conn: renewal still running when the
 * entries are removed would put every one of them back, and Stage 2 would keep publishing to a
 * gateway that is gone for up to 90 s.
 */
public final class GatewayShutdown {

    private GatewayShutdown() {}

    /** Each step runs even if an earlier one failed; a failure is logged. */
    public static void run(List<? extends ExecutorService> loops, Duration wait, List<Runnable> steps) {
        for (ExecutorService loop : loops) loop.shutdownNow();
        for (ExecutorService loop : loops) {
            try {
                if (!loop.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                    System.err.println("[Gateway] a background loop did not stop within " + wait.toSeconds() + " s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            try {
                steps.get(i).run();
            } catch (RuntimeException e) {
                System.err.println("[Gateway] shutdown step " + (i + 1) + " failed: " + e.getMessage());
            }
        }
    }
}
