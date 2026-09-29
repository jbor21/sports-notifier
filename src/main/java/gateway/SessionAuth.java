package gateway;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.websocket.WsContext;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import util.RedisKeys;

import java.util.UUID;

public final class SessionAuth {

    private static final String COOKIE_NAME = "sid";
    private static final long SESSION_TTL_SECONDS = 24 * 60 * 60;

    private SessionAuth() {}

    public static String createSession(JedisPool pool, Context ctx, String userId) {
        String sid = UUID.randomUUID().toString();
        try (Jedis jedis = pool.getResource()) {
            jedis.set(RedisKeys.SESSION_PREFIX + sid, userId, SetParams.setParams().ex(SESSION_TTL_SECONDS));
        }
        Cookie c = new Cookie(COOKIE_NAME, sid);
        c.setPath("/");
        c.setHttpOnly(true);
        c.setMaxAge((int) SESSION_TTL_SECONDS);
        ctx.cookie(c);
        return sid;
    }

    public static String currentUserId(JedisPool pool, Context ctx) {
        String sid = ctx.cookie(COOKIE_NAME);
        if (sid == null || sid.isEmpty()) return null;
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(RedisKeys.SESSION_PREFIX + sid);
        }
    }

    public static String currentUserId(JedisPool pool, WsContext ctx) {
        String sid = ctx.cookie(COOKIE_NAME);
        if (sid == null || sid.isEmpty()) return null;
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(RedisKeys.SESSION_PREFIX + sid);
        }
    }

    public static void destroySession(JedisPool pool, Context ctx) {
        String sid = ctx.cookie(COOKIE_NAME);
        if (sid != null && !sid.isEmpty()) {
            try (Jedis jedis = pool.getResource()) {
                jedis.del(RedisKeys.SESSION_PREFIX + sid);
            }
        }
        Cookie c = new Cookie(COOKIE_NAME, "");
        c.setPath("/");
        c.setHttpOnly(true);
        c.setMaxAge(0);
        ctx.cookie(c);
    }
}
