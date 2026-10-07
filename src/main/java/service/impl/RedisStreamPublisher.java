package service.impl;

import model.GameEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.XAddParams;
import service.PlayPublisher;
import util.GameEventParser;
import util.RedisKeys;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The course path: append the play to the Redis Stream the course workers read. Kept until Stage 1
 * and Stage 2 replace those workers (Phase 3.1, Task 6), then deleted. GAME_SYNC is not appended,
 * because the course workers would only drop it, but it still completes, so its outbox entry clears.
 */
public final class RedisStreamPublisher implements PlayPublisher {

    private static final long MAX_STREAM_LENGTH = 1000L;

    private final JedisPool pool;

    public RedisStreamPublisher(JedisPool pool) {
        this.pool = pool;
    }

    @Override
    public CompletableFuture<Void> publish(GameEvent play) {
        if (!EventProcessingServiceImpl.GAME_SYNC.equals(play.getEventType())) {
            XAddParams params = XAddParams.xAddParams().maxLen(MAX_STREAM_LENGTH).approximateTrimming();
            try (Jedis jedis = pool.getResource()) {
                jedis.xadd(RedisKeys.GAME_EVENTS_STREAM, params, Map.of("data", GameEventParser.toJson(play)));
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        return CompletableFuture.completedFuture(null);
    }
}
