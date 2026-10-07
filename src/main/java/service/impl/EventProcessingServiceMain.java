package service.impl;

import io.javalin.Javalin;
import service.ProcessResult;
import redis.clients.jedis.JedisPool;
import util.GsonJsonMapper;
import util.JedisPoolHolder;

import java.util.Collections;

public final class EventProcessingServiceMain {

    public static void main(String[] args) {
        JedisPoolHolder.awaitRedisOrExit("event-processing");
        int port = Integer.parseInt(System.getenv().getOrDefault("EVENT_PROCESSING_PORT", "8083"));
        JedisPool pool = JedisPoolHolder.create();
        EventProcessingServiceImpl eps = new EventProcessingServiceImpl(pool, new RedisStreamPublisher(pool), System::currentTimeMillis);

        Javalin app = Javalin.create(cfg -> cfg.jsonMapper(new GsonJsonMapper())).start(port);

        app.get("/health", ctx -> ctx.json(Collections.singletonMap("status", "ok")));

        // Duplicate and stale are not errors: the sender did its job, there was just nothing new.
        // Only INVALID is a 400. A Redis failure throws, so Javalin answers 500 and the poller retries.
        app.post("/events", ctx -> {
            ProcessResult result = eps.processJson(ctx.body());
            ctx.status(result == ProcessResult.INVALID ? 400 : 200)
               .json(Collections.singletonMap("result", result.name().toLowerCase()));
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            pool.close();
        }));

        System.out.println("[EventProcessingService] listening on :" + port);
    }
}
