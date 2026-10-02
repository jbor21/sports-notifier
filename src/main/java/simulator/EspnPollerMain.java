package simulator;

import feed.EspnClient;
import feed.EspnScoreFeed;
import feed.FeedBlockedException;
import feed.HttpEventSink;
import feed.Poller;
import redis.clients.jedis.JedisPool;
import util.JedisPoolHolder;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls ESPN's scoreboard for NBA, NFL, MLB, and NHL and forwards changes to Event Processing.
 * All the decisions live in {@link Poller}; this class only schedules it.
 *
 * Each league runs on its own: every 5 s while it has a live game or one about to start,
 * every 60 s otherwise. If ESPN refuses us (403/429) the wait doubles, up to 5 minutes,
 * and goes back to normal on the next success.
 */
public final class EspnPollerMain {

    private static final String EPS_URL =
        System.getenv().getOrDefault("EPS_URL", "http://127.0.0.1:8083");
    private static final long LIVE_MS =
        Long.parseLong(System.getenv().getOrDefault("ESPN_POLL_LIVE_MS", "5000"));
    private static final long IDLE_MS =
        Long.parseLong(System.getenv().getOrDefault("ESPN_POLL_IDLE_MS", "60000"));
    private static final long MAX_BACKOFF_MS = 5 * 60 * 1000L;

    private static final String[] LEAGUES = {"NBA", "NFL", "MLB", "NHL"};

    public static void main(String[] args) throws InterruptedException {
        EspnClient espn = EspnClient.fromEnv();
        JedisPool pool = JedisPoolHolder.create();
        Poller poller = new Poller(new EspnScoreFeed(espn), pool, new HttpEventSink(EPS_URL),
            System::currentTimeMillis);

        System.out.println("[ESPN Poller] live " + LIVE_MS + "ms, idle " + IDLE_MS + "ms, EPS " + EPS_URL);

        for (int i = 0; i < LEAGUES.length; i++) {
            ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
            // Stagger the first polls by 2 s per league so we don't hit ESPN all at once.
            new LeagueLoop(LEAGUES[i], poller, exec).schedule(i * 2000L);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(pool::close));
        Thread.currentThread().join(); // run until killed
    }

    /** Polls one league, then schedules its own next run. */
    private static final class LeagueLoop {
        private final String league;
        private final Poller poller;
        private final ScheduledExecutorService exec;
        private long delay = IDLE_MS;

        LeagueLoop(String league, Poller poller, ScheduledExecutorService exec) {
            this.league = league;
            this.poller = poller;
            this.exec = exec;
        }

        void schedule(long ms) {
            exec.schedule(this::run, ms, TimeUnit.MILLISECONDS);
        }

        private void run() {
            try {
                delay = poller.pollLeague(league) ? LIVE_MS : IDLE_MS;
            } catch (FeedBlockedException e) {
                delay = Math.min(Math.max(delay, IDLE_MS) * 2, MAX_BACKOFF_MS);
                System.err.println("[ESPN Poller " + league + "] blocked: " + e.getMessage()
                    + "; next try in " + delay / 1000 + "s");
            } catch (Exception e) {
                System.err.println("[ESPN Poller " + league + "] poll error: " + e);
            } finally {
                schedule(delay);
            }
        }
    }
}
