package util;

import com.google.gson.Gson;

import java.util.LinkedHashMap;
import java.util.Map;

/** The API's {@code Error} shape: {@code {"error": {"code", "message", "fields"?}}}, plus its HTTP status. */
public record ApiError(int status, String code, String message, Map<String, String> fields) {

    private static final Gson GSON = new Gson();

    public ApiError {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    public static ApiError of(int status, String code, String message) {
        return new ApiError(status, code, message, Map.of());
    }

    /** A 422 naming one field and its problem code. */
    public static ApiError validation(String field, String problem, String message) {
        return new ApiError(422, "validation_failed", message, Map.of(field, problem));
    }

    public String toJson() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (!fields.isEmpty()) error.put("fields", fields);
        return GSON.toJson(Map.of("error", error));
    }
}
