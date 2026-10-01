package model;

/**
 * Represents a live event occurring during a sports game.
 * New fields (homeTeam, awayTeam, homeScore, awayScore, gameStatus, league)
 * carry full state so GameStateServiceImpl can update deterministically.
 * Not every field is meaningful for every eventType — unused fields default
 * to null/0 when deserialized from JSON via Gson.
 */
public class GameEvent {
    /** Unique identifier for the game this event belongs to. */
    private String gameId;
    /** Unique identifier for this specific event; used as the dedup key. */
    private String eventId;
    /** Type of event: {@code GAME_START}, {@code SCORE_UPDATE}, or {@code GAME_END}. */
    private String eventType;
    /** Abbreviated name of the team that triggered the event (e.g. {@code LAL}). */
    private String team;
    /** Unix epoch milliseconds when the event occurred; used for staleness ordering. */
    private long timestamp;
    /** Human-readable description shown in notification messages. */
    private String description;
    /** Abbreviated name of the home team (e.g. {@code LAL}). */
    private String homeTeam;
    /** Abbreviated name of the away team (e.g. {@code BOS}). */
    private String awayTeam;
    /** Home team score at the time of this event. */
    private int homeScore;
    /** Away team score at the time of this event. */
    private int awayScore;
    /** Game status string, e.g. {@code IN_PROGRESS} or {@code FINAL}. */
    private String gameStatus;
    /** League identifier, e.g. {@code NBA}, {@code NFL}. */
    private String league;

    public GameEvent() {}

    public GameEvent(String gameId, String eventId, String eventType, String team,
                     long timestamp, String description,
                     String homeTeam, String awayTeam,
                     int homeScore, int awayScore,
                     String gameStatus, String league) {
        this.gameId = gameId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.team = team;
        this.timestamp = timestamp;
        this.description = description;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.gameStatus = gameStatus;
        this.league = league;
    }

    public String getGameId() { return gameId; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getTeam() { return team; }
    public long getTimestamp() { return timestamp; }
    public String getDescription() { return description; }
    public String getHomeTeam() { return homeTeam; }
    public String getAwayTeam() { return awayTeam; }
    public int getHomeScore() { return homeScore; }
    public int getAwayScore() { return awayScore; }
    public String getGameStatus() { return gameStatus; }
    public String getLeague() { return league; }
}
