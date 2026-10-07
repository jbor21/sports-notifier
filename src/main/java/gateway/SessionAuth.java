package gateway;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import io.javalin.websocket.WsContext;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import util.RedisKeys;

import java.util.UUID;

public final class SessionAuth {

    private static final String COOKIE_NAME = "sid";
    private static final long SESSION_TTL_SECONDS = 24 * 60 * 60;
    /** Off only on the laptop: some browsers (Safari) and tools refuse Secure cookies on plain http://localhost. */
    private static final boolean COOKIE_SECURE =
        Boolean.parseBoolean(System.getenv().getOrDefault("COOKIE_SECURE", "true"));

    private SessionAuth() {}

    public static String createSession(JedisPool pool, Context ctx, String userId) {
        String sid = UUID.randomUUID().toString();
        try (Jedis jedis = pool.getResource()) {
            jedis.set(RedisKeys.SESSION_PREFIX + sid, userId, SetParams.setParams().ex(SESSION_TTL_SECONDS));
        }
        ctx.cookie(sessionCookie(sid, (int) SESSION_TTL_SECONDS, COOKIE_SECURE));
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
        ctx.cookie(sessionCookie("", 0, COOKIE_SECURE));
    }

    /**
     * HttpOnly: page scripts can't read it. SameSite=Lax: other sites can't make the browser send it
     * on their requests, but following a link to the app keeps you logged in (Strict would not).
     * Secure: sent over HTTPS only.
     */
    static Cookie sessionCookie(String sid, int maxAgeSeconds, boolean secure) {
        Cookie c = new Cookie(COOKIE_NAME, sid);
        c.setPath("/");
        c.setHttpOnly(true);
        c.setMaxAge(maxAgeSeconds);
        c.setSecure(secure);
        c.setSameSite(SameSite.LAX);
        return c;
    }
}
