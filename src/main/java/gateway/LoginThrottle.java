package gateway;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.args.ExpiryOption;
import util.RedisKeys;

import java.util.Locale;

/**
 * Limits password guessing. Failed logins are counted in Redis per username and per client
 * address, each in a 15-minute window that starts at the first failure. While either count is
 * at its limit, the gateway answers 429 without checking the password.
 *
 * Tradeoff: someone who knows a username can lock it for 15 minutes on purpose.
 */
public final class LoginThrottle {

    public static final int USER_LIMIT = 5;
    public static final int IP_LIMIT = 20;
    public static final int WINDOW_SECONDS = 900;

    private static final String USER_KEY = RedisKeys.LOGIN_FAIL_PREFIX + "user:";
    private static final String IP_KEY = RedisKeys.LOGIN_FAIL_PREFIX + "ip:";

    private final JedisPool pool;

    public LoginThrottle(JedisPool pool) {
        this.pool = pool;
    }

    public boolean blocked(String usernameOrNull, String ip) {
        String user = normalize(usernameOrNull);
        try (Jedis jedis = pool.getResource()) {
            if (user != null && count(jedis.get(USER_KEY + user)) >= USER_LIMIT) return true;
            return ip != null && count(jedis.get(IP_KEY + ip)) >= IP_LIMIT;
        }
    }

    public void recordFailure(String usernameOrNull, String ip) {
        String user = normalize(usernameOrNull);
        try (Jedis jedis = pool.getResource()) {
            if (user != null) bump(jedis, USER_KEY + user);
            if (ip != null) bump(jedis, IP_KEY + ip);
        }
    }

    public void recordSuccess(String username) {
        String user = normalize(username);
        if (user == null) return;
        try (Jedis jedis = pool.getResource()) {
            jedis.del(USER_KEY + user);
        }
    }

    /**
     * INCR and EXPIRE NX in one MULTI/EXEC, so a counter can never exist without an expiry (which
     * would block that user forever). NX starts the window on the first failure and never
     * extends a window already running.
     */
    private static void bump(Jedis jedis, String key) {
        Transaction tx = jedis.multi();
        tx.incr(key);
        tx.expire(key, WINDOW_SECONDS, ExpiryOption.NX);
        tx.exec();
    }

    private static int count(String value) {
        return value == null ? 0 : Integer.parseInt(value);
    }

    /** Same normalization as signup, so "Bob" and " bob " share one count. */
    private static String normalize(String username) {
        if (username == null) return null;
        String u = username.trim().toLowerCase(Locale.ROOT);
        return u.isEmpty() ? null : u;
    }
}
