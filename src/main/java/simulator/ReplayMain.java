package simulator;

import feed.HttpEventSink;
import feed.Poller;
import feed.RecordedScoreFeed;
import redis.clients.jedis.JedisPool;
import util.JedisPoolHolder;

import java.time.Duration;
import java.util.List;

/**
 * Plays a recorded replay set through Event Processing, without the checker: for demos and for
 * watching a run in the UI ({@code make replay SET=evening SPEED=10}).
 */
public final class ReplayMain {

    private static final String EPS_URL = System.getenv().getOrDefault("EPS_URL", "http://127.0.0.1:8083");
    /** After the feed ends, how long the last plays get to be accepted before the run is called failed. */
    private static final long FINAL_WAIT_MILLIS = 5 * 60_000L;

    public static void main(String[] args) throws Exception {
        String set = System.getenv().getOrDefault("REPLAY_SET", "evening");
        double speed = Double.parseDouble(System.getenv().getOrDefault("REPLAY_SPEED", "10"));
        JedisPoolHolder.awaitRedisOrExit("replay");
        long now = System.currentTimeMillis();
        String runId = Long.toString(now / 1000, 36);
        try (JedisPool pool = JedisPoolHolder.create()) {
            RecordedScoreFeed feed = new RecordedScoreFeed(RecordedScoreFeed.load(set), runId, now, System::currentTimeMillis, speed);
            Replay replay = new Replay(feed, new Poller(feed, pool, new HttpEventSink(EPS_URL), System::currentTimeMillis),
                    pool, Replay.pollMillis(speed));
            System.out.println("[Replay] set " + set + " at " + speed + "x, run " + runId + ": " + feed.gameIds());
            replay.prime(Duration.ofSeconds(30));
            replay.start();
            while (!replay.feedDone()) Thread.sleep(1000);
            long giveUpAt = System.currentTimeMillis() + FINAL_WAIT_MILLIS;
            List<String> open = replay.notFinal();
            while (!open.isEmpty() && System.currentTimeMillis() < giveUpAt) {
                Thread.sleep(1000);
                open = replay.notFinal();
            }
            replay.stop();
            if (!open.isEmpty()) {
                System.err.println("[Replay] still not FINAL " + FINAL_WAIT_MILLIS / 1000 + "s after the feed ended: " + open);
                System.exit(1);
            }
            System.out.println("[Replay] every game is final.");
        }
    }
}
