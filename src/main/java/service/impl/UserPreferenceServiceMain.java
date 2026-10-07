package service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.http.Context;
import model.UserPreferences;
import redis.clients.jedis.JedisPool;
import com.zaxxer.hikari.HikariDataSource;
import util.ApiError;
import util.ApiJson;
import util.ApiResult;
import util.Db;
import util.GsonJsonMapper;
import util.JedisPoolHolder;

import java.util.Collections;

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
}
