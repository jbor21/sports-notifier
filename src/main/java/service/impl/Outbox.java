package service.impl;

import model.GameEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.GameEventParser;
import util.RedisKeys;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Accepted plays waiting for the log to confirm them (DESIGN.md decision 5, Phase 3.1 spec section 1.2).
 *
 * The accept script writes a game's play into {@code outbox:<gameId>} (a hash: version, play) in the
 * same step as the score, and refuses newer plays for that game until the slot is cleared. So the
 * slot holds at most one play, and "still in the outbox" means "accepted but not yet in the log".
 */
public final class Outbox {

    /**
     * Clears the slot only if it still holds this version. A confirmation that arrives late, after
     * the slot was cleared and a newer play accepted, must not clear the newer play: that play would
     * then never be sent. Compare and delete are one script so nothing lands between them.
     *
     * KEYS[1] = outbox:<gameId>   KEYS[2] = outbox:pending   ARGV[1] = version   ARGV[2] = gameId
     * Returns 1 if cleared, 0 if the slot was empty or held another version.
     */
    private static final String CLEAR_SCRIPT =
        "if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then return 0 end\n" +
        "redis.call('DEL', KEYS[1])\n" +
        "redis.call('ZREM', KEYS[2], ARGV[2])\n" +
        "return 1\n";

    private final JedisPool pool;

    public Outbox(JedisPool pool) {
        this.pool = pool;
    }

    /** @return true if the slot held {@code version} and is now empty */
    public boolean clear(String gameId, long version) {
        try (Jedis jedis = pool.getResource()) {
            Object r = jedis.eval(CLEAR_SCRIPT,
                List.of(RedisKeys.OUTBOX_PREFIX + gameId, RedisKeys.OUTBOX_PENDING),
                List.of(String.valueOf(version), gameId));
            return Long.valueOf(1L).equals(r);
        }
    }

    /** Games whose waiting play was accepted at or before {@code olderThanMillis}, oldest first. */
    public List<String> gamesWaitingSince(long olderThanMillis) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.zrangeByScore(RedisKeys.OUTBOX_PENDING, Double.NEGATIVE_INFINITY, olderThanMillis);
        }
    }

    /** The play waiting for this game, with its version set, if there is one. */
    public Optional<GameEvent> play(String gameId) {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> slot = jedis.hgetAll(RedisKeys.OUTBOX_PREFIX + gameId);
            if (slot.isEmpty()) return Optional.empty();
            return Optional.of(GameEventParser.parse(slot.get("play")).withVersion(Long.parseLong(slot.get("version"))));
        }
    }
}
