package util;

import com.google.gson.Gson;
import model.GameEvent;

public class GameEventParser {

    private static final Gson GSON = new Gson();

    public static GameEvent parse(String json) {
        return GSON.fromJson(json, GameEvent.class);
    }

    public static String toJson(GameEvent event) {
        return GSON.toJson(event);
    }
}
