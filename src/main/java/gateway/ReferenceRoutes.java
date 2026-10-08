package gateway;

import catalog.AlertCatalog;
import catalog.AlertType;
import catalog.FollowKind;
import catalog.Leagues;
import catalog.Teams;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import util.ApiError;

import java.util.Comparator;
import java.util.Map;

/**
 * {@code /leagues}, {@code /leagues/{id}/teams}, and {@code /leagues/{id}/alerts}: reference data the
 * gateway serves itself from the classpath, with no Redis or service hop. Public and cacheable.
 */
public final class ReferenceRoutes {

    private static final String CACHE = "public, max-age=3600";
    private static final Gson GSON = new Gson();

    private ReferenceRoutes() {}

    public static void register(Javalin app, Leagues leagues, Teams teams, AlertCatalog catalog) {
        app.get("/api/v1/leagues", ctx -> {
            JsonArray items = new JsonArray();
            leagues.all().forEach(items::add);
            ok(ctx, items);
        });
        app.get("/api/v1/leagues/{leagueId}/teams", ctx -> {
            String league = ctx.pathParam("leagueId");
            if (!known(ctx, leagues, league)) return;
            JsonArray items = new JsonArray();
            teams.all().stream().filter(t -> t.league().equals(league))
                .sorted(Comparator.comparing(Teams.Team::displayName))
                .forEach(t -> items.add(team(t)));
            ok(ctx, items);
        });
        app.get("/api/v1/leagues/{leagueId}/alerts", ctx -> {
            String league = ctx.pathParam("leagueId");
            if (!known(ctx, leagues, league)) return;
            JsonArray items = new JsonArray();
            catalog.forLeague(league).forEach(a -> items.add(alert(a)));
            ok(ctx, items);
        });
    }

    private static boolean known(Context ctx, Leagues leagues, String league) {
        if (leagues.exists(league)) return true;
        ApiV1Routes.write(ctx, ApiError.of(404, "not_found", "No such league."));
        return false;
    }

    private static void ok(Context ctx, JsonArray items) {
        JsonObject out = new JsonObject();
        out.add("items", items);
        ctx.header("Cache-Control", CACHE).contentType("application/json").result(out.toString());
    }

    private static JsonObject team(Teams.Team t) {
        JsonObject o = new JsonObject();
        o.addProperty("id", t.id());
        o.addProperty("leagueId", t.league());
        o.addProperty("abbreviation", t.abbreviation());
        o.addProperty("location", t.location());
        o.addProperty("name", t.name());
        o.addProperty("displayName", t.displayName());
        o.addProperty("logoUrl", t.logoUrl());
        JsonObject colors = new JsonObject();
        colors.addProperty("primary", t.primaryColor());
        colors.addProperty("secondary", t.secondaryColor());
        o.add("colors", colors);
        return o;
    }

    /** The API's AlertType: follow kinds in their API spelling ({@code team}, not {@code TEAM}). */
    private static JsonObject alert(AlertType a) {
        JsonObject o = new JsonObject();
        o.addProperty("type", a.type());
        o.addProperty("name", a.name());
        o.addProperty("description", a.description());
        o.addProperty("category", a.category());
        JsonArray appliesTo = new JsonArray();
        a.appliesTo().stream().sorted().forEach(k -> appliesTo.add(k.api()));
        o.add("appliesTo", appliesTo);
        JsonObject defaults = new JsonObject();
        for (Map.Entry<FollowKind, Boolean> d : a.defaults().entrySet()) defaults.addProperty(d.getKey().api(), d.getValue());
        o.add("defaults", defaults);
        if (a.options() != null && !a.options().isEmpty()) o.add("options", GSON.toJsonTree(a.options()));
        o.addProperty("precision", a.precision());
        o.addProperty("availability", a.availability());
        return o;
    }
}
