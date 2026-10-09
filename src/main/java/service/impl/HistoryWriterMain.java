package service.impl;

import io.javalin.Javalin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import com.zaxxer.hikari.HikariDataSource;
import util.GsonJsonMapper;
import util.Db;
import util.KafkaSettings;
import util.LoopHealth;
import util.Topics;

import java.util.Collections;
import java.time.Duration;
import java.util.List;

/** The history writers (Phase 3.2 spec, 7.2): consumer group {@code history} on notifications, into Postgres. */
public final class HistoryWriterMain {

    /** A pass normally takes about a second; a minute without one means the loop is stuck. */
    private static final long LOOP_MAX_SILENCE_MILLIS = 60_000L;

    public static void main(String[] args) throws InterruptedException {
                KafkaSettings.awaitKafkaOrExit("history");
        int port = Integer.parseInt(System.getenv().getOrDefault("HISTORY_PORT", "8088"));
        HikariDataSource db = Db.fromEnv("history");
        Db.migrate(db);

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(KafkaSettings.consumer(KafkaSettings.bootstrap(), Topics.HISTORY_GROUP));
        consumer.subscribe(List.of(Topics.NOTIFICATIONS));
        // Well under the loop's 60 s silence limit and Kafka's 5 min poll interval: a frozen Postgres
        // fails the batch (retried a second later) instead of stalling the loop (issue #29).
        Duration insertDeadline = Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("HISTORY_INSERT_DEADLINE_MS", "10000")));
        HistoryWriter worker = new HistoryWriter(consumer, new HistoryStore(db, insertDeadline));

        // The consumer is not thread-safe, so the loop owns it: shutdown asks the loop to stop, and the
        // loop's own thread closes the consumer (leaving the group, so its lanes move at once).
        LoopHealth health = new LoopHealth(System::currentTimeMillis, LOOP_MAX_SILENCE_MILLIS);
        Thread loop = new Thread(() -> {
            try {
                worker.run(health);
            } finally {
                worker.close();
            }
        }, "history-writer");
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
            db.close();
        }));

        System.out.println("[HistoryWriter] listening on :" + port);
    }
}
