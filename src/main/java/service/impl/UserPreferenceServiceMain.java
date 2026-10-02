package service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.http.Context;
import model.User;
import model.UserPreferences;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.GsonJsonMapper;
import util.JedisPoolHolder;
import util.RedisKeys;

import java.util.Collections;

public final class UserPreferenceServiceMain {

    private static final Gson GSON = new Gson();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("USER_PREF_PORT", "8081"));
        JedisPool pool = JedisPoolHolder.create();
        UserPreferenceServiceImpl prefSvc = new UserPreferenceServiceImpl(pool);
        AccountService accounts = new AccountService(pool);

        Javalin app = Javalin.create(cfg -> cfg.jsonMapper(new GsonJsonMapper())).start(port);

        app.get("/health", ctx -> ctx.json(Collections.singletonMap("status", "ok")));

        app.post("/users", ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) return;
            write(ctx, accounts.signup(field(body, "username"), field(body, "password")));
        });
        app.post("/users/login", ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) return;
            write(ctx, accounts.login(field(body, "username"), field(body, "password")));
        });
        app.get("/users/{userId}", ctx -> getUser(ctx, pool));

        app.get("/prefs/{userId}", ctx -> {
            UserPreferences prefs = prefSvc.getPreferences(ctx.pathParam("userId"));
            if (prefs == null) { ctx.status(404).result("{}"); return; }
            ctx.json(prefs);
        });

        app.put("/prefs/{userId}", ctx -> {
            String userId = ctx.pathParam("userId");
            UserPreferences incoming = GSON.fromJson(ctx.body(), UserPreferences.class);
            UserPreferences toSave = new UserPreferences(
                userId,
                incoming.getFollowedTeams(),
                incoming.getFollowedGames(),
                incoming.getFollowedLeagues(),
                incoming.getNotificationTypes()
            );
            prefSvc.savePreferences(userId, toSave);
            ctx.json(toSave);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            pool.close();
        }));

        System.out.println("[UserPrefService] listening on :" + port);
    }

    /** The body as a JSON object, or null after answering 400. Never lets bad input become a 500. */
    private static JsonObject jsonBody(Context ctx) {
        try {
            JsonElement el = JsonParser.parseString(ctx.body());
            if (el.isJsonObject()) return el.getAsJsonObject();
        } catch (JsonParseException ignored) {
            // fall through
        }
        ctx.status(400).json(Collections.singletonMap("error", "body must be a JSON object"));
        return null;
    }

    private static String field(JsonObject body, String name) {
        JsonElement el = body.get(name);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static void write(Context ctx, AccountService.Result r) {
        if (r.status() != 200) {
            ctx.status(r.status()).json(Collections.singletonMap("error", r.error()));
            return;
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("userId", r.userId());
        resp.addProperty("username", r.username());
        ctx.json(resp);
    }

    private static void getUser(Context ctx, JedisPool pool) {
        String userId = ctx.pathParam("userId");
        try (Jedis jedis = pool.getResource()) {
            String raw = jedis.get(RedisKeys.USER_PREFIX + userId);
            if (raw == null) { ctx.status(404).result("{}"); return; }
            User u = GSON.fromJson(raw, User.class);
            JsonObject resp = new JsonObject();
            resp.addProperty("userId", u.getUserId());
            resp.addProperty("username", u.getUsername());
            resp.addProperty("createdAt", u.getCreatedAt());
            ctx.json(resp);
        }
    }
}
