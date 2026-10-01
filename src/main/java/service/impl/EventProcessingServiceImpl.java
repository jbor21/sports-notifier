package service.impl;

import model.GameEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.XAddParams;
import service.EventProcessingService;
import service.GameStateService;
import util.GameEventParser;
import util.RedisKeys;

import java.util.List;
import java.util.Map;

public class EventProcessingServiceImpl implements EventProcessingService {

    private final GameStateService gameStateService;
    private final JedisPool pool;
    private static final long MAX_STREAM_LENGTH = 1000L;
    private static final long PROCESSED_TTL_SECONDS = 3600L;

    /**
     * Single atomic Lua script: staleness check + dedup claim + game state update.
     *
     * Redis executes Lua atomically — no other command can interleave between any of
     * these steps. This eliminates both the original TOCTOU (dedup claimed before
     * staleness confirmed) and the secondary race where separate HGET/HSET calls in
     * GameStateServiceImpl could be interleaved by a concurrent thread.
     *
     * KEYS[1] = game hash key  (e.g. "game:g1")
     * KEYS[2] = dedup key      (e.g. "processed:evt-1")
     *
     * ARGV[1] = timestamp       ARGV[2] = dedup TTL seconds
     * ARGV[3] = eventType       ARGV[4] = homeScore   ARGV[5] = awayScore
     * ARGV[6] = homeTeam        ARGV[7] = awayTeam    ARGV[8] = league
     * ARGV[9] = gameStatus
     *
     * Returns: -1 stale | 0 duplicate | 1 processed
     */
    private static final String PROCESS_EVENT_SCRIPT =
        "local lastSeen = redis.call('HGET', KEYS[1], 'lastSeenTimestamp')\n" +
        "local lastSeenTs = tonumber(lastSeen) or 0\n" +
        "local newTs = tonumber(ARGV[1])\n" +
        "if newTs <= lastSeenTs then return -1 end\n" +
        "local claimed = redis.call('SET', KEYS[2], '1', 'NX', 'EX', ARGV[2])\n" +
        "if claimed == false then return 0 end\n" +
        "redis.call('HSETNX', KEYS[1], 'homeScore', '0')\n" +
        "redis.call('HSETNX', KEYS[1], 'awayScore', '0')\n" +
        "redis.call('HSETNX', KEYS[1], 'status', 'UNKNOWN')\n" +
        "local t = ARGV[3]\n" +
        "if t == 'GAME_START' then\n" +
        "  redis.call('HSET', KEYS[1], 'homeTeam', ARGV[6], 'awayTeam', ARGV[7],\n" +
        "             'league', ARGV[8], 'status', 'IN_PROGRESS', 'homeScore', '0', 'awayScore', '0')\n" +
        "elseif t == 'SCORE_UPDATE' then\n" +
        "  redis.call('HSET', KEYS[1], 'homeScore', ARGV[4], 'awayScore', ARGV[5])\n" +
        "elseif t == 'GAME_END' then\n" +
        "  redis.call('HSET', KEYS[1], 'status', 'FINAL', 'homeScore', ARGV[4], 'awayScore', ARGV[5])\n" +
        "else\n" +
        "  if ARGV[9] ~= '' then redis.call('HSET', KEYS[1], 'status', ARGV[9]) end\n" +
        "end\n" +
        "redis.call('HSET', KEYS[1], 'lastSeenTimestamp', ARGV[1])\n" +
        "return 1\n";

    public EventProcessingServiceImpl(GameStateService gameStateService, JedisPool pool) {
        this.gameStateService = gameStateService;
        this.pool = pool;
    }

    @Override
    public void processEvent(GameEvent event) {
        if (event == null) return;
        if (isBlank(event.getGameId()) || isBlank(event.getEventId()) || isBlank(event.getEventType())) {
            System.err.println("[EventProcessingService] Rejected invalid event: missing gameId, eventId, or eventType");
            return;
        }

        try (Jedis jedis = pool.getResource()) {
            List<String> keys = List.of(
                RedisKeys.GAME_PREFIX + event.getGameId(),
                RedisKeys.PROCESSED_PREFIX + event.getEventId()
            );
            List<String> args = List.of(
                String.valueOf(event.getTimestamp()),
                String.valueOf(PROCESSED_TTL_SECONDS),
                event.getEventType(),
                String.valueOf(event.getHomeScore()),
                String.valueOf(event.getAwayScore()),
                event.getHomeTeam()   != null ? event.getHomeTeam()   : "",
                event.getAwayTeam()   != null ? event.getAwayTeam()   : "",
                event.getLeague()     != null ? event.getLeague()     : "",
                event.getGameStatus() != null ? event.getGameStatus() : ""
            );
            long result = ((Long) jedis.eval(PROCESS_EVENT_SCRIPT, keys, args));
            if (result == -1L) {
                System.err.println("[EventProcessingService] Rejected stale event: "
                    + event.getEventId() + " for game " + event.getGameId());
                return;
            }
            if (result == 0L) return; // duplicate — already processed
        }

        publishEvent(event);
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

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
