package feed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The schedule is a listing beside the score, never the score (Phase 3.4 spec, section 4). */
class ScheduleWriterTest {

    private JedisPool pool;
    private Jedis jedis;
    private ScheduleWriter writer;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        writer = new ScheduleWriter(pool, () -> 1_000L);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    static String fixture(String name) throws IOException {
        try (InputStream in = ScheduleWriterTest.class.getResourceAsStream("/espn/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void writesSchedAndIndex() throws IOException {
        List<ScheduleEntry> entries = EspnScoreFeed.parseSchedule("NFL", fixture("schedule-nfl.json"));
        assertEquals(2, writer.write(entries));

        Map<String, String> s = jedis.hgetAll("sched:nfl-401872979");
        assertEquals("NFL", s.get("league"));
        assertEquals("SCHEDULED", s.get("status"));
        assertEquals("Caesars Superdome", s.get("venue"));
        assertEquals("ESPN", s.get("broadcast"));
        long start = Instant.parse("2026-10-06T00:15:00Z").toEpochMilli();
        assertEquals(String.valueOf(start), s.get("startTime"));
        assertEquals("1000", s.get("fetchedAt"));
        long ttl = jedis.ttl("sched:nfl-401872979");
        assertTrue(ttl > 0 && ttl <= 864_000, "ttl " + ttl);
        assertEquals((double) start, jedis.zscore("games:start", "nfl-401872979"));

        Map<String, String> fin = jedis.hgetAll("sched:nfl-401872964");
        assertEquals("FINAL", fin.get("status"));
        assertEquals("27", fin.get("homeScore"));
        assertEquals("Prime Video", fin.get("broadcast"));
        assertTrue(fin.get("lineScore").startsWith("[{\"period\":1"), fin.get("lineScore"));
    }

    @Test
    void leavesGameRecordAlone() throws IOException {
        jedis.hset("game:nfl-401872964", Map.of("homeScore", "99", "version", "7"));
        writer.write(EspnScoreFeed.parseSchedule("NFL", fixture("schedule-nfl.json")));
        assertEquals(Map.of("homeScore", "99", "version", "7"), jedis.hgetAll("game:nfl-401872964"));
    }

    @Test
    void partialEventsDoNotStopTheDate() {
        String json = "{\"events\":["
            + "{\"id\":\"1\",\"date\":\"2026-10-10T17:00Z\",\"competitions\":[{\"status\":{\"period\":0,\"type\":{\"name\":\"STATUS_SCHEDULED\",\"state\":\"pre\",\"completed\":false}}}]},"
            + "{\"id\":\"2\",\"date\":\"2026-10-10T20:00Z\",\"competitions\":[{\"status\":{\"period\":0,\"type\":{\"name\":\"STATUS_SCHEDULED\",\"state\":\"pre\",\"completed\":false}},"
            + "\"competitors\":[{\"homeAway\":\"home\",\"score\":\"0\",\"team\":{\"abbreviation\":\"KC\"}},{\"homeAway\":\"away\",\"score\":\"0\",\"team\":{\"abbreviation\":\"BUF\"}}]}]}"
            + "]}";
        List<ScheduleEntry> entries = EspnScoreFeed.parseSchedule("NFL", json);
        assertEquals(1, entries.size(), "the game with no competitors is skipped");
        writer.write(entries);
        Map<String, String> s = jedis.hgetAll("sched:nfl-2");
        assertEquals("KC", s.get("homeTeam"));
        assertEquals("", s.get("venue"));
        assertEquals("", s.get("broadcast"));
    }
}
