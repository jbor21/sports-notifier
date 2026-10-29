package service.impl;

import com.google.gson.JsonObject;
import feed.GameIds;
import games.GameQuery;
import games.GameSummaries;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import service.GameStateService;
import util.RedisKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class GameStateServiceImpl implements GameStateService {

    private final JedisPool pool;
    private final GameSummaries summaries;

    public GameStateServiceImpl(JedisPool pool, GameSummaries summaries) {
        this.pool = pool;
        this.summaries = summaries;
    }

    @Override
    public List<JsonObject> listDay(GameQuery q) {
        try (Jedis jedis = pool.getResource()) {
            // Redis orders equal scores by member, so this is start time, then id.
            // Replayed games are test runs on real teams (#47); real users never see them in the list.
            List<String> ids = jedis.zrangeByScore(RedisKeys.GAMES_START, String.valueOf(q.fromMillis()), "(" + q.toMillis())
                    .stream().filter(id -> !GameIds.isReplay(id)).toList();
            Pipeline p = jedis.pipelined();
            List<Response<Map<String, String>>> games = new ArrayList<>(), scheds = new ArrayList<>();
            for (String id : ids) {
                games.add(p.hgetAll(RedisKeys.GAME_PREFIX + id));
                scheds.add(p.hgetAll(RedisKeys.SCHED_PREFIX + id));
            }
            p.sync();
            List<JsonObject> out = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                Map<String, String> game = games.get(i).get(), sched = scheds.get(i).get();
                if (game.isEmpty() && sched.isEmpty()) continue;
                JsonObject s = summaries.summary(ids.get(i), game, sched);
                if (matches(s, q)) out.add(s);
            }
            return out;
        }
    }

    @Override
    public Optional<JsonObject> detail(String gameId) {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> game = jedis.hgetAll(RedisKeys.GAME_PREFIX + gameId);
            Map<String, String> sched = jedis.hgetAll(RedisKeys.SCHED_PREFIX + gameId);
            if (game.isEmpty() && sched.isEmpty()) return Optional.empty();
            return Optional.of(summaries.detail(gameId, game, sched));
        }
    }

    private static boolean matches(JsonObject s, GameQuery q) {
        if (q.league() != null && !q.league().equals(s.get("leagueId").getAsString())) return false;
        if (q.status() != null && !q.status().equals(s.get("status").getAsString())) return false;
        if (q.teamId() != null) {
            String home = s.getAsJsonObject("home").getAsJsonObject("team").get("id").getAsString();
            String away = s.getAsJsonObject("away").getAsJsonObject("team").get("id").getAsString();
            return q.teamId().equals(home) || q.teamId().equals(away);
        }
        return true;
    }
}
