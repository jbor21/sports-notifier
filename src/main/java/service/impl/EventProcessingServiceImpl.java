package service.impl;

import com.google.gson.JsonParseException;
import model.GameEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.XAddParams;
import service.EventProcessingService;
import service.ProcessResult;
import util.GameEventParser;
import util.RedisKeys;

import java.util.List;
import java.util.Map;

public class EventProcessingServiceImpl implements EventProcessingService {

    /** State-only event: the script saves it, nobody is notified. */
    public static final String GAME_SYNC = "GAME_SYNC";

    private final JedisPool pool;
    private static final long MAX_STREAM_LENGTH = 1000L;
    private static final long PROCESSED_TTL_SECONDS = 3600L;
    /** A finished or canceled game's record lives a day after its last update. */
    private static final long FINISHED_GAME_TTL_SECONDS = 86_400L;
    /** Any other game's record, and its place in games:recent, lasts 8 days without an update. */
    private static final long IDLE_GAME_TTL_SECONDS = 8 * 86_400L;

    /**
     * Single atomic Lua script: duplicate check, ordering check, then claim + state write.
     *
     * Redis executes Lua atomically, so nothing runs between these steps. The order matters:
     *
     * 1. The event id is checked first, read-only. A retry of an event that was already accepted
     *    has the same seq, so checking seq first would call it "stale" and Phase 3's
     *    "claimed but not yet produced, so produce again" path (DESIGN.md decision 5) could never run.
     * 2. Then seq: game state never goes back in game time. A stale event returns before the
     *    claim, so it never burns an id (the course's original TOCTOU fix).
     * 3. Only then claim the id and write the full state. Every event carries the whole state,
     *    so the script does not branch on event type.
     * 4. Expire the record (a day after a final, 8 days idle otherwise) and note the game in
     *    games:recent, so the game list reads a bounded set instead of scanning every key.
     *
     * KEYS[1] = game hash key  (e.g. "game:g1")
     * KEYS[2] = dedup key      (e.g. "processed:evt-1")
     * KEYS[3] = games:recent
     *
     * ARGV[1] = seq        ARGV[2] = dedup TTL seconds
     * ARGV[3] = homeTeam   ARGV[4] = awayTeam   ARGV[5] = league
     * ARGV[6] = homeScore  ARGV[7] = awayScore  ARGV[8] = status   ARGV[9] = timestamp
     * ARGV[10] = gameId    ARGV[11] = finished TTL seconds   ARGV[12] = idle TTL / recent window seconds
     *
     * Returns: 0 duplicate | -1 stale | 1 accepted
     */
    private static final String PROCESS_EVENT_SCRIPT =
        "if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end\n" +
        "local lastSeq = tonumber(redis.call('HGET', KEYS[1], 'lastSeq')) or 0\n" +
        "if tonumber(ARGV[1]) <= lastSeq then return -1 end\n" +
        "redis.call('SET', KEYS[2], '1', 'EX', ARGV[2])\n" +
        "redis.call('HSET', KEYS[1],\n" +
        "  'homeTeam', ARGV[3], 'awayTeam', ARGV[4], 'league', ARGV[5],\n" +
        "  'homeScore', ARGV[6], 'awayScore', ARGV[7], 'status', ARGV[8],\n" +
        "  'lastSeq', ARGV[1], 'lastTimestamp', ARGV[9])\n" +
        "if ARGV[8] == 'FINAL' or ARGV[8] == 'CANCELED' then\n" +
        "  redis.call('EXPIRE', KEYS[1], ARGV[11])\n" +
        "else\n" +
        "  redis.call('EXPIRE', KEYS[1], ARGV[12])\n" +
        "end\n" +
        "local now = tonumber(redis.call('TIME')[1])\n" +
        "redis.call('ZADD', KEYS[3], now, ARGV[10])\n" +
        "redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now - tonumber(ARGV[12]))\n" +
        "return 1\n";

    public EventProcessingServiceImpl(JedisPool pool) {
        this.pool = pool;
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
                RedisKeys.GAMES_RECENT
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
                String.valueOf(IDLE_GAME_TTL_SECONDS)
            );
            result = (Long) jedis.eval(PROCESS_EVENT_SCRIPT, keys, args);
        }

        if (result == 0L) return ProcessResult.DUPLICATE;
        if (result == -1L) {
            System.err.println("[EventProcessingService] Rejected stale event: "
                + event.getEventId() + " for game " + event.getGameId());
            return ProcessResult.STALE;
        }
        if (!GAME_SYNC.equals(event.getEventType())) publishEvent(event);
        return ProcessResult.ACCEPTED;
    }

    @Override
    public void publishEvent(GameEvent event) {
        String json = GameEventParser.toJson(event);
        XAddParams params = XAddParams.xAddParams()
            .maxLen(MAX_STREAM_LENGTH)
            .approximateTrimming();
        try (Jedis jedis = pool.getResource()) {
            jedis.xadd(RedisKeys.GAME_EVENTS_STREAM, params, Map.of("data", json));
        }
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
