package service.impl;

import io.javalin.Javalin;
import model.GameEvent;
import redis.clients.jedis.JedisPool;
import util.GameEventParser;
import util.GsonJsonMapper;
import util.JedisPoolHolder;

import java.util.Collections;

public final class EventProcessingServiceMain {

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("EVENT_PROCESSING_PORT", "8083"));
        JedisPool pool = JedisPoolHolder.create();
        GameStateServiceImpl gameState = new GameStateServiceImpl(pool);
        EventProcessingServiceImpl eps = new EventProcessingServiceImpl(gameState, pool);

        Javalin app = Javalin.create(cfg -> cfg.jsonMapper(new GsonJsonMapper())).start(port);

        app.get("/health", ctx -> ctx.json(Collections.singletonMap("status", "ok")));

        app.post("/events", ctx -> {
            GameEvent ev = GameEventParser.parse(ctx.body());
            eps.processEvent(ev);
            ctx.status(202).json(Collections.singletonMap("accepted", ev != null ? ev.getEventId() : null));
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            pool.close();
        }));

        System.out.println("[EventProcessingService] listening on :" + port);
    }
}
