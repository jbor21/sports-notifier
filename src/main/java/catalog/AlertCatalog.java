package catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The alert catalog in {@code catalog/alerts.json}: DESIGN.md decision 15's tables, per league. The one
 * place defaults live. New follows take their switches from here, and a switch change is checked here.
 */
public final class AlertCatalog {

    private final Map<String, List<AlertType>> byLeague;

    private AlertCatalog(Map<String, List<AlertType>> byLeague) {
        this.byLeague = byLeague;
    }

    public static AlertCatalog load() {
        try (Reader r = new InputStreamReader(
                AlertCatalog.class.getResourceAsStream("/catalog/alerts.json"), StandardCharsets.UTF_8)) {
            JsonObject leagues = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("leagues");
            Map<String, List<AlertType>> byLeague = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> lg : leagues.entrySet()) {
                List<AlertType> types = new ArrayList<>();
                for (JsonElement el : lg.getValue().getAsJsonArray()) types.add(parse(el.getAsJsonObject()));
                byLeague.put(lg.getKey(), List.copyOf(types));
            }
            return new AlertCatalog(byLeague);
        } catch (Exception e) {
            throw new IllegalStateException("catalog/alerts.json could not be read", e);
        }
    }

    /** The league's alert types in display order; empty for an unknown league. */
    public List<AlertType> forLeague(String league) {
        return byLeague.getOrDefault(league, List.of());
    }

    /** Every switch a new follow of this kind offers, set to its default. */
    public Map<String, AlertSetting> defaults(String league, FollowKind kind) {
        Map<String, AlertSetting> out = new LinkedHashMap<>();
        for (AlertType t : forLeague(league)) {
            if (t.appliesTo().contains(kind)) {
                out.put(t.type(), new AlertSetting(Boolean.TRUE.equals(t.defaults().get(kind)), Map.of()));
            }
        }
        return out;
    }

    /** The league's scoring alerts (category {@code scoring}), in catalog order. */
    public List<String> scoringTypes(String league) {
        return forLeague(league).stream().filter(t -> "scoring".equals(t.category())).map(AlertType::type).toList();
    }

    /**
     * Why this switch can't be saved on this follow: {@code unknown_type}, {@code not_for_this_follow},
     * {@code bad_option}; empty if it can.
     */
    public Optional<String> problem(String league, FollowKind kind, String type, AlertSetting setting) {
        AlertType t = forLeague(league).stream().filter(a -> a.type().equals(type)).findFirst().orElse(null);
        if (t == null) return Optional.of("unknown_type");
        if (!t.appliesTo().contains(kind)) return Optional.of("not_for_this_follow");
        for (Map.Entry<String, Object> opt : setting.options().entrySet()) {
            String optionKind = t.options().stream()
                    .filter(o -> opt.getKey().equals(o.get("key")))
                    .map(o -> o.get("kind"))
                    .findFirst().orElse(null);
            if (!validOption(optionKind, opt.getValue())) return Optional.of("bad_option");
        }
        return Optional.empty();
    }

    private static boolean validOption(String optionKind, Object value) {
        if (!"inning_set".equals(optionKind)) return false;
        // Innings 1-9; empty means every inning. JSON numbers arrive as doubles, so accept any whole number.
        if (!(value instanceof List<?> innings)) return false;
        for (Object i : innings) {
            if (!(i instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) return false;
            if (n.intValue() < 1 || n.intValue() > 9) return false;
        }
        return true;
    }

    private static AlertType parse(JsonObject o) {
        Set<FollowKind> appliesTo = EnumSet.noneOf(FollowKind.class);
        for (JsonElement k : o.getAsJsonArray("appliesTo")) appliesTo.add(FollowKind.parse(k.getAsString()).orElseThrow());
        Map<FollowKind, Boolean> defaults = new EnumMap<>(FollowKind.class);
        for (Map.Entry<String, JsonElement> d : o.getAsJsonObject("defaults").entrySet()) {
            defaults.put(FollowKind.parse(d.getKey()).orElseThrow(), d.getValue().getAsBoolean());
        }
        List<Map<String, String>> options = new ArrayList<>();
        if (o.has("options")) {
            for (JsonElement opt : o.getAsJsonArray("options")) {
                Map<String, String> m = new LinkedHashMap<>();
                opt.getAsJsonObject().entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsString()));
                options.add(m);
            }
        }
        return new AlertType(o.get("type").getAsString(), o.get("name").getAsString(),
                o.get("description").getAsString(), o.get("category").getAsString(),
                Set.copyOf(appliesTo), Map.copyOf(defaults), List.copyOf(options),
                o.get("precision").getAsString(), o.get("availability").getAsString());
    }
}
