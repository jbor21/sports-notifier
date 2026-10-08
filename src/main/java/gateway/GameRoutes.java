package gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.http.Context;
import games.GameDays;
import redis.clients.jedis.JedisPool;
import util.ApiError;
import util.SimpleHttp;

import java.net.http.HttpResponse;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code /api/v1/games} and {@code /api/v1/games/{id}}. The gateway owns what needs the session: the
 * user's timezone as the default day, and {@code following=true}. Game State only reads games, so
 * it never sees user data.
 */
public final class GameRoutes {

    private GameRoutes() {}

    public static void register(Javalin app, JedisPool pool, String gameStateUrl, String userPrefUrl, Supplier<Instant> now) {
        app.get("/api/v1/games", ctx -> {
            String userId = SessionAuth.currentUserId(pool, ctx);
            boolean following = "true".equals(ctx.queryParam("following"));
            if (following && userId == null) {
                ApiV1Routes.write(ctx, ApiError.of(401, "unauthenticated", "Sign in first."));
                return;
            }
            ZoneId tz = timezone(ctx, userId, userPrefUrl);
            if (tz == null) return;
            Instant at = now.get();
            LocalDate date;
            try {
                String raw = ctx.queryParam("date");
                date = raw == null ? GameDays.today(tz, at) : LocalDate.parse(raw);
            } catch (DateTimeParseException e) {
                ApiV1Routes.write(ctx, ApiError.validation("date", "invalid", "date must be YYYY-MM-DD."));
                return;
            }
            GameDays.Range range;
            try {
                range = GameDays.range(date, tz, at);
            } catch (GameDays.OutOfRange e) {
                ApiV1Routes.write(ctx, ApiError.validation("date", "out_of_range", e.getMessage()));
                return;
            }
            StringBuilder q = new StringBuilder("?from=").append(range.fromMillis()).append("&to=").append(range.toMillis());
            for (String name : new String[]{"league", "team", "status"}) {
                String v = ctx.queryParam(name);
                if (v != null) q.append('&').append(name).append('=').append(ApiV1Routes.enc(v));
            }
            HttpResponse<String> r = SimpleHttp.get(gameStateUrl + "/games" + q);
            if (r.statusCode() != 200) {
                ApiV1Routes.forward(ctx, r);
                return;
            }
            JsonArray items = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("items");
            if (following) items = followed(items, follows(userPrefUrl, userId));
            JsonObject out = new JsonObject();
            out.addProperty("date", date.toString());
            out.add("items", items);
            ctx.contentType("application/json").result(out.toString());
        });
        app.get("/api/v1/games/{gameId}", ctx ->
            ApiV1Routes.forward(ctx, SimpleHttp.get(gameStateUrl + "/games/" + ApiV1Routes.enc(ctx.pathParam("gameId")))));
    }

    /** The {@code tz} parameter, else the signed-in user's timezone, else UTC; null after answering 422. */
    private static ZoneId timezone(Context ctx, String userId, String userPrefUrl) throws Exception {
        String raw = ctx.queryParam("tz");
        if (raw == null && userId != null) {
            HttpResponse<String> me = SimpleHttp.get(userPrefUrl + "/users/" + ApiV1Routes.enc(userId));
            if (me.statusCode() == 200) {
                JsonElement tz = JsonParser.parseString(me.body()).getAsJsonObject().get("timezone");
                if (tz != null && !tz.isJsonNull()) raw = tz.getAsString();
            }
        }
        if (raw == null) return ZoneId.of("UTC");
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException e) {
            ApiV1Routes.write(ctx, ApiError.validation("tz", "invalid", "tz must be an IANA timezone, e.g. America/New_York."));
            return null;
        }
    }

    /** Everything the user follows: league ids, team ids, and game ids, in one set. */
    private static Set<String> follows(String userPrefUrl, String userId) throws Exception {
        HttpResponse<String> r = SimpleHttp.get(userPrefUrl + "/users/" + ApiV1Routes.enc(userId) + "/follows");
        Set<String> targets = new HashSet<>();
        if (r.statusCode() != 200) throw new IllegalStateException("follows answered " + r.statusCode());
        for (JsonElement f : JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("items")) {
            targets.add(f.getAsJsonObject().getAsJsonObject("target").get("id").getAsString());
        }
        return targets;
    }

    private static JsonArray followed(JsonArray items, Set<String> targets) {
        JsonArray out = new JsonArray();
        for (JsonElement el : items) {
            JsonObject g = el.getAsJsonObject();
            if (targets.contains(g.get("id").getAsString()) || targets.contains(g.get("leagueId").getAsString())
                || targets.contains(teamId(g, "home")) || targets.contains(teamId(g, "away"))) {
                out.add(g);
            }
        }
        return out;
    }

    private static String teamId(JsonObject game, String side) {
        return game.getAsJsonObject(side).getAsJsonObject("team").get("id").getAsString();
    }
}
