package gateway;

import catalog.AlertCatalog;
import catalog.AlertType;
import catalog.Leagues;
import catalog.Teams;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** /leagues, teams, and the alert catalog, served by the gateway from the classpath (Phase 3.4 spec, 6). */
class ReferenceRoutesTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private Javalin gateway;
    private String base;

    @BeforeEach
    void setUp() {
        gateway = Javalin.create().start(0);
        ReferenceRoutes.register(gateway, Leagues.load(), Teams.load(), AlertCatalog.load());
        base = "http://localhost:" + gateway.port() + "/api/v1";
    }

    @AfterEach
    void tearDown() {
        gateway.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonArray items(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("items");
    }

    @Test
    void leaguesListsFour() throws Exception {
        HttpResponse<String> r = get("/leagues");
        assertEquals(200, r.statusCode());
        List<String> ids = new ArrayList<>();
        for (JsonElement el : items(r)) {
            JsonObject l = el.getAsJsonObject();
            ids.add(l.get("id").getAsString());
            assertTrue(l.has("name") && l.has("sport") && l.has("periodName"), l.toString());
        }
        assertEquals(List.of("NFL", "NBA", "MLB", "NHL"), ids);
        assertEquals(9, items(r).get(2).getAsJsonObject().get("regulationPeriods").getAsInt());
    }

    @Test
    void teamsAlphabetical() throws Exception {
        JsonArray teams = items(get("/leagues/NFL/teams"));
        assertEquals(32, teams.size());
        String last = "";
        for (JsonElement el : teams) {
            JsonObject t = el.getAsJsonObject();
            String name = t.get("displayName").getAsString();
            assertTrue(name.compareTo(last) >= 0, name + " after " + last);
            last = name;
            assertEquals("NFL", t.get("leagueId").getAsString());
            assertTrue(t.get("logoUrl").getAsString().startsWith("https://"));
            assertTrue(t.getAsJsonObject("colors").get("primary").getAsString().startsWith("#"));
        }
    }

    @Test
    void alertsInCatalogOrder() throws Exception {
        JsonArray alerts = items(get("/leagues/NFL/alerts"));
        List<String> expected = AlertCatalog.load().forLeague("NFL").stream().map(AlertType::type).toList();
        List<String> got = new ArrayList<>();
        for (JsonElement el : alerts) got.add(el.getAsJsonObject().get("type").getAsString());
        assertEquals(expected, got);
        for (JsonElement el : alerts) {
            JsonObject a = el.getAsJsonObject();
            if (a.get("type").getAsString().equals("DAILY_SUMMARY")) {
                assertEquals("coming_soon", a.get("availability").getAsString());
            }
            assertTrue(a.has("defaults") && a.has("appliesTo") && a.has("precision"), a.toString());
        }
    }

    @Test
    void unknownLeague404() throws Exception {
        for (String path : List.of("/leagues/XFL/teams", "/leagues/XFL/alerts")) {
            HttpResponse<String> r = get(path);
            assertEquals(404, r.statusCode(), path);
            assertTrue(JsonParser.parseString(r.body()).getAsJsonObject().has("error"), r.body());
        }
    }

    @Test
    void cacheHeader() throws Exception {
        for (String path : List.of("/leagues", "/leagues/NBA/teams", "/leagues/NBA/alerts")) {
            assertEquals("public, max-age=3600", get(path).headers().firstValue("Cache-Control").orElse(""), path);
        }
    }
}
