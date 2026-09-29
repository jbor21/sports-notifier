package service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.http.Context;
import model.User;
import model.UserPreferences;
import org.mindrot.jbcrypt.BCrypt;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.GsonJsonMapper;
import util.JedisPoolHolder;

import java.util.Collections;
import java.util.UUID;

public final class UserPreferenceServiceMain {

    private static final Gson GSON = new Gson();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("USER_PREF_PORT", "8081"));
        JedisPool pool = JedisPoolHolder.create();
        UserPreferenceServiceImpl prefSvc = new UserPreferenceServiceImpl(pool);

        Javalin app = Javalin.create(cfg -> cfg.jsonMapper(new GsonJsonMapper())).start(port);

        app.get("/health", ctx -> ctx.json(Collections.singletonMap("status", "ok")));

        app.post("/users", ctx -> signup(ctx, pool));
        app.post("/users/login", ctx -> login(ctx, pool));
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

    private static void signup(Context ctx, JedisPool pool) {
        JsonObject body = JsonParser.parseString(ctx.body()).getAsJsonObject();
        String username = body.get("username").getAsString().trim().toLowerCase();
        String password = body.get("password").getAsString();
        if (username.isEmpty() || password.length() < 4) {
            ctx.status(400).json(Collections.singletonMap("error", "invalid username or password"));
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            String existing = jedis.get("username:" + username);
            if (existing != null) {
                ctx.status(409).json(Collections.singletonMap("error", "username already exists"));
                return;
            }
            String userId = UUID.randomUUID().toString();
            String hash = BCrypt.hashpw(password, BCrypt.gensalt(10));
            User user = new User(userId, username, hash, System.currentTimeMillis());
            jedis.set("user:" + userId, GSON.toJson(user));
            jedis.set("username:" + username, userId);
            JsonObject resp = new JsonObject();
            resp.addProperty("userId", userId);
            resp.addProperty("username", username);
            ctx.json(resp);
        }
    }

    private static void login(Context ctx, JedisPool pool) {
        JsonObject body = JsonParser.parseString(ctx.body()).getAsJsonObject();
        String username = body.get("username").getAsString().trim().toLowerCase();
        String password = body.get("password").getAsString();
        try (Jedis jedis = pool.getResource()) {
            String userId = jedis.get("username:" + username);
            if (userId == null) { ctx.status(401).json(Collections.singletonMap("error", "invalid credentials")); return; }
            String raw = jedis.get("user:" + userId);
            if (raw == null) { ctx.status(401).json(Collections.singletonMap("error", "invalid credentials")); return; }
            User user = GSON.fromJson(raw, User.class);
            if (!BCrypt.checkpw(password, user.getPasswordHash())) {
                ctx.status(401).json(Collections.singletonMap("error", "invalid credentials"));
                return;
            }
            JsonObject resp = new JsonObject();
            resp.addProperty("userId", user.getUserId());
            resp.addProperty("username", user.getUsername());
            ctx.json(resp);
        }
    }

    private static void getUser(Context ctx, JedisPool pool) {
        String userId = ctx.pathParam("userId");
        try (Jedis jedis = pool.getResource()) {
            String raw = jedis.get("user:" + userId);
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
