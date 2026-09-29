package  model;

/**
 * Represents a notification delivered to a user about a game event.
 * Carries league/team context so the frontend can do live preference discovery
 * without a separate API call.
 */
public class Notification {

    /** Identifier of the user who should receive this notification. */
    private String userId;
    /** Identifier of the game that triggered this notification. */
    private String gameId;
    /** Human-readable notification text delivered to the user. */
    private String message;
    /** Unix epoch milliseconds when the notification was created; used for newest-first ordering. */
    private long timestamp;
    /** League of the game (e.g. {@code NBA}), carried for frontend preference display. */
    private String league;
    /** Abbreviated home team name, carried for frontend preference display. */
    private String homeTeam;
    /** Abbreviated away team name, carried for frontend preference display. */
    private String awayTeam;

    public Notification() {}

    public Notification(String userId, String gameId, String message, long timestamp) {
        this.userId = userId;
        this.gameId = gameId;
        this.message = message;
        this.timestamp = timestamp;
    }

    public Notification(String userId, String gameId, String message, long timestamp,
                        String league, String homeTeam, String awayTeam) {
        this.userId = userId;
        this.gameId = gameId;
        this.message = message;
        this.timestamp = timestamp;
        this.league = league;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
    }

    public String getUserId()   { return userId; }
    public String getGameId()   { return gameId; }
    public String getMessage()  { return message; }
    public long   getTimestamp(){ return timestamp; }
    public String getLeague()   { return league; }
    public String getHomeTeam() { return homeTeam; }
    public String getAwayTeam() { return awayTeam; }
}