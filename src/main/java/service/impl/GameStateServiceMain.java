package service.impl;

import io.javalin.Javalin;
import model.GameState;
import redis.clients.jedis.JedisPool;
import util.GsonJsonMapper;
import util.JedisPoolHolder;

import java.util.Collections;

public final class GameStateServiceMain {

    public static void main(String[] args) {
        JedisPoolHolder.awaitRedisOrExit("game-state");
        int port = Integer.parseInt(System.getenv().getOrDefault("GAME_STATE_PORT", "8082"));
        JedisPool pool = JedisPoolHolder.create();
        GameStateServiceImpl svc = new GameStateServiceImpl(pool);

        Javalin app = Javalin.create(cfg -> cfg.jsonMapper(new GsonJsonMapper())).start(port);

        app.get("/health", ctx -> ctx.json(Collections.singletonMap("status", "ok")));

        app.get("/games/{gameId}", ctx -> {
            GameState state = svc.getGameState(ctx.pathParam("gameId"));
            if (state == null) { ctx.status(404).result("{}"); return; }
            ctx.json(state);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            pool.close();
        }));

        System.out.println("[GameStateService] listening on :" + port);
    }
}
