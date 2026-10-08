package model;

import java.util.List;

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
    /** Type of event: {@code GAME_START}, {@code SCORE_UPDATE}, {@code GAME_END}, or {@code GAME_SYNC} (state only, no notification). */
    private String eventType;
    /** Abbreviated name of the team that triggered the event (e.g. {@code LAL}). */
    private String team;
    /** Unix epoch milliseconds when the event was observed; for display only (ordering uses {@code seq}). */
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
    /** Position in the game; bigger is later. Orders events for the Lua script. See {@code feed.Seq}. */
    private long seq;
    /**
     * The game's accept counter, set by the Lua script when it accepts this play: 1, 2, 3, ... per
     * game. 0 until accepted. Everything after the script orders and dedups plays by it, not by
     * {@code seq}, because healing a bad ESPN clock can lower {@code seq}.
     */
    private long version;
    /** Before, during, or after the game: {@code PRE}, {@code LIVE}, or {@code FINAL}. Null if the sender didn't say. */
    private String phase;
    /** Quarter, period, or inning; overtime keeps counting. 0 before the game. */
    private int period;
    /** Tenths of a second left in the period; 0 for MLB; -1 if the feed sent no clock. */
    private int clockTenths;
    /** MLB half-inning: {@code TOP}, {@code MIDDLE}, {@code BOTTOM}, {@code END}; {@code TOP} elsewhere. */
    private String half;
    /** MLB outs in the half-inning. */
    private int outs;
    /** {@code IN_PLAY}, {@code DELAYED}, {@code END_PERIOD}, or {@code HALFTIME}. */
    private String stage;
    /** 1 preseason, 2 regular season, 3 postseason. */
    private int seasonType;
    /** The feed's short status text, e.g. {@code Bot 4th}; null if none. */
    private String detail;
    /** Scheduled start, epoch milliseconds; 0 if unknown. Saved in game:<id> for the game lists. */
    private long startTime;
    /** Points per period so far; saved with the score in one step, so the two always add up. */
    private List<LinePeriod> lineScore;

    public GameEvent() {}

    public GameEvent(String gameId, String eventId, String eventType, String team,
                     long timestamp, String description,
                     String homeTeam, String awayTeam,
                     int homeScore, int awayScore,
                     String gameStatus, String league) {
        this(gameId, eventId, eventType, team, timestamp, description,
             homeTeam, awayTeam, homeScore, awayScore, gameStatus, league, 0L);
    }

    public GameEvent(String gameId, String eventId, String eventType, String team,
                     long timestamp, String description,
                     String homeTeam, String awayTeam,
                     int homeScore, int awayScore,
                     String gameStatus, String league, long seq) {
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
        this.seq = seq;
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
    public long getSeq() { return seq; }
    public long getVersion() { return version; }
    public String getPhase() { return phase; }
    public int getPeriod() { return period; }
    public int getClockTenths() { return clockTenths; }
    public String getHalf() { return half; }
    public int getOuts() { return outs; }
    public String getStage() { return stage; }
    public int getSeasonType() { return seasonType; }
    public String getDetail() { return detail; }
    public long getStartTime() { return startTime; }
    public List<LinePeriod> getLineScore() { return lineScore == null ? List.of() : lineScore; }

    /** A copy of this play with its accept counter set. */
    public GameEvent withVersion(long version) {
        GameEvent copy = copy();
        copy.version = version;
        return copy;
    }

    /** A copy of this play with its position in the game set. */
    public GameEvent withPosition(String phase, int period, int clockTenths, String half, int outs,
                                  String stage, int seasonType, String detail) {
        GameEvent copy = copy();
        copy.phase = phase;
        copy.period = period;
        copy.clockTenths = clockTenths;
        copy.half = half;
        copy.outs = outs;
        copy.stage = stage;
        copy.seasonType = seasonType;
        copy.detail = detail;
        return copy;
    }

    /** A copy of this play with its scheduled start and line score set. */
    public GameEvent withSchedule(long startTime, List<LinePeriod> lineScore) {
        GameEvent copy = copy();
        copy.startTime = startTime;
        copy.lineScore = lineScore == null ? List.of() : List.copyOf(lineScore);
        return copy;
    }

    private GameEvent copy() {
        GameEvent c = new GameEvent(gameId, eventId, eventType, team, timestamp, description,
            homeTeam, awayTeam, homeScore, awayScore, gameStatus, league, seq);
        c.version = version;
        c.phase = phase;
        c.period = period;
        c.clockTenths = clockTenths;
        c.half = half;
        c.outs = outs;
        c.stage = stage;
        c.seasonType = seasonType;
        c.detail = detail;
        c.startTime = startTime;
        c.lineScore = lineScore;
        return c;
    }
}
