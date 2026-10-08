package catalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** One switch on a follow: on or off, plus the alert's options (e.g. {@code innings: [3, 6, 9]}). */
public record AlertSetting(boolean enabled, Map<String, Object> options) {
    public AlertSetting {
        options = options == null ? Map.of() : Map.copyOf(normalized(options));
    }

    /**
     * Innings as sorted, distinct whole numbers. Gson reads every JSON number as a double, so without
     * this a saved {@code 9} came back as {@code 9.0} and could never name the set {@code END_OF_INNING:9}.
     */
    private static Map<String, Object> normalized(Map<String, Object> options) {
        if (!(options.get("innings") instanceof List<?> innings)) return options;
        TreeSet<Integer> whole = new TreeSet<>();
        for (Object i : innings) {
            // Anything else stays as sent, so the catalog's validation rejects it with a 422.
            if (!(i instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) return options;
            whole.add(n.intValue());
        }
        Map<String, Object> copy = new HashMap<>(options);
        copy.put("innings", List.copyOf(whole));
        return copy;
    }

    /** The chosen innings; empty means every inning. */
    public List<Integer> innings() {
        Object v = options.get("innings");
        return v instanceof List<?> l ? l.stream().map(o -> ((Number) o).intValue()).toList() : List.of();
    }
}
