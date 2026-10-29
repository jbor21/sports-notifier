package feed;

import java.util.Locale;

/**
 * Our game id: the league and the provider's event id, e.g. {@code nfl-401872948}. The poller and
 * the upcoming-games list both build it here, so a game followed from the list matches the alerts
 * the poller sends. It never names the provider, so the API's ids survive a change of feed.
 */
public final class GameIds {

    private GameIds() {}

    public static String of(String league, String eventId) {
        return league.toLowerCase(Locale.ROOT) + "-" + eventId;
    }

    /** A replayed game's id, new every run: {@code nfl-rp<runId>-401872971}. */
    public static String replay(String league, String runId, String eventId) {
        return of(league, "rp" + runId + "-" + eventId);
    }

    /**
     * True for a replayed game. Its teams are real, so only synthetic users may hear about it (#47),
     * and real users don't see it in {@code /games}. Simulated games ({@code -sim}) are not replays.
     */
    public static boolean isReplay(String gameId) {
        if (gameId == null) return false;
        int dash = gameId.indexOf('-');
        return dash >= 0 && gameId.startsWith("rp", dash + 1);
    }
}
