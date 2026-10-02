package gateway;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.args.ExpiryOption;
import util.RedisKeys;

import java.util.List;
import java.util.Locale;

/**
 * Limits password guessing: 5 attempts per username and 20 per client address in a 15-minute
 * window that starts at the first attempt.
 *
 * Every attempt is counted BEFORE the password is checked. Checking first and counting the
 * failure afterwards is check-then-act: bcrypt takes tens of milliseconds, so 50 guesses sent
 * at once would all see "under the limit" and all get checked (measured: 50 of 50). Counting
 * first means the 6th concurrent guess is refused no matter how the requests interleave.
 * A successful login then clears the username's count and gives back the address's attempt.
 *
 * Tradeoff: someone who knows a username can lock it for 15 minutes on purpose.
 */
public final class LoginThrottle {

    public static final int USER_LIMIT = 5;
    public static final int IP_LIMIT = 20;
    public static final int WINDOW_SECONDS = 900;

    private static final String USER_KEY = RedisKeys.LOGIN_FAIL_PREFIX + "user:";
    private static final String IP_KEY = RedisKeys.LOGIN_FAIL_PREFIX + "ip:";

    /** DECR, and drop the key at zero so an expired-then-decremented key can't go negative forever. */
    private static final String GIVE_BACK =
        "local v = redis.call('DECR', KEYS[1])\n" +
        "if v <= 0 then redis.call('DEL', KEYS[1]) end\n" +
        "return v\n";

    private final JedisPool pool;

    public LoginThrottle(JedisPool pool) {
        this.pool = pool;
    }

    /**
     * Counts one login attempt and says whether it may go ahead. INCR and EXPIRE NX run in one
     * MULTI/EXEC, so a counter can never exist without its window (which would block forever).
     *
     * @return false if this attempt is over either limit (answer 429 without checking the password)
     */
    public boolean admit(String usernameOrNull, String ip) {
        String user = normalize(usernameOrNull);
        try (Jedis jedis = pool.getResource()) {
            Transaction tx = jedis.multi();
            Response<Long> userCount = user == null ? null : tx.incr(USER_KEY + user);
            if (user != null) tx.expire(USER_KEY + user, WINDOW_SECONDS, ExpiryOption.NX);
            Response<Long> ipCount = ip == null ? null : tx.incr(IP_KEY + ip);
            if (ip != null) tx.expire(IP_KEY + ip, WINDOW_SECONDS, ExpiryOption.NX);
            tx.exec();
            if (userCount != null && userCount.get() > USER_LIMIT) return false;
            return ipCount == null || ipCount.get() <= IP_LIMIT;
        }
    }

    /** The attempt succeeded: forget the username's failures and give back the address's attempt. */
    public void recordSuccess(String username, String ip) {
        String user = normalize(username);
        try (Jedis jedis = pool.getResource()) {
            if (user != null) jedis.del(USER_KEY + user);
            if (ip != null) jedis.eval(GIVE_BACK, List.of(IP_KEY + ip), List.of());
        }
    }

    /** Same normalization as signup, so "Bob" and " bob " share one count. */
    private static String normalize(String username) {
        if (username == null) return null;
        String u = username.trim().toLowerCase(Locale.ROOT);
        return u.isEmpty() ? null : u;
    }
}
