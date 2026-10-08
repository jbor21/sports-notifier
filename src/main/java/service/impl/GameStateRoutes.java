package service.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.GameQuery;
import io.javalin.Javalin;
import io.javalin.http.Context;
import service.GameStateService;
import util.ApiError;
import util.ApiJson;
import util.ApiResult;

/**
 * Game State's routes. The gateway turns {@code /api/v1/games?date=&tz=} into the millisecond range
 * asked for here and checks the date; this service only reads.
 */
public final class GameStateRoutes {

    private GameStateRoutes() {}

    public static void register(Javalin app, GameStateService svc) {
        app.get("/games", ctx -> {
            Long from = millis(ctx, "from"), to = from == null ? null : millis(ctx, "to");
            if (from == null || to == null) return;
            JsonArray items = new JsonArray();
            svc.listDay(new GameQuery(from, to, ctx.queryParam("league"), ctx.queryParam("team"), ctx.queryParam("status")))
                .forEach(items::add);
            JsonObject out = new JsonObject();
            out.add("items", items);
            ctx.contentType("application/json").result(out.toString());
        });
        app.get("/games/{gameId}", ctx -> svc.detail(ctx.pathParam("gameId")).ifPresentOrElse(
            d -> ctx.contentType("application/json").result(d.toString()),
            () -> ApiJson.write(ctx, ApiResult.fail(ApiError.of(404, "not_found", "No such game.")))));
    }

    /** A required epoch-millis query parameter; null after answering 422. */
    private static Long millis(Context ctx, String name) {
        try {
            return Long.parseLong(ctx.queryParam(name));
        } catch (RuntimeException e) {
            ApiJson.write(ctx, ApiResult.fail(ApiError.validation(name, "invalid", name + " must be epoch milliseconds.")));
            return null;
        }
    }
}
