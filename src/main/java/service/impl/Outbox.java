package service.impl;

import model.GameEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import service.PlayPublisher;
import util.GameEventParser;
import util.RedisKeys;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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

    /**
     * Sends a waiting play and clears its slot once the log confirms it. If the send fails, the slot
     * stays and the retry loop sends it again. Both Event Processing and the retry loop send this way.
     *
     * @return completes after the slot is cleared; completes exceptionally if the send failed
     */
    public CompletableFuture<Void> send(PlayPublisher publisher, GameEvent play) {
        CompletableFuture<Void> sent;
        try {
            sent = publisher.publish(play);
        } catch (RuntimeException e) {
            sent = CompletableFuture.failedFuture(e);
        }
        return sent.whenComplete((ok, err) -> {
            if (err != null) {
                System.err.println("[Outbox] send failed, left in the outbox for retry: "
                    + play.getEventId() + " v" + play.getVersion() + ": " + err.getMessage());
            }
        }).thenRun(() -> {
            try {
                clear(play.getGameId(), play.getVersion());
            } catch (RuntimeException e) {
                System.err.println("[Outbox] sent " + play.getEventId()
                    + " but could not clear its slot; the retry loop will send it once more: " + e.getMessage());
            }
        });
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
