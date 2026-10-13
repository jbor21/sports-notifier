package checker;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import static org.junit.jupiter.api.Assertions.*;

class RedisClockTest {

    @Test
    void offsetIsRedisMinusLocal() {
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            long offset = RedisClock.offsetMillis(jedis, () -> System.currentTimeMillis() - 5000);
            assertTrue(offset >= 4900 && offset <= 5100, "a local clock 5 s behind Redis: " + offset);
        }
    }
}
