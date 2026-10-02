package gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.concurrent.ThreadLocalRandom;

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

    @Test
    void sixthAttemptOnUsernameBlocked() {
        for (int i = 0; i < 4; i++) throttle.recordFailure("bob", "1.1.1." + i);
        assertFalse(throttle.blocked("bob", "2.2.2.2"));
        throttle.recordFailure("bob", "1.1.1.9");
        assertTrue(throttle.blocked("bob", "2.2.2.2"));
    }

    @Test
    void twentyFirstFromAddressBlocked() {
        for (int i = 0; i < 20; i++) throttle.recordFailure("user" + i, "9.9.9.9");
        assertTrue(throttle.blocked("someone-new", "9.9.9.9"));
        assertFalse(throttle.blocked("someone-new", "8.8.8.8"));
    }

    @Test
    void spoofedForwardedHeaderDoesNotHelp() {
        // Every attempt claims a different made-up client address in the first X-Forwarded-For entry.
        for (int i = 0; i < 20; i++) {
            String fake = ThreadLocalRandom.current().nextInt(256) + ".0.0." + i;
            String ip = ClientAddress.of("9.9.9.9", fake + ", 9.9.9.9", "10.0.0.5");
            throttle.recordFailure("user" + i, ip);
        }
        String nextIp = ClientAddress.of("9.9.9.9", "7.7.7.7, 9.9.9.9", "10.0.0.5");
        assertTrue(throttle.blocked("another", nextIp));
    }

    @Test
    void successResetsUsernameCount() {
        for (int i = 0; i < 4; i++) throttle.recordFailure("bob", "1.1.1.1");
        throttle.recordSuccess("bob");
        for (int i = 0; i < 4; i++) throttle.recordFailure("bob", "1.1.1.1");
        assertFalse(throttle.blocked("bob", "3.3.3.3"));
    }

    @Test
    void windowIsFifteenMinutes() {
        throttle.recordFailure("bob", "1.1.1.1");
        long userTtl = jedis.ttl("login:fail:user:bob");
        long ipTtl = jedis.ttl("login:fail:ip:1.1.1.1");
        assertTrue(userTtl > 890 && userTtl <= 900, "user ttl " + userTtl);
        assertTrue(ipTtl > 890 && ipTtl <= 900, "ip ttl " + ipTtl);
        throttle.recordFailure("bob", "1.1.1.1");
        assertTrue(jedis.ttl("login:fail:user:bob") <= 900);
    }

    @Test
    void missingUsernameStillCountsAddress() {
        for (int i = 0; i < 20; i++) throttle.recordFailure(null, "9.9.9.9");
        assertTrue(throttle.blocked(null, "9.9.9.9"));
        assertTrue(jedis.keys("login:fail:user:*").isEmpty());
    }

    @Test
    void usernameCaseDoesNotDodgeTheLimit() {
        for (int i = 0; i < 5; i++) throttle.recordFailure(i % 2 == 0 ? "Bob" : " bob ", "1.1.1." + i);
        assertTrue(throttle.blocked("BOB", "2.2.2.2"));
    }
}
