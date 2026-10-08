package testsupport;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import model.GameEvent;
import model.NotificationMessage;

/** A notifications-topic message with an API body, as Stage 1 writes them since 3.3. */
public final class TestMessages {

    private TestMessages() {}

    /** Body id {@code <gameId>:v<version>}, headline type = the play's type, text = its description. */
    public static NotificationMessage of(GameEvent play, String userId) {
        JsonObject headline = new JsonObject();
        headline.addProperty("type", play.getEventType());
        headline.addProperty("text", play.getDescription());
        JsonObject body = new JsonObject();
        body.addProperty("id", play.getGameId() + ":v" + play.getVersion());
        body.addProperty("kind", "game_moment");
        body.addProperty("leagueId", play.getLeague());
        body.add("headline", headline);
        body.add("extras", new JsonArray());
        return NotificationMessage.of(play, userId, body);
    }
}
