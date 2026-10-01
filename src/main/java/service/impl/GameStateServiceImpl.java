package service.impl;

import model.GameEvent;
import model.GameState;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import service.GameStateService;
import util.RedisKeys;

import java.util.Map;

public class GameStateServiceImpl implements GameStateService {

    private final JedisPool pool;

    public GameStateServiceImpl(JedisPool pool) {
        this.pool = pool;
    }

    @Override
    public void updateGameState(GameEvent event) {
        try (Jedis jedis = pool.getResource()) {
            String key = RedisKeys.GAME_PREFIX + event.getGameId();

            String lastSeenStr = jedis.hget(key, "lastSeenTimestamp");
            long lastSeen = lastSeenStr != null ? Long.parseLong(lastSeenStr) : 0L;

            // Defense-in-depth: EventProcessingServiceImpl is the primary staleness gatekeeper.
            // This check guards against direct calls to updateGameState() that bypass EPS.
            if (event.getTimestamp() <= lastSeen) return;

            jedis.hsetnx(key, "homeScore", "0");
            jedis.hsetnx(key, "awayScore", "0");
            jedis.hsetnx(key, "status", "UNKNOWN");

            switch (event.getEventType()) {
                case "GAME_START":
                    jedis.hset(key, Map.of(
                        "homeTeam", event.getHomeTeam() != null ? event.getHomeTeam() : "",
                        "awayTeam", event.getAwayTeam() != null ? event.getAwayTeam() : "",
                        "league",   event.getLeague()   != null ? event.getLeague()   : "",
                        "status", "IN_PROGRESS",
                        "homeScore", "0",
                        "awayScore", "0"
                    ));
                    break;
                case "SCORE_UPDATE":
                    jedis.hset(key, Map.of(
                        "homeScore", String.valueOf(event.getHomeScore()),
                        "awayScore", String.valueOf(event.getAwayScore())
                    ));
                    break;
                case "GAME_END":
                    jedis.hset(key, Map.of(
                        "status", "FINAL",
                        "homeScore", String.valueOf(event.getHomeScore()),
                        "awayScore", String.valueOf(event.getAwayScore())
                    ));
                    break;
                default:
                    if (event.getGameStatus() != null) {
                        jedis.hset(key, "status", event.getGameStatus());
                    }
                    break;
            }

            jedis.hset(key, "lastSeenTimestamp", String.valueOf(event.getTimestamp()));
        }
    }

    @Override
    public GameState getGameState(String gameId) {
        try (Jedis jedis = pool.getResource()) {
            String key = RedisKeys.GAME_PREFIX + gameId;
            Map<String, String> fields = jedis.hgetAll(key);
            if (fields == null || fields.isEmpty()) return null;

            return new GameState(
                gameId,
                fields.getOrDefault("homeTeam", ""),
                fields.getOrDefault("awayTeam", ""),
                fields.getOrDefault("status", "UNKNOWN"),
                Integer.parseInt(fields.getOrDefault("homeScore", "0")),
                Integer.parseInt(fields.getOrDefault("awayScore", "0"))
            );
        }
    }
}
