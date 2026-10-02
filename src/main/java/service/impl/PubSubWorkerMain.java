package service.impl;

import io.javalin.Javalin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.GsonJsonMapper;
import util.JedisPoolHolder;

import java.util.Collections;

public final class PubSubWorkerMain {

    public static void main(String[] args) {
        JedisPoolHolder.awaitRedisOrExit("pubsub-worker");
        int port = Integer.parseInt(System.getenv().getOrDefault("PUBSUB_WORKER_PORT", "8084"));
        String consumerName = System.getenv().getOrDefault("PUBSUB_CONSUMER_NAME", "worker-" + port);

        JedisPool pool = JedisPoolHolder.create();
        Jedis blocking = JedisPoolHolder.dedicated();

        UserPreferenceServiceImpl prefs = new UserPreferenceServiceImpl(pool);
        NotificationServiceImpl notifs = new NotificationServiceImpl(pool);
        PubSubWorkerImpl worker = new PubSubWorkerImpl(prefs, notifs, pool, blocking, consumerName);
        worker.setup();

        Thread consumerThread = new Thread(worker::startConsuming, "pubsub-worker-" + consumerName);
        consumerThread.setDaemon(false);
        consumerThread.start();

        Javalin app = Javalin.create(cfg -> cfg.jsonMapper(new GsonJsonMapper())).start(port);
        app.get("/health", ctx -> ctx.json(Collections.singletonMap("status", "ok")));
        app.get("/info", ctx -> {
            ctx.json(Collections.singletonMap("consumer", consumerName));
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            worker.stop();
            app.stop();
            try { blocking.close(); } catch (Exception ignored) {}
            pool.close();
        }));

        System.out.println("[PubSubWorker] " + consumerName + " listening on :" + port);
    }
}
