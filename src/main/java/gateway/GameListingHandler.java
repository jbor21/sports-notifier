package gateway;

import io.javalin.http.Context;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import util.RedisKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles the {@code GET /api/games} endpoint.
 * Reads the bounded {@code games:recent} set the Lua script maintains (no scan of every
 * {@code game:*} key), newest first. Scheduled games are left out; the UI gets those from
 * {@code /api/upcoming}. Ids whose record has expired are skipped.
 */
public final class GameListingHandler {

    private final JedisPool pool;

    public GameListingHandler(JedisPool pool) {
        this.pool = pool;
    }

    public void handleList(Context ctx) {
        ctx.json(listGames());
    }

    /** Each game's record, plus a {@code gameId} field. */
    public List<Map<String, String>> listGames() {
        try (Jedis jedis = pool.getResource()) {
            List<String> ids = new ArrayList<>(jedis.zrevrange(RedisKeys.GAMES_RECENT, 0, -1));
            Pipeline p = jedis.pipelined();
            List<Response<Map<String, String>>> records = new ArrayList<>();
            for (String id : ids) records.add(p.hgetAll(RedisKeys.GAME_PREFIX + id));
            p.sync();

            List<Map<String, String>> games = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                Map<String, String> state = records.get(i).get();
                if (state == null || state.isEmpty() || "SCHEDULED".equals(state.get("status"))) continue;
                state.put("gameId", ids.get(i));
                games.add(state);
            }
            return games;
        }
    }
}
