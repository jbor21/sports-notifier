package service.impl;

import com.google.gson.Gson;
import model.Notification;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import service.NotificationService;
import util.RedisKeys;

import java.util.ArrayList;
import java.util.List;

public class NotificationServiceImpl implements NotificationService {

    private final JedisPool pool;
    private final Gson gson;

    public NotificationServiceImpl(JedisPool pool) {
        this.pool = pool;
        this.gson = new Gson();
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
