package service.impl;

import model.GameEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import util.RedisKeys;
import util.RedisPipelines;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Who hears about a play. Builds the audience inside Redis instead of in Java: the union of everyone
 * following either team, the game, or the league, intersected with everyone who enabled this event
 * type. That is a handful of Redis calls per play, not one SISMEMBER round trip per subscriber, and
 * the union de-duplicates a user who follows both teams so they get one notification. Both teams,
 * not just the tagged one: following a team means following its whole game.
 */
public final class Audience {

    private static final int PAGE_SIZE = 1000;
    private static final long TEMP_KEY_TTL_SECONDS = 60L;

    private final JedisPool pool;

    public Audience(JedisPool pool) {
        this.pool = pool;
    }

    /** Calls {@code page} with the audience a page at a time, so a huge audience never sits in memory at once. */
    public void forEachPage(GameEvent play, Consumer<List<String>> page) {
        if (play.getEventType() == null) return;

        List<String> sources = new ArrayList<>();
        for (String team : new LinkedHashSet<>(Arrays.asList(play.getHomeTeam(), play.getAwayTeam(), play.getTeam()))) {
            if (team != null) sources.add(RedisKeys.SUB_TEAM_PREFIX + team);
        }
        if (play.getGameId() != null) sources.add(RedisKeys.SUB_GAME_PREFIX + play.getGameId());
        if (play.getLeague() != null) sources.add(RedisKeys.SUB_LEAGUE_PREFIX + play.getLeague());
        if (sources.isEmpty()) return;

        String audience = RedisKeys.FANOUT_PREFIX + play.getGameId() + ":v" + play.getVersion();
        try (Jedis jedis = pool.getResource()) {
            try {
                Pipeline p = jedis.pipelined();
                p.sunionstore(audience, sources.toArray(new String[0]));
                p.sinterstore(audience, audience, RedisKeys.SUB_NOTIF_PREFIX + play.getEventType());
                // Safety net: if this worker dies mid-fan-out, the temporary set still goes away.
                p.expire(audience, TEMP_KEY_TTL_SECONDS);
                // Checked: if the union worked and the type filter failed, the audience would be
                // every follower, not just those who asked for this event type.
                RedisPipelines.syncChecked(p);

                ScanParams params = new ScanParams().count(PAGE_SIZE);
                String cursor = ScanParams.SCAN_POINTER_START;
                do {
                    ScanResult<String> result = jedis.sscan(audience, cursor, params);
                    if (!result.getResult().isEmpty()) page.accept(result.getResult());
                    cursor = result.getCursor();
                } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
            } finally {
                jedis.del(audience);
            }
        }
    }
}
