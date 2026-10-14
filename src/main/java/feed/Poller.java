package feed;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import model.GameEvent;
import model.GameSnapshot;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import telemetry.Traces;
import util.RedisKeys;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
    /** Log de-duplication only, never correctness state: gameId -> event id last warned as stale. */
    private final Map<String, String> lastStaleWarning = new ConcurrentHashMap<>();

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

    /** How loudly to log one POST's outcome. */
    enum LogLevel { DEBUG, INFO, WARN, RETRY, ERROR }

    static LogLevel levelFor(String eventType, EventSink.PostResult r) {
        boolean sync = PollDecision.GAME_SYNC.equals(eventType);
        if (r.status() >= 200 && r.status() < 300) {
            if ("stale".equals(r.result())) {
                // An alert the script refused as older than the saved state. Usually one ESPN snapshot
                // got ahead of reality (a clock glitch, an early final) and became the floor: alerts
                // stop until the game passes it. Make that visible instead of logging "sent".
                return sync ? LogLevel.DEBUG : LogLevel.WARN;
            }
            // pending: the game's previous play is still on its way to the log; the next poll sends again.
            if ("duplicate".equals(r.result()) || "pending".equals(r.result()) || sync) return LogLevel.DEBUG;
            return LogLevel.INFO;
        }
        if (r.status() >= 400 && r.status() < 500) return LogLevel.ERROR;
        return LogLevel.RETRY;
    }

    /**
     * Each POST is the root span of the play's trace, {@code poll}, starting when the poller saw the
     * play ({@code detectedAt}, the event's timestamp), the same instant pipeline.latency counts from.
     */
    private void send(GameEvent event) throws Exception {
        Span span = Traces.startAt("poll", SpanKind.CLIENT, Context.root(), event.getTimestamp());
        EventSink.PostResult r;
        try (Scope scope = span.makeCurrent()) {
            r = sink.post(event);
            if (r.result() != null) span.setAttribute("poll.result", r.result());
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
        String what = event.getEventType() + " " + event.getEventId() + " :: " + event.getDescription();
        switch (levelFor(event.getEventType(), r)) {
            case DEBUG -> { if (DEBUG) System.out.println("[Poller] " + r.result() + " " + what); }
            case INFO -> System.out.println("[Poller] sent " + what);
            case WARN -> {
                // Once per event id: the same stale event is re-posted every poll until the game moves on.
                if (!event.getEventId().equals(lastStaleWarning.put(event.getGameId(), event.getEventId()))) {
                    System.err.println("[Poller] STALE alert, not delivered (saved state is further along): " + what);
                }
            }
            case ERROR -> System.err.println("[Poller] rejected as invalid (" + r.status()
                + "); re-sent each poll until the game changes: " + what);
            case RETRY -> System.err.println("[Poller] failed (" + r.status() + "), next poll retries: " + what);
        }
    }

    private static boolean wantsFastPolling(GameSnapshot snap, long now) {
        // A suspended game resumes another day: keep the league slow so it doesn't poll every 5 s all day.
        if (snap.phase() == GameSnapshot.Phase.LIVE) return !"SUSPENDED".equals(snap.status());
        return snap.phase() == GameSnapshot.Phase.PRE
            && "SCHEDULED".equals(snap.status())
            && snap.startTimeMillis() > 0
            && snap.startTimeMillis() - now <= SOON_MILLIS;
    }
}
