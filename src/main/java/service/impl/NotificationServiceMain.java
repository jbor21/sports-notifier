package service.impl;

import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import util.Db;
import util.GsonJsonMapper;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Notification history: reads, seen, clear, and the 30-day cleanup. The history writers do the inserts. */
public final class NotificationServiceMain {

    private static final Duration RETENTION = Duration.ofDays(30);
    private static final int DELETE_BATCH = 10_000;

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("NOTIFICATION_PORT", "8087"));
        HikariDataSource db = Db.fromEnv("notification");
        Db.migrate(db);
        NotificationHistory history = new NotificationHistory(db);

        ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "history-retention"));
        cleanup.scheduleWithFixedDelay(() -> {
            try {
                int n = history.deleteOlderThan(RETENTION, DELETE_BATCH);
                if (n > 0) System.out.println("[NotificationService] deleted " + n + " notifications older than 30 days");
            } catch (RuntimeException e) {
                System.err.println("[NotificationService] retention cleanup failed, next hour retries: " + e.getMessage());
            }
        }, 1, 60, TimeUnit.MINUTES);

        Javalin app = Javalin.create(cfg -> cfg.jsonMapper(new GsonJsonMapper())).start(port);
        app.get("/health", ctx -> ctx.json(Collections.singletonMap("status", "ok")));
        NotificationRoutes.register(app, history);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cleanup.shutdownNow();
            app.stop();
            db.close();
        }));

        System.out.println("[NotificationService] listening on :" + port);
    }
}
