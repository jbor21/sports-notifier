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
}
