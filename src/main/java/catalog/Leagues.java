package catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The launch leagues from catalog/leagues.json, in the API's {@code League} shape, in display order. */
public final class Leagues {

    private final List<JsonObject> all;

    private Leagues(List<JsonObject> all) {
        this.all = all;
    }

    public static Leagues load() {
        try (Reader r = new InputStreamReader(
                Leagues.class.getResourceAsStream("/catalog/leagues.json"), StandardCharsets.UTF_8)) {
            List<JsonObject> all = new ArrayList<>();
            for (JsonElement el : JsonParser.parseReader(r).getAsJsonObject().getAsJsonArray("leagues")) {
                all.add(el.getAsJsonObject());
            }
            return new Leagues(List.copyOf(all));
        } catch (Exception e) {
            throw new IllegalStateException("catalog/leagues.json could not be read", e);
        }
    }

    public List<JsonObject> all() {
        return all.stream().map(JsonObject::deepCopy).toList();
    }

    public boolean exists(String id) {
        return all.stream().anyMatch(l -> l.get("id").getAsString().equals(id));
    }
}
