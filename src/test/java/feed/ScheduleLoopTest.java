package feed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleLoopTest {

    private JedisPool pool;
    private Jedis jedis;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    @Test
    void datesAreElevenUtcDays() {
        List<LocalDate> dates = ScheduleLoop.dates(LocalDate.of(2026, 10, 8));
        assertEquals(11, dates.size());
        assertEquals(LocalDate.of(2026, 10, 6), dates.get(0));
        assertEquals(LocalDate.of(2026, 10, 16), dates.get(10));
    }

    @Test
    void failedDateIsSkipped() throws IOException {
        String board = ScheduleWriterTest.fixture("schedule-nfl.json");
        AtomicInteger calls = new AtomicInteger();
        LocalDate bad = LocalDate.of(2026, 10, 9);
        ScheduleLoop.ScheduleSource source = (league, date) -> {
            calls.incrementAndGet();
            if (league.equals("NFL") && date.equals(bad)) throw new IOException("ESPN said no");
            return league.equals("NFL") ? board : "{\"events\":[]}";
        };
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
        ScheduleLoop loop = new ScheduleLoop(source, new ScheduleWriter(pool, clock::millis), clock);

        int written = loop.runOnce();

        assertEquals(44, calls.get(), "4 leagues x 11 dates");
        assertEquals(10 * 2, written, "the other 10 NFL dates still wrote their 2 games each");
        assertNotNull(jedis.zscore("games:start", "nfl-401872979"));
    }
}
