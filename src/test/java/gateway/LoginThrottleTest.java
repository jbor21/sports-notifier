package gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class LoginThrottleTest {

    private JedisPool pool;
    private Jedis jedis;
    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        throttle = new LoginThrottle(pool);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    /** The gateway's flow: count the attempt, then the slow password check (which fails here). */
    private boolean attempt(String user, String ip) throws InterruptedException {
        if (!throttle.admit(user, ip)) return false;
        Thread.sleep(30);   // bcrypt
        return true;
    }

    @Test
    void concurrentGuessesCannotExceedTheLimit() throws Exception {
        // 50 wrong passwords for one user at the same instant. At most 5 may reach the password check.
        // Counting after the check (the first version) let all 50 through.
        ExecutorService threads = Executors.newFixedThreadPool(50);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                final String ip = "10.0.0." + i;
                results.add(threads.submit(() -> { gate.await(); return attempt("bob", ip); }));
            }
            gate.countDown();
            int reachedPasswordCheck = 0;
            for (Future<Boolean> r : results) if (r.get(10, TimeUnit.SECONDS)) reachedPasswordCheck++;
            assertEquals(LoginThrottle.USER_LIMIT, reachedPasswordCheck);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void sixthAttemptOnUsernameBlocked() {
        for (int i = 0; i < 5; i++) assertTrue(throttle.admit("bob", "1.1.1." + i));
        assertFalse(throttle.admit("bob", "2.2.2.2"));
    }

    @Test
    void twentyFirstFromAddressBlocked() {
        for (int i = 0; i < 20; i++) assertTrue(throttle.admit("user" + i, "9.9.9.9"));
        assertFalse(throttle.admit("someone-new", "9.9.9.9"));
        assertTrue(throttle.admit("someone-new", "8.8.8.8"));
    }

    @Test
    void spoofedForwardedHeaderDoesNotHelp() {
        // Every attempt claims a different made-up client address in the first X-Forwarded-For entry.
        for (int i = 0; i < 20; i++) {
            String fake = ThreadLocalRandom.current().nextInt(256) + ".0.0." + i;
            throttle.admit("user" + i, ClientAddress.of("9.9.9.9", fake + ", 9.9.9.9", "10.0.0.5"));
        }
        String nextIp = ClientAddress.of("9.9.9.9", "7.7.7.7, 9.9.9.9", "10.0.0.5");
        assertFalse(throttle.admit("another", nextIp));
    }

    @Test
    void successResetsUsernameCount() {
        for (int i = 0; i < 4; i++) throttle.admit("bob", "1.1.1.1");
        throttle.recordSuccess("bob", "1.1.1.1");
        for (int i = 0; i < 5; i++) assertTrue(throttle.admit("bob", "1.1.1.1"));
    }

    @Test
    void successGivesBackTheAddressAttempt() {
        // A shared address with many successful logins must never hit the address limit.
        for (int i = 0; i < 30; i++) {
            assertTrue(throttle.admit("user" + i, "9.9.9.9"), "attempt " + i);
            throttle.recordSuccess("user" + i, "9.9.9.9");
        }
        assertFalse(jedis.exists("login:fail:ip:9.9.9.9"));
    }

    @Test
    void windowIsFifteenMinutes() {
        throttle.admit("bob", "1.1.1.1");
        long userTtl = jedis.ttl("login:fail:user:bob");
        long ipTtl = jedis.ttl("login:fail:ip:1.1.1.1");
        assertTrue(userTtl > 890 && userTtl <= 900, "user ttl " + userTtl);
        assertTrue(ipTtl > 890 && ipTtl <= 900, "ip ttl " + ipTtl);
    }

    @Test
    void missingUsernameStillCountsAddress() {
        for (int i = 0; i < 20; i++) throttle.admit(null, "9.9.9.9");
        assertFalse(throttle.admit(null, "9.9.9.9"));
        assertTrue(jedis.keys("login:fail:user:*").isEmpty());
    }

    @Test
    void usernameCaseDoesNotDodgeTheLimit() {
        for (int i = 0; i < 5; i++) throttle.admit(i % 2 == 0 ? "Bob" : " bob ", "1.1.1." + i);
        assertFalse(throttle.admit("BOB", "2.2.2.2"));
    }
}
