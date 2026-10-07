package gateway;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.http.Context;
import redis.clients.jedis.JedisPool;
import util.ApiError;
import util.SimpleHttp;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * The {@code /api/v1} routes the new UI calls (docs/api/openapi.yaml). The gateway owns sessions and
 * login throttling; the services behind it own the data and answer in the API's shapes, which the
 * gateway passes through.
 */
public final class ApiV1Routes {

    private static final ApiError UNAUTHENTICATED = ApiError.of(401, "unauthenticated", "Sign in first.");

    private ApiV1Routes() {}

    public static void register(Javalin app, JedisPool pool, LoginThrottle throttle, String userPrefUrl, String notificationUrl) {
        app.post("/api/v1/auth/signup", ctx -> {
            HttpResponse<String> r = SimpleHttp.postJson(userPrefUrl + "/users", ctx.body());
            if (r.statusCode() == 201) SessionAuth.createSession(pool, ctx, idIn(r.body()));
            forward(ctx, r);
        });

        app.post("/api/v1/auth/login", ctx -> {
            String username = usernameIn(ctx.body());
            String ip = ClientAddress.of(ctx.header("X-Real-Ip"), ctx.header("X-Forwarded-For"), ctx.ip());
            // Count the attempt before checking the password; see LoginThrottle for why.
            if (!throttle.admit(username, ip)) {
                ctx.header("Retry-After", String.valueOf(LoginThrottle.WINDOW_SECONDS));
                write(ctx, ApiError.of(429, "rate_limited", "Too many attempts. Try again later."));
                return;
            }
            HttpResponse<String> r = SimpleHttp.postJson(userPrefUrl + "/users/login", ctx.body());
            if (r.statusCode() == 200) {
                throttle.recordSuccess(username, ip);
                SessionAuth.createSession(pool, ctx, idIn(r.body()));
            }
            forward(ctx, r);
        });

        app.post("/api/v1/auth/logout", ctx -> {
            if (requireUser(pool, ctx) == null) return;
            SessionAuth.destroySession(pool, ctx);
            ctx.status(204);
        });

        app.get("/api/v1/me", ctx -> {
            String userId = requireUser(pool, ctx);
            if (userId == null) return;
            forward(ctx, SimpleHttp.get(userPrefUrl + "/users/" + enc(userId)));
        });

        app.patch("/api/v1/me", ctx -> {
            String userId = requireUser(pool, ctx);
            if (userId == null) return;
            forward(ctx, SimpleHttp.patchJson(userPrefUrl + "/users/" + enc(userId), ctx.body()));
        });
    }

    /** The signed-in user's id, or null after answering 401 in the API's Error shape. */
    public static String requireUser(JedisPool pool, Context ctx) {
        String userId = SessionAuth.currentUserId(pool, ctx);
        if (userId == null) write(ctx, UNAUTHENTICATED);
        return userId;
    }

    /** Passes a service's answer through unchanged: it is already in the API's shape. */
    static void forward(Context ctx, HttpResponse<String> r) {
        ctx.status(r.statusCode());
        if (r.statusCode() != 204) ctx.contentType("application/json").result(r.body());
    }

    static void write(Context ctx, ApiError e) {
        ctx.status(e.status()).contentType("application/json").result(e.toJson());
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String idIn(String userJson) {
        return JsonParser.parseString(userJson).getAsJsonObject().get("id").getAsString();
    }

    /** The username in a login body, or null if the body isn't a JSON object with one. */
    private static String usernameIn(String body) {
        try {
            JsonElement el = JsonParser.parseString(body);
            if (!el.isJsonObject()) return null;
            JsonElement u = el.getAsJsonObject().get("username");
            return u != null && u.isJsonPrimitive() ? u.getAsString() : null;
        } catch (JsonParseException e) {
            return null;
        }
    }
}
