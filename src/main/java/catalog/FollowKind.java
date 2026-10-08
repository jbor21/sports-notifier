package catalog;

import java.util.Optional;

/** What a follow points at. The API spells these in lower case. */
public enum FollowKind {
    LEAGUE, TEAM, GAME;

    public static Optional<FollowKind> parse(String raw) {
        if (raw == null) return Optional.empty();
        for (FollowKind k : values()) {
            if (k.api().equals(raw)) return Optional.of(k);
        }
        return Optional.empty();
    }

    /** The API's spelling: {@code league}, {@code team}, {@code game}. */
    public String api() {
        return name().toLowerCase();
    }
}
