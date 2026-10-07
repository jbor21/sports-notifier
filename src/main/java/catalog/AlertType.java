package catalog;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** One row of the alert catalog (DESIGN.md decision 15), as {@code GET /leagues/{id}/alerts} returns it. */
public record AlertType(String type, String name, String description, String category,
                        Set<FollowKind> appliesTo, Map<FollowKind, Boolean> defaults,
                        List<Map<String, String>> options, String precision, String availability) {
}
