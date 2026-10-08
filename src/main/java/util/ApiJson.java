package util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import io.javalin.http.Context;

import java.time.Instant;

/** Writes {@link ApiResult}s as the API spells them: ISO-8601 times, errors in the {@code Error} shape. */
public final class ApiJson {

    /** Java 17 blocks Gson's reflection into java.time, so Instants get an explicit adapter. */
    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (src, type, c) -> new JsonPrimitive(src.toString()))
            .serializeNulls()
            .create();

    private ApiJson() {}

    public static String toJson(Object body) {
        return GSON.toJson(body);
    }

    public static void write(Context ctx, ApiResult<?> r) {
        ctx.status(r.status()).contentType("application/json");
        if (!r.isOk()) {
            ctx.result(r.error().toJson());
        } else if (r.body() == null) {
            ctx.result("");
        } else {
            ctx.result(toJson(r.body()));
        }
    }
}
