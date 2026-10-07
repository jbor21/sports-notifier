package service.impl;

import catalog.FollowKind;
import feed.TeamIds;
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
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Who hears about a play: the union of the play's targets (both teams, the game, the league), each
 * crossed with the play's switches, so {@code sub:team:nhl-bos:GOAL} and the rest (spec section 6).
 * Built inside Redis, not in Java: a handful of Redis calls per play, not one round trip per
 * subscriber, and the union de-duplicates a user reached through several follows. Both teams, not
 * just the tagged one: following a team means following its whole game.
 */
public final class Audience {

    private static final int PAGE_SIZE = 1000;
    private static final long TEMP_KEY_TTL_SECONDS = 60L;

    private final JedisPool pool;

    public Audience(JedisPool pool) {
        this.pool = pool;
    }

    /**
     * Calls {@code page} with the audience a page at a time, so a huge audience never sits in memory
     * at once. No switches, no audience.
     */
    public void forEachPage(GameEvent play, List<String> switches, Consumer<List<String>> page) {
        if (switches.isEmpty()) return;

        List<String> targets = new ArrayList<>();
        for (String team : new LinkedHashSet<>(Arrays.asList(
                TeamIds.of(play.getLeague(), play.getHomeTeam()), TeamIds.of(play.getLeague(), play.getAwayTeam())))) {
            if (team != null) targets.add(FollowSets.prefix(FollowKind.TEAM) + team);
        }
        if (play.getGameId() != null) targets.add(FollowSets.prefix(FollowKind.GAME) + play.getGameId());
        if (play.getLeague() != null) targets.add(FollowSets.prefix(FollowKind.LEAGUE) + play.getLeague());
        List<String> sources = new ArrayList<>();
        for (String target : targets) {
            for (String s : switches) sources.add(target + ":" + s);
        }
        if (sources.isEmpty()) return;

        // Unique per call: a stalled worker can overlap the lane's new owner on the same play, and with
        // a shared name one worker's cleanup would delete the set the other is still paging through.
        String audience = RedisKeys.FANOUT_PREFIX + play.getGameId() + ":v" + play.getVersion() + ":" + UUID.randomUUID();
        try (Jedis jedis = pool.getResource()) {
            try {
                Pipeline p = jedis.pipelined();
                p.sunionstore(audience, sources.toArray(new String[0]));
                // Safety net: if this worker dies mid-fan-out, the temporary set still goes away.
                p.expire(audience, TEMP_KEY_TTL_SECONDS);
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
