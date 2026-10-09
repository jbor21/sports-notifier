package service.impl;

import catalog.FollowKind;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import util.RedisKeys;
import util.RedisPipelines;

import java.util.Set;
import java.util.TreeSet;

/**
 * The sub:* sets: one per follow target and alert type, holding the ids of users who follow that
 * target with that switch on (spec 5.2). Stage 1 builds an alert's audience from them.
 */
public class FollowSets {

    /**
     * The shape of the sets: which names exist and what they hold. Bump it whenever that changes
     * (3.3 added {@code ANY} and the innings sets), so a Redis kept across an upgrade is rebuilt
     * instead of trusted (issue #16).
     */
    public static final int SHAPE_VERSION = 2;

    private final JedisPool pool;

    public FollowSets(JedisPool pool) {
        this.pool = pool;
    }

    /** {@code sub:<kind>:<targetId>:<ALERT>}, e.g. {@code sub:team:mlb-chw:GAME_START}. */
    public static String key(FollowKind kind, String targetId, String alertType) {
        return prefix(kind) + targetId + ":" + alertType;
    }

    public static String prefix(FollowKind kind) {
        return switch (kind) {
            case LEAGUE -> RedisKeys.SUB_LEAGUE_PREFIX;
            case TEAM -> RedisKeys.SUB_TEAM_PREFIX;
            case GAME -> RedisKeys.SUB_GAME_PREFIX;
        };
    }

    private static final String END_OF_INNING = "END_OF_INNING";

    /**
     * The set names (the part after the target) the follow's user belongs in: {@code ANY}, every
     * enabled switch, and for end of inning with innings chosen, {@code END_OF_INNING:<n>} per inning
     * instead of the plain set. Options live in the sets, so delivery looks nothing up (spec 4.2).
     */
    public static Set<String> memberships(Follow f) {
        Set<String> names = new TreeSet<>();
        names.add(RedisKeys.SUB_ANY);
        f.alerts().forEach((type, s) -> {
            if (!s.enabled()) return;
            if (END_OF_INNING.equals(type) && !s.innings().isEmpty()) {
                s.innings().forEach(n -> names.add(inningSwitch(n)));
            } else {
                names.add(type);
            }
        });
        return names;
    }

    /** Every set name the follow could be in. */
    public static Set<String> allNames(Follow f) {
        Set<String> names = new TreeSet<>(f.alerts().keySet());
        names.add(RedisKeys.SUB_ANY);
        if (f.alerts().containsKey(END_OF_INNING)) {
            for (int n = 1; n <= 9; n++) names.add(inningSwitch(n));
        }
        return names;
    }

    /** {@code END_OF_INNING:<n>}: followers who chose inning n. */
    public static String inningSwitch(int n) {
        return END_OF_INNING + ":" + n;
    }

    /**
     * The follow's whole state, not a diff: in every set of {@link #memberships}, out of every other.
     * Writing it twice gives the same sets, so a retry or a rebuild never conflicts with it.
     */
    public void writeState(Follow f) {
        Set<String> in = memberships(f);
        try (Jedis jedis = pool.getResource()) {
            Pipeline p = jedis.pipelined();
            for (String name : allNames(f)) {
                if (in.contains(name)) p.sadd(key(f.kind(), f.targetId(), name), f.userId());
                else p.srem(key(f.kind(), f.targetId(), name), f.userId());
            }
            RedisPipelines.syncChecked(p);
        }
    }

    public void removeAll(Follow f) {
        try (Jedis jedis = pool.getResource()) {
            Pipeline p = jedis.pipelined();
            allNames(f).forEach(name -> p.srem(key(f.kind(), f.targetId(), name), f.userId()));
            RedisPipelines.syncChecked(p);
        }
    }

    /**
     * Whether {@code sub:built} says the sets were built in today's shape. A marker from before
     * versioning is a plain string, so the type is checked first rather than reading it as a hash.
     */
    public static boolean isBuilt(Jedis jedis) {
        if (!"hash".equals(jedis.type(RedisKeys.SUB_BUILT))) return false;
        return String.valueOf(SHAPE_VERSION).equals(jedis.hget(RedisKeys.SUB_BUILT, "version"));
    }

    /** Records a finished build: today's shape, by the Redis whose {@code run_id} is {@code runId}. */
    public static void markBuilt(Jedis jedis, String runId) {
        Pipeline p = jedis.pipelined();
        p.del(RedisKeys.SUB_BUILT);
        p.hset(RedisKeys.SUB_BUILT, java.util.Map.of("version", String.valueOf(SHAPE_VERSION),
                "runId", runId == null ? "" : runId));
        RedisPipelines.syncChecked(p);
    }

    /** Says the sets can no longer be trusted: user-pref rebuilds them, Stage 1 waits until it has. */
    public void deleteMarker() {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(RedisKeys.SUB_BUILT);
        }
    }
}
