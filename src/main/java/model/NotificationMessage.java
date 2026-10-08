package model;

import alerts.Play;
import com.google.gson.JsonObject;

/**
 * One user's notification about one play, as Stage 1 writes it to the {@code notifications} topic
 * (keyed by {@code userId}) and Stage 2 delivers it.
 */
public class NotificationMessage {

    /** The push's id: its headline alert's id, e.g. {@code nfl-1:SCORE:away:2} (3.1-style messages:
     *  {@code <gameId>:v<version>}). With {@code userId} it is Stage 2's dedup key, so a re-sent or
     *  re-detected message is delivered once. */
    private String messageId;
    /** Who receives it; also the Kafka key, so one user's messages stay in one lane, in order. */
    private String userId;
    /** The game the play belongs to. */
    private String gameId;
    /** The play's type, e.g. {@code SCORE_UPDATE}. */
    private String eventType;
    /** Text shown to the user. */
    private String message;
    /** League of the game, e.g. {@code NBA}. */
    private String league;
    /** Home team abbreviation. */
    private String homeTeam;
    /** Away team abbreviation. */
    private String awayTeam;
    /** Home score at this play. */
    private int homeScore;
    /** Away score at this play. */
    private int awayScore;
    /** Game status at this play, e.g. {@code IN_PROGRESS}. */
    private String status;
    /** When the poller saw the play (epoch millis), for measuring delay end to end. */
    private long detectedAt;
    /** The API's {@code Notification} (see {@link NotificationBody}): what history stores and the new UI reads. */
    private JsonObject notification;

    public NotificationMessage() {}

    /** The message for {@code userId} about an accepted {@code play}. */
    public static NotificationMessage of(GameEvent play, String userId, JsonObject notification) {
        NotificationMessage m = of(play, userId);
        m.notification = notification;
        return m;
    }

    /** {@code userId}'s push about a moment: {@code body} is a {@link NotificationBody}, its id the message id. */
    public static NotificationMessage of(Play play, String userId, JsonObject body) {
        NotificationMessage m = new NotificationMessage();
        JsonObject headline = body.getAsJsonObject("headline");
        m.messageId = body.get("id").getAsString();
        m.userId = userId;
        m.gameId = play.gameId();
        m.eventType = headline.get("type").getAsString();
        m.message = headline.get("text").getAsString();
        m.league = play.league();
        m.homeTeam = play.homeTeam();
        m.awayTeam = play.awayTeam();
        m.homeScore = play.homeScore();
        m.awayScore = play.awayScore();
        m.status = play.status();
        m.detectedAt = play.detectedAtMillis();
        m.notification = body;
        return m;
    }

    public static NotificationMessage of(GameEvent play, String userId) {
        NotificationMessage m = new NotificationMessage();
        m.messageId = play.getGameId() + ":v" + play.getVersion();
        m.userId = userId;
        m.gameId = play.getGameId();
        m.eventType = play.getEventType();
        m.message = play.getDescription();
        m.league = play.getLeague();
        m.homeTeam = play.getHomeTeam();
        m.awayTeam = play.getAwayTeam();
        m.homeScore = play.getHomeScore();
        m.awayScore = play.getAwayScore();
        m.status = play.getGameStatus();
        m.detectedAt = play.getTimestamp();
        return m;
    }

    public String getMessageId() { return messageId; }
    public String getUserId() { return userId; }
    public String getGameId() { return gameId; }
    public String getEventType() { return eventType; }
    public String getMessage() { return message; }
    public String getLeague() { return league; }
    public String getHomeTeam() { return homeTeam; }
    public String getAwayTeam() { return awayTeam; }
    public int getHomeScore() { return homeScore; }
    public int getAwayScore() { return awayScore; }
    public String getStatus() { return status; }
    public long getDetectedAt() { return detectedAt; }
    public JsonObject getNotification() { return notification; }
}
