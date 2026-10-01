package gateway;

import io.javalin.http.Context;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import util.RedisKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles the {@code GET /api/games} endpoint.
 * Iterates all {@code game:*} Redis Hash keys via SCAN (cursor-based, memory-safe)
 * and returns the full state of every active game as a JSON array.
 */
public final class GameListingHandler {

    private final JedisPool pool;

    public GameListingHandler(JedisPool pool) {
        this.pool = pool;
    }

    /**
     * Scans all {@code game:*} keys and returns their field maps, each augmented
     * with a top-level {@code gameId} field derived from the key name.
     *
     * @param ctx Javalin request context; response is written as JSON.
     */
    public void handleList(Context ctx) {
        try (Jedis jedis = pool.getResource()) {
            List<Map<String, String>> games = new ArrayList<>();
            String cursor = "0";
            do {
                ScanParams params = new ScanParams()
                    .match(RedisKeys.GAME_PREFIX + "*")
                    .count(100);
                ScanResult<String> result = jedis.scan(cursor, params);
                cursor = result.getCursor();
                for (String key : result.getResult()) {
                    Map<String, String> state = jedis.hgetAll(key);
                    if (state != null && !state.isEmpty()) {
                        state.put("gameId", key.substring(RedisKeys.GAME_PREFIX.length()));
                        games.add(state);
                    }
                }
            } while (!cursor.equals("0"));
            ctx.json(games);
        }
    }
}
