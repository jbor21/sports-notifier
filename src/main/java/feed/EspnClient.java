package feed;

import util.SimpleHttp;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * The one place that talks HTTP to ESPN, shared by the poller's live and schedule loops so both use
 * the same host and User-Agent.
 *
 * Both are settings because ESPN's filtering changes without notice. As of 2026-10-01 it refused
 * browser-imitating User-Agents, and refused site.api.espn.com from a cloud address while
 * site.web.api.espn.com answered. The daily espn-check workflow tells us when that changes.
 */
public final class EspnClient {

    public static final String DEFAULT_HOST = "site.web.api.espn.com";
    public static final String DEFAULT_USER_AGENT =
        "sports-notifier/1.0 (+https://github.com/boboborenstein-collab/sports-notifier)";

    private static final Map<String, String> SPORT_PATHS = Map.of(
        "NBA", "basketball/nba",
        "NFL", "football/nfl",
        "MLB", "baseball/mlb",
        "NHL", "hockey/nhl");

    private final String host;
    private final String userAgent;

    public EspnClient(String host, String userAgent) {
        this.host = host;
        this.userAgent = userAgent;
    }

    public static EspnClient fromEnv() {
        return new EspnClient(
            System.getenv().getOrDefault("ESPN_HOST", DEFAULT_HOST),
            System.getenv().getOrDefault("ESPN_USER_AGENT", DEFAULT_USER_AGENT));
    }

    /** Raw scoreboard JSON for {@code NBA}, {@code NFL}, {@code MLB}, or {@code NHL}: ESPN's current day. */
    public String scoreboard(String league) throws FeedBlockedException, IOException {
        return get(league, "");
    }

    /** The scoreboard for one date (ESPN's {@code dates=yyyyMMdd}), for the schedule. */
    public String scoreboard(String league, LocalDate date) throws FeedBlockedException, IOException {
        return get(league, "?dates=" + date.format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    private String get(String league, String query) throws FeedBlockedException, IOException {
        String path = SPORT_PATHS.get(league);
        if (path == null) throw new IllegalArgumentException("unknown league " + league);
        String url = "https://" + host + "/apis/site/v2/sports/" + path + "/scoreboard" + query;

        HttpResponse<String> response;
        try {
            response = SimpleHttp.get(url, Map.of("User-Agent", userAgent));
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching " + url, e);
        } catch (Exception e) {
            throw new IOException("failed fetching " + url, e);
        }

        int status = response.statusCode();
        if (status == 403 || status == 429) throw new FeedBlockedException(status, url);
        if (status != 200) throw new IOException("HTTP " + status + " from " + url);
        return response.body();
    }
}
