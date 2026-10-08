package model;

import alerts.Alert;
import alerts.GameLabels;
import alerts.Play;
import catalog.Teams;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import feed.TeamIds;

import java.time.Instant;
import java.util.List;

/**
 * The API's {@code Notification} for one moment (docs/api/openapi.yaml), built by Stage 1 once per
 * distinct set of alerts. History stores it as is; {@code createdAt} is added when it is read, from
 * the history row.
 */
public final class NotificationBody {

    private NotificationBody() {}

    /**
     * One push for a moment (Phase 3.3 spec, 5.5): the first alert is the headline and its id is the
     * push's id; the rest ride along as extras. Built once per distinct set of alerts, not per user.
     */
    public static JsonObject of(Play play, List<Alert> headlineFirst, GameLabels labels) {
        Alert first = headlineFirst.get(0);
        JsonArray extras = new JsonArray();
        for (Alert a : headlineFirst.subList(1, headlineFirst.size())) extras.add(part(a));

        JsonObject game = new JsonObject();
        game.addProperty("id", play.gameId());
        game.add("home", side(play.league(), play.homeTeam(), play.homeScore(), labels.teams()));
        game.add("away", side(play.league(), play.awayTeam(), play.awayScore(), labels.teams()));
        game.addProperty("periodLabel", GameLabels.periodLabel(play));
        game.addProperty("clock", GameLabels.clock(play));

        JsonObject n = new JsonObject();
        n.addProperty("id", first.id());
        n.addProperty("kind", "game_moment");
        n.addProperty("leagueId", play.league());
        n.add("headline", part(first));
        n.add("extras", extras);
        n.add("game", game);
        n.addProperty("detectedAt", Instant.ofEpochMilli(play.detectedAtMillis()).toString());
        return n;
    }

    private static JsonObject part(Alert a) {
        JsonObject p = new JsonObject();
        p.addProperty("type", a.type());
        p.addProperty("text", a.text());
        return p;
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
