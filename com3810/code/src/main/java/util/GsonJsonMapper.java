package util;

import com.google.gson.Gson;
import io.javalin.json.JsonMapper;

import java.lang.reflect.Type;

public final class GsonJsonMapper implements JsonMapper {

    private static final Gson GSON = new Gson();

    @Override
    public String toJsonString(Object obj, Type type) {
        return GSON.toJson(obj, type);
    }

    @Override
    public <T> T fromJsonString(String json, Type targetType) {
        return GSON.fromJson(json, targetType);
    }
}
