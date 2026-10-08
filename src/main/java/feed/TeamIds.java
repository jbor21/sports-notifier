package feed;

/**
 * Team ids are league-qualified ({@code mlb-chw}): an abbreviation alone is ambiguous, since BOS is
 * the Celtics, the Red Sox, and the Bruins. The only place these ids are built.
 */
public final class TeamIds {

    /**
     * Scoreboard abbreviations that differ from ESPN's team list (teams.json), checked against every
     * team in 60 hours of recordings: the NHL scoreboard says UTA for the team its list calls UTAH.
     */
    private static final java.util.Map<String, String> ALIASES = java.util.Map.of("nhl-uta", "nhl-utah");

    private TeamIds() {}

    /** {@code "<league>-<abbreviation>"} in lower case, or null if either part is missing. */
    public static String of(String league, String abbreviation) {
        if (league == null || league.isBlank() || abbreviation == null || abbreviation.isBlank()) return null;
        String id = league.trim().toLowerCase() + "-" + abbreviation.trim().toLowerCase();
        return ALIASES.getOrDefault(id, id);
    }
}
