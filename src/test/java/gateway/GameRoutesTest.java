package gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import util.RedisKeys;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** /api/v1/games in the gateway: the day range, tz, and following (Phase 3.4 spec, 5.3). */
class GameRoutesTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    private JedisPool pool;
    private Jedis jedis;
    private Javalin gameState;
    private Javalin userPref;
    private Javalin gateway;
    private String base;
    private final List<String> gameStateQueries = new CopyOnWriteArrayList<>();
    private String sid;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        gameState = Javalin.create().start(0);
        gameState.get("/games", ctx -> {
            gameStateQueries.add(ctx.queryString());
            ctx.contentType("application/json").result("{\"items\":["
                + game("nfl-1", "NFL", "nfl-chi", "nfl-lv") + "," + game("nfl-2", "NFL", "nfl-kc", "nfl-buf") + ","
                + game("nba-1", "NBA", "nba-bos", "nba-lal") + "," + game("mlb-1", "MLB", "mlb-nyy", "mlb-bos") + "]}");
        });
        gameState.get("/games/{id}", ctx -> ctx.contentType("application/json").result(game(ctx.pathParam("id"), "NFL", "nfl-chi", "nfl-lv")));
        userPref = Javalin.create().start(0);
        userPref.get("/users/{id}", ctx -> ctx.contentType("application/json")
            .result("{\"id\":\"" + ctx.pathParam("id") + "\",\"timezone\":\"America/Los_Angeles\"}"));
        userPref.get("/users/{id}/follows", ctx -> ctx.contentType("application/json").result("{\"items\":["
            + "{\"target\":{\"kind\":\"team\",\"id\":\"nfl-lv\"}},"
            + "{\"target\":{\"kind\":\"league\",\"id\":\"NBA\"}},"
            + "{\"target\":{\"kind\":\"game\",\"id\":\"mlb-1\"}}]}"));
        gateway = Javalin.create().start(0);
        GameRoutes.register(gateway, pool, "http://localhost:" + gameState.port(), "http://localhost:" + userPref.port(), () -> NOW);
        base = "http://localhost:" + gateway.port() + "/api/v1";
        sid = UUID.randomUUID().toString();
        jedis.set(RedisKeys.SESSION_PREFIX + sid, "u1");
    }

    @AfterEach
    void tearDown() {
        gateway.stop();
        userPref.stop();
        gameState.stop();
        jedis.close();
        pool.close();
    }

    private static String game(String id, String league, String home, String away) {
        return "{\"id\":\"" + id + "\",\"leagueId\":\"" + league + "\",\"home\":{\"team\":{\"id\":\"" + home
            + "\"}},\"away\":{\"team\":{\"id\":\"" + away + "\"}}}";
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (session != null) b.header("Cookie", "sid=" + session);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> ids(String body) {
        JsonArray items = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("items");
        return items.asList().stream().map(e -> e.getAsJsonObject().get("id").getAsString()).toList();
    }

    @Test
    void followingNeedsSession() throws Exception {
        assertEquals(401, get("/games?following=true", null).statusCode());
    }

    @Test
    void followingKeepsFollowedGames() throws Exception {
        HttpResponse<String> r = get("/games?following=true&tz=UTC", sid);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(List.of("nfl-1", "nba-1", "mlb-1"), ids(r.body()));
    }

    @Test
    void tzDefaultsToUsersTimezone() throws Exception {
        HttpResponse<String> r = get("/games", sid);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("2026-10-08", JsonParser.parseString(r.body()).getAsJsonObject().get("date").getAsString());
        long from = Instant.parse("2026-10-08T07:00:00Z").toEpochMilli();
        assertTrue(gameStateQueries.get(0).contains("from=" + from), gameStateQueries.get(0));
    }

    @Test
    void signedOutDefaultsToUtc() throws Exception {
        assertEquals(200, get("/games", null).statusCode());
        assertTrue(gameStateQueries.get(0).contains("from=" + Instant.parse("2026-10-08T00:00:00Z").toEpochMilli()));
    }

    @Test
    void outOfRangeIs422() throws Exception {
        HttpResponse<String> r = get("/games?date=2026-10-20&tz=UTC", null);
        assertEquals(422, r.statusCode());
        JsonObject err = JsonParser.parseString(r.body()).getAsJsonObject();
        assertTrue(err.toString().contains("2026-10-15"), err.toString());
    }

    @Test
    void badTzOrDateIs422() throws Exception {
        assertEquals(422, get("/games?tz=Mars/Base", null).statusCode());
        assertEquals(422, get("/games?date=yesterday", null).statusCode());
    }

    @Test
    void filtersPassThrough() throws Exception {
        get("/games?tz=UTC&league=NFL&team=nfl-chi&status=final", null);
        String q = gameStateQueries.get(0);
        assertTrue(q.contains("league=NFL") && q.contains("team=nfl-chi") && q.contains("status=final"), q);
    }

    @Test
    void detailForwards() throws Exception {
        HttpResponse<String> r = get("/games/nfl-9", null);
        assertEquals(200, r.statusCode());
        assertEquals("nfl-9", JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString());
    }
}
