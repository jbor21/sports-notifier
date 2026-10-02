package util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class JedisPoolHolderTest {

    @Test
    void returnsTrueWhenRedisIsUp() {
        assertTrue(JedisPoolHolder.waitForRedis("localhost", 6379, Duration.ofSeconds(2), Duration.ofMillis(200)));
    }

    @Test
    void givesUpAfterMaxWhenNothingListens() {
        long start = System.nanoTime();
        assertFalse(JedisPoolHolder.waitForRedis("localhost", 1, Duration.ofSeconds(1), Duration.ofMillis(200)));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 500 && elapsedMs < 2500, "took " + elapsedMs + " ms");
    }
}
