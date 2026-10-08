package gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.impl.HistoryStore;
import service.impl.NotificationHistory;
import service.impl.NotificationRoutes;
import service.impl.UnreadCounter;
import testsupport.PostgresTestSupport;
import util.RedisKeys;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** /api/v1/notifications through the gateway to the notification service, on real Postgres and Redis. */
class ApiV1NotificationsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private JedisPool pool;
    private Jedis jedis;
    private Javalin notification;
    private Javalin gateway;
    private String base;
    private String bob;
    private String sid;

    @BeforeEach
    void setUp() throws Exception {
        PostgresTestSupport.truncateAll();
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        notification = Javalin.create().start(0);
        NotificationRoutes.register(notification, new NotificationHistory(PostgresTestSupport.dataSource()), new UnreadCounter(pool));
        gateway = Javalin.create().start(0);
        ApiV1Routes.register(gateway, pool, new LoginThrottle(pool), "http://localhost:1",
                "http://localhost:" + notification.port());
        base = "http://localhost:" + gateway.port() + "/api/v1";
        bob = UUID.randomUUID().toString();
        sid = UUID.randomUUID().toString();
        jedis.set(RedisKeys.SESSION_PREFIX + sid, bob);
        new HistoryStore(PostgresTestSupport.dataSource()).insertBatch(List.of(new HistoryStore.HistoryRow(
                bob, "mlb-1:v1", Instant.now(), "MLB", "mlb-1", "mlb-chw", "mlb-cle",
                "{\"id\":\"mlb-1:v1\",\"kind\":\"game_moment\",\"leagueId\":\"MLB\"}")));
    }

    @AfterEach
    void tearDown() {
        gateway.stop();
        notification.stop();
        jedis.close();
        pool.close();
    }

    @Test
    void listIsTheApiPage() throws Exception {
        HttpResponse<String> r = send("GET", "/notifications?limit=5", sid);
        assertEquals(200, r.statusCode());
        JsonObject page = JsonParser.parseString(r.body()).getAsJsonObject();
        assertEquals(1, page.getAsJsonArray("items").size());
        assertTrue(page.get("nextCursor").isJsonNull());
        assertEquals("mlb-1:v1", page.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void badLimitOrCursorIs422() throws Exception {
        for (String q : List.of("limit=0", "limit=101", "limit=abc", "cursor=garbage!", "cursor=MTIz")) {
            HttpResponse<String> r = send("GET", "/notifications?" + q, sid);
            assertEquals(422, r.statusCode(), q);
            JsonObject err = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error");
            assertEquals("validation_failed", err.get("code").getAsString(), q);
            assertTrue(err.getAsJsonObject("fields").has(q.substring(0, q.indexOf('='))), q);
        }
    }

    @Test
    void unreadSeenAndClear() throws Exception {
        assertEquals(1, count());
        assertEquals(204, send("POST", "/notifications/seen", sid).statusCode());
        assertEquals(0, count());
        assertEquals(204, send("DELETE", "/notifications", sid).statusCode());
        assertEquals(0, JsonParser.parseString(send("GET", "/notifications", sid).body())
                .getAsJsonObject().getAsJsonArray("items").size());
    }

    @Test
    void everyRouteNeedsASession() throws Exception {
        for (String[] r : List.of(new String[] {"GET", "/notifications"}, new String[] {"DELETE", "/notifications"},
                new String[] {"GET", "/notifications/unread-count"}, new String[] {"POST", "/notifications/seen"})) {
            assertEquals(401, send(r[0], r[1], null).statusCode(), r[1]);
        }
    }

    @Test
    void anotherUsersIdInTheQueryChangesNothing() throws Exception {
        String alice = UUID.randomUUID().toString();
        String aliceSid = UUID.randomUUID().toString();
        jedis.set(RedisKeys.SESSION_PREFIX + aliceSid, alice);
        HttpResponse<String> r = send("GET", "/notifications?userId=" + bob, aliceSid);
        assertEquals(0, JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("items").size());
    }

    private int count() throws Exception {
        return JsonParser.parseString(send("GET", "/notifications/unread-count", sid).body())
                .getAsJsonObject().get("count").getAsInt();
    }

    private HttpResponse<String> send(String method, String path, String session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (session != null) b.header("Cookie", "sid=" + session);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void seenAndClearResetTheBadge() throws Exception {
        jedis.set(RedisKeys.UNREAD_PREFIX + bob, "5");
        assertEquals(204, send("POST", "/notifications/seen", sid).statusCode());
        assertEquals("0", jedis.get(RedisKeys.UNREAD_PREFIX + bob));
        jedis.set(RedisKeys.UNREAD_PREFIX + bob, "2");
        assertEquals(204, send("DELETE", "/notifications", sid).statusCode());
        assertEquals("0", jedis.get(RedisKeys.UNREAD_PREFIX + bob));
    }
}
