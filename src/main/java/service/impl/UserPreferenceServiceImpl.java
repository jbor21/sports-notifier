package service.impl;

import com.google.gson.Gson;
import model.UserPreferences;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import service.UserPreferenceService;
import util.RedisKeys;

public class UserPreferenceServiceImpl implements UserPreferenceService {

    private final JedisPool pool;
    private final Gson gson;

    public UserPreferenceServiceImpl(JedisPool pool) {
        this.pool = pool;
        this.gson = new Gson();
    }

    @Override
    public void savePreferences(String userId, UserPreferences preferences) {
        try (Jedis jedis = pool.getResource()) {
            String existing = jedis.get(RedisKeys.PREFS_PREFIX + userId);
            Pipeline p = jedis.pipelined();
            if (existing != null) {
                UserPreferences oldPrefs = gson.fromJson(existing, UserPreferences.class);
                removeFromCache(p, userId, oldPrefs);
            }
            p.set(RedisKeys.PREFS_PREFIX + userId, gson.toJson(preferences));
            addToCache(p, userId, preferences);
            p.sync();
        }
    }

    @Override
    public UserPreferences getPreferences(String userId) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.get(RedisKeys.PREFS_PREFIX + userId);
            if (json == null) return null;
            return gson.fromJson(json, UserPreferences.class);
        }
    }

    @Override
    public void updateSubscriptionCache(String userId, UserPreferences preferences) {
        try (Jedis jedis = pool.getResource()) {
            Pipeline p = jedis.pipelined();
            addToCache(p, userId, preferences);
            p.sync();
        }
    }

    private void removeFromCache(Pipeline p, String userId, UserPreferences prefs) {
        if (prefs == null) return;
        if (prefs.getFollowedTeams() != null) {
            for (String team : prefs.getFollowedTeams()) p.srem(RedisKeys.SUB_TEAM_PREFIX + team, userId);
        }
        if (prefs.getFollowedGames() != null) {
            for (String gameId : prefs.getFollowedGames()) p.srem(RedisKeys.SUB_GAME_PREFIX + gameId, userId);
        }
        if (prefs.getFollowedLeagues() != null) {
            for (String league : prefs.getFollowedLeagues()) p.srem(RedisKeys.SUB_LEAGUE_PREFIX + league, userId);
        }
        if (prefs.getNotificationTypes() != null) {
            for (String type : prefs.getNotificationTypes()) p.srem(RedisKeys.SUB_NOTIF_PREFIX + type, userId);
        }
    }

    private void addToCache(Pipeline p, String userId, UserPreferences prefs) {
        if (prefs == null) return;
        if (prefs.getFollowedTeams() != null) {
            for (String team : prefs.getFollowedTeams()) p.sadd(RedisKeys.SUB_TEAM_PREFIX + team, userId);
        }
        if (prefs.getFollowedGames() != null) {
            for (String gameId : prefs.getFollowedGames()) p.sadd(RedisKeys.SUB_GAME_PREFIX + gameId, userId);
        }
        if (prefs.getFollowedLeagues() != null) {
            for (String league : prefs.getFollowedLeagues()) p.sadd(RedisKeys.SUB_LEAGUE_PREFIX + league, userId);
        }
        if (prefs.getNotificationTypes() != null) {
            for (String type : prefs.getNotificationTypes()) p.sadd(RedisKeys.SUB_NOTIF_PREFIX + type, userId);
        }
    }
}
