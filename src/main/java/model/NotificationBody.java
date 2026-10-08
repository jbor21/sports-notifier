package model;

import catalog.Teams;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import feed.TeamIds;

import java.time.Instant;

/**
 * The API's {@code Notification} for one play (docs/api/openapi.yaml), built once per play by Stage 1.
 * History stores it as is; {@code createdAt} is added when it is read, from the history row.
 */
public final class NotificationBody {

    private NotificationBody() {}

    public static JsonObject of(GameEvent play, String headlineType, Teams teams) {
        JsonObject headline = new JsonObject();
        headline.addProperty("type", headlineType);
        headline.addProperty("text", play.getDescription());

        JsonObject game = new JsonObject();
        game.addProperty("id", play.getGameId());
        game.add("home", side(play.getLeague(), play.getHomeTeam(), play.getHomeScore(), teams));
        game.add("away", side(play.getLeague(), play.getAwayTeam(), play.getAwayScore(), teams));
        game.add("periodLabel", null);
        game.add("clock", null);

        JsonObject n = new JsonObject();
        n.addProperty("id", play.getGameId() + ":v" + play.getVersion());
        n.addProperty("kind", "game_moment");
        n.addProperty("leagueId", play.getLeague());
        n.add("headline", headline);
        n.add("extras", new JsonArray());
        n.add("game", game);
        n.addProperty("detectedAt", Instant.ofEpochMilli(play.getTimestamp()).toString());
        return n;
    }

    private static JsonObject side(String league, String abbreviation, int score, Teams teams) {
        String id = TeamIds.of(league, abbreviation);
        JsonObject team = new JsonObject();
        team.addProperty("id", id);
        team.addProperty("abbreviation", abbreviation);
        team.addProperty("displayName", teams.find(id).map(Teams.Team::displayName).orElse(abbreviation));
        JsonObject side = new JsonObject();
        side.add("team", team);
        side.addProperty("score", score);
        return side;
    }
}
