package service.impl;

import alerts.Detectors;
import alerts.GameLabels;
import catalog.Teams;
import io.javalin.Javalin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.GsonJsonMapper;
import util.JedisPoolHolder;
import util.KafkaSettings;
import util.LoopHealth;
import util.RedisKeys;
import util.Topics;

import java.util.List;
import java.util.Map;

public final class Stage1WorkerMain {

    /** A pass normally takes about a second; a minute without one means the loop is stuck. */
    private static final long LOOP_MAX_SILENCE_MILLIS = 60_000L;

    public static void main(String[] args) throws InterruptedException {
        JedisPoolHolder.awaitRedisOrExit("stage1-worker");
        KafkaSettings.awaitKafkaOrExit("stage1-worker");
        int port = Integer.parseInt(System.getenv().getOrDefault("STAGE1_WORKER_PORT", "8085"));
        JedisPool pool = JedisPoolHolder.create();

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(KafkaSettings.consumer(KafkaSettings.bootstrap(), Topics.STAGE1_GROUP));
        consumer.subscribe(List.of(Topics.GAME_EVENTS));
        KafkaProducer<String, String> producer = new KafkaProducer<>(KafkaSettings.fanoutProducer(KafkaSettings.bootstrap()));
        GameLabels labels = new GameLabels(Teams.load());
        Stage1Worker worker = new Stage1Worker(new DetectorMemory(pool), Detectors.standard(labels),
                new MomentFanout(pool, new AlertAudience(), labels), consumer, producer, Topics.NOTIFICATIONS, Topics.GAME_EVENTS_DLQ, () -> {
            try (Jedis jedis = pool.getResource()) {
                return jedis.exists(RedisKeys.SUB_BUILT);
            }
        });

        // The consumer is not thread-safe, so the loop owns it: shutdown asks the loop to stop, and the
        // loop's own thread closes the consumer (leaving the group, so its lanes move at once).
        LoopHealth health = new LoopHealth(System::currentTimeMillis, LOOP_MAX_SILENCE_MILLIS);
        Thread loop = new Thread(() -> {
            try {
                worker.run(health);
            } finally {
                worker.close();
            }
        }, "stage1-worker");
        health.attach(loop);
        loop.start();

        Javalin app = Javalin.create(cfg -> cfg.jsonMapper(new GsonJsonMapper())).start(port);
        // Fails when the consumer loop has died or stopped coming around, so Kubernetes restarts the pod.
        app.get("/health", ctx -> {
            boolean ok = health.isHealthy();
            // deadLettered: plays set aside on game-events-dlq since start; Phase 4 turns it into a metric.
            ctx.status(ok ? 200 : 503).json(Map.of("status", ok ? "ok" : "consumer loop not running",
                    "deadLettered", worker.deadLettered()));
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            worker.stop();
            try { loop.join(10_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            app.stop();
            pool.close();
        }));

        System.out.println("[Stage1Worker] listening on :" + port);
    }
}
