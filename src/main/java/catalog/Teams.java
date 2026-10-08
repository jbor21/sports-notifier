package catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** The team list in {@code catalog/teams.json}, written by {@code scripts/fetch-teams.py}. */
public final class Teams {

    /**
     * {@code shortName} is what alert text calls the team ("Red Sox"); the abbreviation if teams.json has none.
     * Location, name, logo, and colors are for the UI; null where teams.json has none.
     */
    public record Team(String id, String league, String abbreviation, String displayName, String shortName,
                       String location, String name, String logoUrl, String primaryColor, String secondaryColor) {}

    private final Map<String, Team> byId;

    private Teams(Map<String, Team> byId) {
        this.byId = byId;
    }

    public static Teams load() {
        try (Reader r = new InputStreamReader(
                Teams.class.getResourceAsStream("/catalog/teams.json"), StandardCharsets.UTF_8)) {
            Map<String, Team> byId = new LinkedHashMap<>();
            for (JsonElement el : JsonParser.parseReader(r).getAsJsonObject().getAsJsonArray("teams")) {
                JsonObject t = el.getAsJsonObject();
                JsonObject colors = t.has("colors") ? t.getAsJsonObject("colors") : null;
                Team team = new Team(t.get("id").getAsString(), t.get("league").getAsString(),
                        t.get("abbreviation").getAsString(), t.get("displayName").getAsString(),
                        t.has("shortName") ? t.get("shortName").getAsString() : t.get("abbreviation").getAsString(),
                        str(t, "location"), str(t, "name"), str(t, "logoUrl"), str(colors, "primary"), str(colors, "secondary"));
                byId.put(team.id(), team);
            }
            return new Teams(byId);
        } catch (Exception e) {
            throw new IllegalStateException("catalog/teams.json could not be read", e);
        }
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    public Optional<Team> find(String teamId) {
        return teamId == null ? Optional.empty() : Optional.ofNullable(byId.get(teamId));
    }

    public Collection<Team> all() {
        return byId.values();
    }

    public Map<String, Long> countsByLeague() {
        Map<String, Long> counts = new TreeMap<>();
        byId.values().forEach(t -> counts.merge(t.league(), 1L, Long::sum));
        return counts;
    }
}
