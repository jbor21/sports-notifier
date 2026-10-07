package service.impl;

import io.javalin.Javalin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import redis.clients.jedis.JedisPool;
import util.GsonJsonMapper;
import util.JedisPoolHolder;
import util.KafkaSettings;
import util.LoopHealth;
import util.Topics;

import java.util.Collections;
import java.util.List;

public final class Stage2WorkerMain {

    /** A pass normally takes about a second; a minute without one means the loop is stuck. */
    private static final long LOOP_MAX_SILENCE_MILLIS = 60_000L;

    public static void main(String[] args) throws InterruptedException {
        JedisPoolHolder.awaitRedisOrExit("stage2-worker");
        KafkaSettings.awaitKafkaOrExit("stage2-worker");
        int port = Integer.parseInt(System.getenv().getOrDefault("STAGE2_WORKER_PORT", "8086"));
        JedisPool pool = JedisPoolHolder.create();

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(KafkaSettings.consumer(KafkaSettings.bootstrap(), Topics.STAGE2_GROUP));
        consumer.subscribe(List.of(Topics.NOTIFICATIONS));
        Stage2Worker worker = new Stage2Worker(consumer, new Delivery(pool));

        // The consumer is not thread-safe, so the loop owns it: shutdown asks the loop to stop, and the
        // loop's own thread closes the consumer (leaving the group, so its lanes move at once).
        LoopHealth health = new LoopHealth(System::currentTimeMillis, LOOP_MAX_SILENCE_MILLIS);
        Thread loop = new Thread(() -> {
            try {
                worker.run(health);
            } finally {
                worker.close();
            }
        }, "stage2-worker");
        health.attach(loop);
        loop.start();

        Javalin app = Javalin.create(cfg -> cfg.jsonMapper(new GsonJsonMapper())).start(port);
        // Fails when the consumer loop has died or stopped coming around, so Kubernetes restarts the pod.
        app.get("/health", ctx -> {
            boolean ok = health.isHealthy();
            ctx.status(ok ? 200 : 503).json(Collections.singletonMap("status", ok ? "ok" : "consumer loop not running"));
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            worker.stop();
            try { loop.join(10_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            app.stop();
            pool.close();
        }));

        System.out.println("[Stage2Worker] listening on :" + port);
    }
}
