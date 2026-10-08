package feed;

/**
 * Team ids are league-qualified ({@code mlb-chw}): an abbreviation alone is ambiguous, since BOS is
 * the Celtics, the Red Sox, and the Bruins. The only place these ids are built.
 */
public final class TeamIds {

    private TeamIds() {}

    /** {@code "<league>-<abbreviation>"} in lower case, or null if either part is missing. */
    public static String of(String league, String abbreviation) {
        if (league == null || league.isBlank() || abbreviation == null || abbreviation.isBlank()) return null;
        return league.trim().toLowerCase() + "-" + abbreviation.trim().toLowerCase();
    }
}
