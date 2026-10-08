package feed;

import com.google.gson.Gson;
import model.GameSnapshot;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import util.RedisKeys;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Saves ESPN's schedule in {@code sched:<gameId>} and indexes each game in {@code games:start}.
 *
 * A listing, not the score: it never writes {@code game:<id>}, sends no play, and fires no alert.
 * The game lists use it for games the poller has no record of (future games, finals whose record
 * expired) and for venue and broadcast. The fields have the names the accept script uses in
 * {@code game:<id>}, so one reader handles both.
 */
public final class ScheduleWriter {

    /** 10 days: past the furthest date fetched (today + 8), so an entry never outlives its use by much. */
    static final long TTL_SECONDS = 864_000L;
    private static final Gson GSON = new Gson();

    private final JedisPool pool;
    private final LongSupplier nowMillis;

    public ScheduleWriter(JedisPool pool, LongSupplier nowMillis) {
        this.pool = pool;
        this.nowMillis = nowMillis;
    }

    /** @return how many games were written */
    public int write(List<ScheduleEntry> entries) {
        if (entries.isEmpty()) return 0;
        String fetchedAt = String.valueOf(nowMillis.getAsLong());
        try (Jedis jedis = pool.getResource()) {
            Pipeline p = jedis.pipelined();
            for (ScheduleEntry e : entries) {
                GameSnapshot g = e.snap();
                String key = RedisKeys.SCHED_PREFIX + g.gameId();
                p.hset(key, fields(e, fetchedAt));
                p.expire(key, TTL_SECONDS);
                if (g.startTimeMillis() > 0) p.zadd(RedisKeys.GAMES_START, g.startTimeMillis(), g.gameId());
            }
            p.sync();
        }
        return entries.size();
    }

    private static Map<String, String> fields(ScheduleEntry e, String fetchedAt) {
        GameSnapshot g = e.snap();
        Map<String, String> f = new HashMap<>();
        f.put("league", g.league());
        f.put("homeTeam", g.homeTeam());
        f.put("awayTeam", g.awayTeam());
        f.put("homeScore", String.valueOf(g.homeScore()));
        f.put("awayScore", String.valueOf(g.awayScore()));
        f.put("status", g.status());
        f.put("phase", g.phase().name());
        f.put("period", String.valueOf(g.period()));
        f.put("clockTenths", String.valueOf(g.clockTenths()));
        f.put("half", g.half().name());
        f.put("outs", String.valueOf(g.outs()));
        f.put("stage", g.stage().name());
        f.put("seasonType", String.valueOf(g.seasonType()));
        f.put("detail", orEmpty(g.detail()));
        f.put("startTime", String.valueOf(g.startTimeMillis()));
        f.put("lineScore", GSON.toJson(g.lineScore()));
        f.put("venue", orEmpty(e.venue()));
        f.put("broadcast", orEmpty(e.broadcast()));
        f.put("fetchedAt", fetchedAt);
        return f;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
