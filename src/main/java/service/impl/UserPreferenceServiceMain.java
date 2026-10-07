package service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import catalog.AlertCatalog;
import catalog.Teams;
import io.javalin.Javalin;
import io.javalin.http.Context;
import model.UserPreferences;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import com.zaxxer.hikari.HikariDataSource;
import util.ApiError;
import util.ApiJson;
import util.ApiResult;
import util.Db;
import util.GsonJsonMapper;
import util.JedisPoolHolder;
import util.RedisKeys;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class UserPreferenceServiceMain {

    private static final Gson GSON = new Gson();

    public static void main(String[] args) {
        JedisPoolHolder.awaitRedisOrExit("user-pref");
        int port = Integer.parseInt(System.getenv().getOrDefault("USER_PREF_PORT", "8081"));
        JedisPool pool = JedisPoolHolder.create();
        HikariDataSource db = Db.fromEnv("user-pref");
        Db.migrate(db);
        UserPreferenceServiceImpl prefSvc = new UserPreferenceServiceImpl(pool);
        AccountService accounts = new AccountService(db);
        FollowService follows = new FollowService(db, pool, AlertCatalog.load(), Teams.load(), gameId -> {
            try (Jedis jedis = pool.getResource()) {
                Map<String, String> record = jedis.hgetAll(RedisKeys.GAME_PREFIX + gameId);
                return record.isEmpty() ? Optional.empty() : Optional.of(record);
            }
        });

        // Keeps the sub:* sets rebuildable from Postgres: whenever the marker is missing, and daily.
        ScheduledExecutorService jobs = Executors.newScheduledThreadPool(1, r -> new Thread(r, "follow-jobs"));
        new SubRebuilder(db, pool, follows::rebuildOwed, follows::clearRebuildOwed).start(jobs,
                Long.parseLong(System.getenv().getOrDefault("SUB_CHECK_MS", "30000")),
                Long.parseLong(System.getenv().getOrDefault("SUB_FULL_REBUILD_HOURS", "24")));
        new GameFollowExpiry(db, pool, follows).start(jobs);

        Javalin app = Javalin.create(cfg -> cfg.jsonMapper(new GsonJsonMapper())).start(port);

        app.get("/health", ctx -> ctx.json(Collections.singletonMap("status", "ok")));

        app.post("/users", ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) return;
            ApiJson.write(ctx, accounts.signup(field(body, "username"), field(body, "password"), field(body, "timezone")));
        });
        app.post("/users/login", ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) return;
            ApiJson.write(ctx, accounts.login(field(body, "username"), field(body, "password")));
        });
        app.get("/users/{userId}", ctx -> ApiJson.write(ctx, accounts.get(ctx.pathParam("userId"))));
        app.patch("/users/{userId}", ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) return;
            ApiJson.write(ctx, accounts.updateTimezone(ctx.pathParam("userId"), field(body, "timezone")));
        });

        app.get("/users/{userId}/follows", ctx -> {
            ApiResult<List<Follow>> r = follows.list(ctx.pathParam("userId"));
            JsonObject out = new JsonObject();
            JsonArray items = new JsonArray();
            r.body().forEach(f -> items.add(follows.toApi(f)));
            out.add("items", items);
            ctx.contentType("application/json").result(out.toString());
        });
        app.post("/users/{userId}/follows", ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) return;
            JsonElement target = body.get("target");
            if (target == null || !target.isJsonObject()) {
                ApiJson.write(ctx, ApiResult.fail(ApiError.validation("target", "required", "Say what to follow.")));
                return;
            }
            writeFollow(ctx, follows, follows.create(ctx.pathParam("userId"),
                    field(target.getAsJsonObject(), "kind"), field(target.getAsJsonObject(), "id")));
        });
        app.get("/users/{userId}/follows/{id}", ctx ->
                writeFollow(ctx, follows, follows.get(ctx.pathParam("userId"), ctx.pathParam("id"))));
        app.patch("/users/{userId}/follows/{id}", ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) return;
            writeFollow(ctx, follows, follows.patch(ctx.pathParam("userId"), ctx.pathParam("id"), body));
        });
        app.delete("/users/{userId}/follows/{id}", ctx ->
                ApiJson.write(ctx, follows.delete(ctx.pathParam("userId"), ctx.pathParam("id"))));

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
            jobs.shutdownNow();
            app.stop();
            pool.close();
            db.close();
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
        ApiJson.write(ctx, ApiResult.fail(ApiError.of(422, "validation_failed", "The body must be a JSON object.")));
        return null;
    }

    private static String field(JsonObject body, String name) {
        JsonElement el = body.get(name);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static void writeFollow(Context ctx, FollowService follows, ApiResult<Follow> r) {
        if (!r.isOk()) { ApiJson.write(ctx, r); return; }
        ctx.status(r.status()).contentType("application/json").result(follows.toApi(r.body()).toString());
    }
}
