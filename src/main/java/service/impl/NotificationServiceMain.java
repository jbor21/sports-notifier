package service.impl;

import com.google.gson.Gson;
import io.javalin.Javalin;
import model.Notification;
import redis.clients.jedis.JedisPool;
import util.GsonJsonMapper;
import util.JedisPoolHolder;

import java.util.Collections;
import java.util.List;

public final class NotificationServiceMain {

    private static final Gson GSON = new Gson();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("NOTIFICATION_PORT", "8087"));
        JedisPool pool = JedisPoolHolder.create();
        NotificationServiceImpl svc = new NotificationServiceImpl(pool);

        Javalin app = Javalin.create(cfg -> cfg.jsonMapper(new GsonJsonMapper())).start(port);

        app.get("/health", ctx -> ctx.json(Collections.singletonMap("status", "ok")));

        app.get("/history/{userId}", ctx -> {
            int limit = Integer.parseInt(ctx.queryParamAsClass("limit", String.class).getOrDefault("50"));
            List<Notification> hist = svc.getNotificationHistory(ctx.pathParam("userId"), limit);
            ctx.json(hist);
        });

        app.post("/notify/{userId}", ctx -> {
            Notification n = GSON.fromJson(ctx.body(), Notification.class);
            svc.sendNotification(ctx.pathParam("userId"), n);
            ctx.status(202);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            pool.close();
        }));

        System.out.println("[NotificationService] listening on :" + port);
    }
}
