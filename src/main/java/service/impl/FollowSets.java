package service.impl;

import catalog.FollowKind;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import util.RedisKeys;
import util.RedisPipelines;

/**
 * The sub:* sets: one per follow target and alert type, holding the ids of users who follow that
 * target with that switch on (spec 5.2). Stage 1 builds an alert's audience from them.
 */
public class FollowSets {

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

    /**
     * The follow's whole state, not a diff: in every enabled switch's set, out of every disabled one.
     * Writing it twice gives the same sets, so a retry or a rebuild never conflicts with it.
     */
    public void writeState(Follow f) {
        try (Jedis jedis = pool.getResource()) {
            Pipeline p = jedis.pipelined();
            f.alerts().forEach((type, s) -> {
                if (s.enabled()) p.sadd(key(f.kind(), f.targetId(), type), f.userId());
                else p.srem(key(f.kind(), f.targetId(), type), f.userId());
            });
            RedisPipelines.syncChecked(p);
        }
    }

    public void removeAll(Follow f) {
        try (Jedis jedis = pool.getResource()) {
            Pipeline p = jedis.pipelined();
            f.alerts().keySet().forEach(type -> p.srem(key(f.kind(), f.targetId(), type), f.userId()));
            RedisPipelines.syncChecked(p);
        }
    }

    /** Says the sets can no longer be trusted: user-pref rebuilds them, Stage 1 waits until it has. */
    public void deleteMarker() {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(RedisKeys.SUB_BUILT);
        }
    }
}
