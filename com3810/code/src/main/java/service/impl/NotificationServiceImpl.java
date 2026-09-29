package service.impl;

import com.google.gson.Gson;
import model.Notification;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import service.NotificationService;
import util.RedisKeys;

import java.util.ArrayList;
import java.util.List;

public class NotificationServiceImpl implements NotificationService {

    private final JedisPool pool;
    private final Gson gson;
    private static final int MAX_HISTORY_INDEX = 999; // LTRIM 0 999 retains 1000 entries (zero-based)

    public NotificationServiceImpl(JedisPool pool) {
        this.pool = pool;
        this.gson = new Gson();
    }

    @Override
    public void sendNotification(String userId, Notification notification) {
        String key = RedisKeys.NOTIFICATIONS_PREFIX + userId;
        String json = gson.toJson(new Notification(userId, notification.getGameId(),
            notification.getMessage(), notification.getTimestamp(),
            notification.getLeague(), notification.getHomeTeam(), notification.getAwayTeam()));
        try (Jedis jedis = pool.getResource()) {
            Pipeline p = jedis.pipelined();
            p.lpush(key, json);
            p.ltrim(key, 0, MAX_HISTORY_INDEX);
            p.publish(RedisKeys.NOTIFY_CHANNEL_PREFIX + userId, json);
            p.sync();
        }
    }

    @Override
    public void sendNotification(List<String> userIds, Notification notification) {
        if (userIds == null || userIds.isEmpty()) return;
        try (Jedis jedis = pool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (String userId : userIds) {
                String key = RedisKeys.NOTIFICATIONS_PREFIX + userId;
                String json = gson.toJson(new Notification(userId, notification.getGameId(),
                    notification.getMessage(), notification.getTimestamp(),
                    notification.getLeague(), notification.getHomeTeam(), notification.getAwayTeam()));
                pipeline.lpush(key, json);
                pipeline.ltrim(key, 0, MAX_HISTORY_INDEX);
                pipeline.publish(RedisKeys.NOTIFY_CHANNEL_PREFIX + userId, json);
            }
            pipeline.sync();
        }
    }

    @Override
    public List<Notification> getNotificationHistory(String userId, int limit) {
        String key = RedisKeys.NOTIFICATIONS_PREFIX + userId;
        try (Jedis jedis = pool.getResource()) {
            List<String> raw = jedis.lrange(key, 0, limit - 1);
            List<Notification> result = new ArrayList<>();
            for (String json : raw) {
                result.add(gson.fromJson(json, Notification.class));
            }
            return result;
        }
    }
}
