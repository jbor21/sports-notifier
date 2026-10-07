package catalog;

import java.util.Map;

/** One switch on a follow: on or off, plus the alert's options (e.g. {@code innings: [3, 6, 9]}). */
public record AlertSetting(boolean enabled, Map<String, Object> options) {
    public AlertSetting {
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
