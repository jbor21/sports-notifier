package service.impl;

import com.google.gson.JsonParseException;
import model.GameEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import service.EventProcessingService;
import service.PlayPublisher;
import service.ProcessResult;
import util.GameEventParser;
import util.RedisKeys;

import java.util.List;
import java.util.function.LongSupplier;

public class EventProcessingServiceImpl implements EventProcessingService {

    /** State-only event: the script saves it, nobody is notified. */
    public static final String GAME_SYNC = "GAME_SYNC";

    private final JedisPool pool;
    private final PlayPublisher publisher;
    private final LongSupplier nowMillis;
    private final Outbox outbox;
    private static final long PROCESSED_TTL_SECONDS = 3600L;
    /** A finished or canceled game's record lives a day after its last update. */
    private static final long FINISHED_GAME_TTL_SECONDS = 86_400L;
    /** Any other game's record, and its place in games:recent, lasts 8 days without an update. */
    private static final long IDLE_GAME_TTL_SECONDS = 8 * 86_400L;
    /** How long the same period and score must keep looking "older" before the no-score heal accepts it. */
    private static final long NO_SCORE_HEAL_MILLIS = 30_000L;
    /** Longer than Stage 1's 8-day memory of versions (Stage1Progress), refreshed on every accept. */
    private static final long VERSION_TTL_SECONDS = 9 * 86_400L;

    /**
     * Single atomic Lua script: duplicate check, pending check, ordering check, then claim, state
     * write, and outbox.
     *
     * Redis executes Lua atomically, so nothing runs between these steps. The order matters:
     *
     * 1. The event id is checked first, read-only. A retry of an event that was already accepted
     *    has the same seq, so checking seq first would call it "stale" and Phase 3's
     *    "claimed but not yet produced, so produce again" path (DESIGN.md decision 5) could never run.
     * 2. Then the outbox: if this game still has an accepted play the log has not confirmed, answer
     *    "pending" and change nothing; the poller sends again on its next poll. A game therefore has
     *    at most one unsent play, so its plays reach the log in the order they were accepted, even
     *    when a crashed pod's play is re-sent late (Stage 1 drops anything at or below the last
     *    version it handled, so a late older play would otherwise be lost). Enforced here rather
     *    than with a lock: a pod that stalls past a lock's timeout could still send an older play
     *    after a newer one, while Redis running one script at a time cannot be interleaved.
     * 3. Then seq: game state never goes back in game time. A stale event returns before the
     *    claim, so it never burns an id (the course's original TOCTOU fix). Two heals let an
     *    older-looking play through when ESPN's clock ran ahead of reality (seen: 4:01 left, then
     *    11:45), because the bad snapshot's seq would otherwise block the game for minutes:
     *    - goal heal: same phase, period, and half (seq's first four digits) and more points.
     *      An older snapshot never has more points, so a slower overlapping poller can't use this.
     *    - no-score heal: same phase, period, half, and score, still "older" after 30 seconds.
     *      staleSince records when that started; any accepted play clears it.
     *    Either heal lowers lastSeq to the play's seq. Both apply only to game positions (below
     *    10^14). A seq at or above that isn't a position (the course-era simulator sent such seqs to
     *    order its runs), so its leading digits mean nothing as a period.
     *    This check comes after "pending", so a play that will not be considered never starts
     *    the 30-second timer.
     * 4. Only then claim the id, add 1 to the game's version, and write the full state. Every
     *    event carries the whole state, so the script does not branch on event type. version
     *    changes in the same step as the score, so it counts accepted plays in exactly the order
     *    the score changed; everything after the script orders and dedups by it.
     * 5. Put the play in the game's outbox slot (a hash: version, play) and list the game in
     *    outbox:pending, scored by when it was accepted. The slot is cleared only when the log
     *    confirms the play (Outbox.clear), so a crash between here and the log loses nothing: the
     *    retry loop sends whatever is still waiting. Written in the same step as the score, so a
     *    Redis crash keeps or loses both together.
     * 6. Expire the record (a day after a final, 8 days idle otherwise) and note the game in
     *    games:recent, so the game list reads a bounded set instead of scanning every key.
     *
     * KEYS[1] = game hash key  (e.g. "game:g1")
     * KEYS[2] = dedup key      (e.g. "processed:evt-1")
     * KEYS[3] = games:recent
     * KEYS[4] = outbox slot    (e.g. "outbox:g1")
     * KEYS[5] = outbox:pending
     * KEYS[6] = version:<gameId>, the accept counter. Its own key, not a field of the game record:
     *           the record expires a day after a final, and a counter that restarted at 1 for a
     *           reused game id would be dropped by Stage 1, which remembers versions for 8 days.
     *
     * ARGV[1] = seq        ARGV[2] = dedup TTL seconds
     * ARGV[3] = homeTeam   ARGV[4] = awayTeam   ARGV[5] = league
     * ARGV[6] = homeScore  ARGV[7] = awayScore  ARGV[8] = status   ARGV[9] = timestamp
     * ARGV[10] = gameId    ARGV[11] = finished TTL seconds   ARGV[12] = idle TTL / recent window seconds
     * ARGV[13] = now millis (from Java, so tests can move time)   ARGV[14] = the play as JSON
     * ARGV[15] = no-score heal window millis   ARGV[16] = version key TTL seconds
     * ARGV[17..24] = phase, period, clockTenths, half, outs, stage, seasonType, detail (the position,
     *                saved with the score for the live game API)
     *
     * Returns: 0 duplicate | -1 stale | -2 pending | the game's new version when accepted
     *
     * A missing counter starts from now in milliseconds, not 0: Postgres outlives Redis, and history
     * is keyed by <gameId>:v<version>, so after a Redis reset (or the counter's TTL) a counter that
     * restarted at 1 would reuse old ids and history would drop the new notifications; an open UI
     * would also ignore the new versions as stale. Milliseconds (13 digits) stay exact through
     * Lua's number-to-text conversion, which keeps 14 significant digits.
     */
    private static final String PROCESS_EVENT_SCRIPT =
        "if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end\n" +
        "if redis.call('EXISTS', KEYS[4]) == 1 then return -2 end\n" +
        "local seq = tonumber(ARGV[1])\n" +
        "local lastSeq = tonumber(redis.call('HGET', KEYS[1], 'lastSeq')) or 0\n" +
        "if seq <= lastSeq then\n" +
        "  local heal = false\n" +
        "  if seq < lastSeq and lastSeq < 1e14 and math.floor(seq / 1e10) == math.floor(lastSeq / 1e10) then\n" +
        "    local home, away = tonumber(ARGV[6]), tonumber(ARGV[7])\n" +
        "    local savedHome = tonumber(redis.call('HGET', KEYS[1], 'homeScore')) or 0\n" +
        "    local savedAway = tonumber(redis.call('HGET', KEYS[1], 'awayScore')) or 0\n" +
        "    if home + away > savedHome + savedAway then\n" +
        "      heal = true\n" +
        "    elseif home == savedHome and away == savedAway then\n" +
        "      local since = tonumber(redis.call('HGET', KEYS[1], 'staleSince'))\n" +
        "      if not since then\n" +
        "        redis.call('HSET', KEYS[1], 'staleSince', ARGV[13])\n" +
        "      elseif tonumber(ARGV[13]) - since >= tonumber(ARGV[15]) then\n" +
        "        heal = true\n" +
        "      end\n" +
        "    end\n" +
        "  end\n" +
        "  if not heal then return -1 end\n" +
        "end\n" +
        "redis.call('SET', KEYS[2], '1', 'EX', ARGV[2])\n" +
        "if redis.call('EXISTS', KEYS[6]) == 0 then redis.call('SET', KEYS[6], ARGV[13]) end\n" +
        "local version = redis.call('INCR', KEYS[6])\n" +
        "redis.call('EXPIRE', KEYS[6], ARGV[16])\n" +
        "redis.call('HSET', KEYS[1], 'version', version)\n" +
        "redis.call('HDEL', KEYS[1], 'staleSince')\n" +
        "redis.call('HSET', KEYS[1],\n" +
        "  'homeTeam', ARGV[3], 'awayTeam', ARGV[4], 'league', ARGV[5],\n" +
        "  'homeScore', ARGV[6], 'awayScore', ARGV[7], 'status', ARGV[8],\n" +
        "  'lastSeq', ARGV[1], 'lastTimestamp', ARGV[9],\n" +
        "  'phase', ARGV[17], 'period', ARGV[18], 'clockTenths', ARGV[19], 'half', ARGV[20],\n" +
        "  'outs', ARGV[21], 'stage', ARGV[22], 'seasonType', ARGV[23], 'detail', ARGV[24])\n" +
        "if ARGV[8] == 'FINAL' or ARGV[8] == 'CANCELED' then\n" +
        "  redis.call('EXPIRE', KEYS[1], ARGV[11])\n" +
        "else\n" +
        "  redis.call('EXPIRE', KEYS[1], ARGV[12])\n" +
        "end\n" +
        "redis.call('HSET', KEYS[4], 'version', version, 'play', ARGV[14])\n" +
        "redis.call('ZADD', KEYS[5], ARGV[13], ARGV[10])\n" +
        "local now = tonumber(redis.call('TIME')[1])\n" +
        "redis.call('ZADD', KEYS[3], now, ARGV[10])\n" +
        "redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now - tonumber(ARGV[12]))\n" +
        "return version\n";

    public EventProcessingServiceImpl(JedisPool pool, PlayPublisher publisher, LongSupplier nowMillis) {
        this.pool = pool;
        this.publisher = publisher;
        this.nowMillis = nowMillis;
        this.outbox = new Outbox(pool);
    }

    @Override
    public ProcessResult processJson(String json) {
        GameEvent event;
        try {
            event = GameEventParser.parse(json);
        } catch (JsonParseException | NumberFormatException | IllegalStateException e) {
            return ProcessResult.INVALID;
        }
        return processEvent(event);
    }

    @Override
    public ProcessResult processEvent(GameEvent event) {
        if (event == null || isBlank(event.getGameId()) || isBlank(event.getEventId())
                || isBlank(event.getEventType()) || isBlank(event.getGameStatus()) || event.getSeq() <= 0) {
            System.err.println("[EventProcessingService] Rejected invalid event: needs gameId, eventId, eventType, gameStatus, seq > 0");
            return ProcessResult.INVALID;
        }

        long result;
        try (Jedis jedis = pool.getResource()) {
            List<String> keys = List.of(
                RedisKeys.GAME_PREFIX + event.getGameId(),
                RedisKeys.PROCESSED_PREFIX + event.getEventId(),
                RedisKeys.GAMES_RECENT,
                RedisKeys.OUTBOX_PREFIX + event.getGameId(),
                RedisKeys.OUTBOX_PENDING,
                RedisKeys.VERSION_PREFIX + event.getGameId()
            );
            List<String> args = List.of(
                String.valueOf(event.getSeq()),
                String.valueOf(PROCESSED_TTL_SECONDS),
                orEmpty(event.getHomeTeam()),
                orEmpty(event.getAwayTeam()),
                orEmpty(event.getLeague()),
                String.valueOf(event.getHomeScore()),
                String.valueOf(event.getAwayScore()),
                event.getGameStatus(),
                String.valueOf(event.getTimestamp()),
                event.getGameId(),
                String.valueOf(FINISHED_GAME_TTL_SECONDS),
                String.valueOf(IDLE_GAME_TTL_SECONDS),
                String.valueOf(nowMillis.getAsLong()),
                GameEventParser.toJson(event),
                String.valueOf(NO_SCORE_HEAL_MILLIS),
                String.valueOf(VERSION_TTL_SECONDS),
                orEmpty(event.getPhase()),
                String.valueOf(event.getPeriod()),
                String.valueOf(event.getClockTenths()),
                orEmpty(event.getHalf()),
                String.valueOf(event.getOuts()),
                orEmpty(event.getStage()),
                String.valueOf(event.getSeasonType()),
                orEmpty(event.getDetail())
            );
            result = (Long) jedis.eval(PROCESS_EVENT_SCRIPT, keys, args);
        }

        if (result == 0L) return ProcessResult.DUPLICATE;
        if (result == -2L) return ProcessResult.PENDING;
        if (result == -1L) {
            System.err.println("[EventProcessingService] Rejected stale event: "
                + event.getEventId() + " for game " + event.getGameId());
            return ProcessResult.STALE;
        }
        publish(event.withVersion(result));
        return ProcessResult.ACCEPTED;
    }

    /**
     * The caller gets its answer without waiting for the log: the play is already safe in the
     * outbox, and if the send fails the slot stays, so the retry loop sends it again. Every
     * accepted play goes out, GAME_SYNC included.
     */
    private void publish(GameEvent play) {
        outbox.send(publisher, play);
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
