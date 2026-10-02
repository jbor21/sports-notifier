package feed;

import model.GameEvent;
import model.GameSnapshot;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.RedisKeys;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * One poll of one league: fetch the scoreboard, compare each game with its saved record, send
 * at most one event per game.
 *
 * The poller keeps no memory of its own. Its memory is the game record the Lua script writes
 * ({@code game:<id>}), and the script writes it only when an event is accepted. So:
 * - a poller restart loses nothing and re-sends nothing;
 * - a failed POST changes nothing, and the next poll sees the same difference and sends the
 *   same event again (same position, same seq, same id), which is the retry;
 * - two pollers running at once read the same record, and the script decides between them.
 */
public final class Poller {

    /** A scheduled game this close to its start (or past it, not yet live) keeps the fast rate. */
    public static final long SOON_MILLIS = 10 * 60 * 1000L;

    private static final boolean DEBUG = Boolean.parseBoolean(System.getenv().getOrDefault("POLLER_DEBUG", "false"));

    private final ScoreFeed feed;
    private final JedisPool pool;
    private final EventSink sink;
    private final LongSupplier clock;

    public Poller(ScoreFeed feed, JedisPool pool, EventSink sink, LongSupplier clock) {
        this.feed = feed;
        this.pool = pool;
        this.sink = sink;
        this.clock = clock;
    }

    /**
     * Polls one league once.
     *
     * @return true if the league should be polled at the live rate next time
     */
    public boolean pollLeague(String league) throws FeedBlockedException, IOException {
        List<GameSnapshot> games = feed.fetch(league);
        long now = clock.getAsLong();
        boolean fast = false;
        for (GameSnapshot snap : games) {
            fast |= wantsFastPolling(snap, now);
            try {
                Map<String, String> record;
                try (Jedis jedis = pool.getResource()) {
                    record = jedis.hgetAll(RedisKeys.GAME_PREFIX + snap.gameId());
                }
                Optional<GameEvent> event = PollDecision.decide(record, snap, now);
                if (event.isPresent()) send(event.get());
            } catch (Exception e) {
                // One game's trouble never stops the rest of the league.
                System.err.println("[Poller " + league + "] " + snap.gameId() + ": " + e);
            }
        }
        return fast;
    }

    private void send(GameEvent event) throws Exception {
        int status = sink.post(event);
        String what = event.getEventType() + " " + event.getEventId() + " :: " + event.getDescription();
        if (status >= 200 && status < 300) {
            // Duplicate and stale also answer 200; only worth seeing when debugging.
            if (DEBUG || !PollDecision.GAME_SYNC.equals(event.getEventType())) {
                System.out.println("[Poller] sent " + what);
            }
        } else if (status >= 400 && status < 500) {
            System.err.println("[Poller] rejected (" + status + "), not retrying: " + what);
        } else {
            System.err.println("[Poller] failed (" + status + "), next poll retries: " + what);
        }
    }

    private static boolean wantsFastPolling(GameSnapshot snap, long now) {
        if (snap.phase() == GameSnapshot.Phase.LIVE) return true;
        return snap.phase() == GameSnapshot.Phase.PRE
            && "SCHEDULED".equals(snap.status())
            && snap.startTimeMillis() > 0
            && snap.startTimeMillis() - now <= SOON_MILLIS;
    }
}
