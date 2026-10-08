package service.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import util.ApiError;
import util.ApiJson;
import util.ApiResult;

import java.util.Map;

/** The notification service's routes. The gateway forwards /api/v1/notifications here with the session's user id. */
public final class NotificationRoutes {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private NotificationRoutes() {}

    public static void register(Javalin app, NotificationHistory history) {
        app.get("/users/{userId}/notifications", ctx -> {
            Integer limit = limit(ctx);
            if (limit == null) return;
            NotificationHistory.Page page;
            try {
                page = history.list(ctx.pathParam("userId"), ctx.queryParam("league"), ctx.queryParam("team"),
                        ctx.queryParam("game"), ctx.queryParam("cursor"), limit);
            } catch (IllegalArgumentException e) {
                ApiJson.write(ctx, ApiResult.fail(ApiError.validation("cursor", "invalid", "Use the nextCursor from the previous page.")));
                return;
            }
            JsonObject out = new JsonObject();
            JsonArray items = new JsonArray();
            page.items().forEach(items::add);
            out.add("items", items);
            out.addProperty("nextCursor", page.nextCursor());
            ctx.contentType("application/json").result(out.toString());
        });
        app.delete("/users/{userId}/notifications", ctx -> {
            history.clear(ctx.pathParam("userId"));
            ctx.status(204);
        });
        app.get("/users/{userId}/notifications/unread-count", ctx ->
                ApiJson.write(ctx, ApiResult.ok(200, Map.of("count", history.unreadCount(ctx.pathParam("userId"))))));
        app.post("/users/{userId}/notifications/seen", ctx -> {
            history.markSeen(ctx.pathParam("userId"));
            ctx.status(204);
        });
    }

    /** 1 to 100, default 20; null after answering 422. */
    private static Integer limit(Context ctx) {
        String raw = ctx.queryParam("limit");
        if (raw == null) return DEFAULT_LIMIT;
        try {
            int n = Integer.parseInt(raw);
            if (n >= 1 && n <= MAX_LIMIT) return n;
        } catch (NumberFormatException ignored) {
            // falls through to the 422
        }
        ApiJson.write(ctx, ApiResult.fail(ApiError.validation("limit", "invalid", "limit must be 1 to 100.")));
        return null;
    }
}
