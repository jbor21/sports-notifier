package service.impl;

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
