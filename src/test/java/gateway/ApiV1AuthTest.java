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
import util.RedisKeys;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The gateway's /api/v1/auth and /api/v1/me routes, against a stub user-pref and real Redis. */
class ApiV1AuthTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String USER =
            "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"username\":\"jacob\",\"timezone\":\"UTC\",\"createdAt\":\"2026-10-07T00:00:00Z\"}";

    private JedisPool pool;
    private Jedis jedis;
    private Javalin userPref;
    private Javalin gateway;
    private String base;
    private final AtomicInteger loginCalls = new AtomicInteger();
    private final AtomicInteger signupCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        userPref = Javalin.create().start(0);
        userPref.post("/users", ctx -> {
            signupCalls.incrementAndGet();
            ctx.status(201).contentType("application/json").result(USER);
        });
        userPref.post("/users/login", ctx -> {
            loginCalls.incrementAndGet();
            if (ctx.body().contains("good-password")) ctx.contentType("application/json").result(USER);
            else ctx.status(401).contentType("application/json")
                    .result("{\"error\":{\"code\":\"invalid_credentials\",\"message\":\"Wrong username or password.\"}}");
        });
        userPref.get("/users/{id}", ctx -> ctx.contentType("application/json").result(USER));
        userPref.patch("/users/{id}", ctx -> ctx.status(422).contentType("application/json")
                .result("{\"error\":{\"code\":\"validation_failed\",\"message\":\"m\",\"fields\":{\"timezone\":\"invalid\"}}}"));
        gateway = Javalin.create().start(0);
        ApiV1Routes.register(gateway, pool, new LoginThrottle(pool),
                "http://localhost:" + userPref.port(), "http://localhost:1", "s3cret");
        base = "http://localhost:" + gateway.port() + "/api/v1";
    }

    @AfterEach
    void tearDown() {
        gateway.stop();
        userPref.stop();
        jedis.close();
        pool.close();
    }

    @Test
    void signupSetsSessionAndReturns201() throws Exception {
        HttpResponse<String> r = post("/auth/signup", "{\"username\":\"jacob\",\"password\":\"good-password\"}", null);
        assertEquals(201, r.statusCode());
        String sid = sid(r);
        assertEquals("11111111-1111-1111-1111-111111111111", jedis.get(RedisKeys.SESSION_PREFIX + sid));
        assertEquals("jacob", JsonParser.parseString(r.body()).getAsJsonObject().get("username").getAsString());
    }

    @Test
    void signupOfCheckerNameWithoutKeyIs422AndNeverReachesUserPref() throws Exception {
        HttpResponse<String> r = post("/auth/signup", "{\"username\":\"ck_abcdef_1\",\"password\":\"good-password\"}", null);
        assertEquals(422, r.statusCode());
        JsonObject error = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error");
        assertEquals("validation_failed", error.get("code").getAsString());
        assertEquals("invalid", error.getAsJsonObject("fields").get("username").getAsString());
        assertEquals(0, signupCalls.get());
        assertTrue(r.headers().allValues("Set-Cookie").isEmpty());
    }

    @Test
    void signupOfCheckerNameWithKeyIs201() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/auth/signup"))
                .header("X-Checker-Key", "s3cret")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"ck_abcdef_1\",\"password\":\"good-password\"}")), null);
        assertEquals(201, r.statusCode());
        assertEquals(1, signupCalls.get());
    }

    @Test
    void meNeedsASession() throws Exception {
        HttpResponse<String> r = get("/me", null);
        assertEquals(401, r.statusCode());
        assertEquals("unauthenticated", errorCode(r));
        String sid = sid(post("/auth/login", "{\"username\":\"jacob\",\"password\":\"good-password\"}", null));
        assertEquals(200, get("/me", sid).statusCode());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        String sid = sid(post("/auth/login", "{\"username\":\"jacob\",\"password\":\"good-password\"}", null));
        assertEquals(204, post("/auth/logout", "", sid).statusCode());
        assertNull(jedis.get(RedisKeys.SESSION_PREFIX + sid));
        assertEquals(401, get("/me", sid).statusCode());
        assertEquals(401, post("/auth/logout", "", null).statusCode());
    }

    @Test
    void wrongPasswordPassesThrough401() throws Exception {
        HttpResponse<String> r = post("/auth/login", "{\"username\":\"jacob\",\"password\":\"bad\"}", null);
        assertEquals(401, r.statusCode());
        assertEquals("invalid_credentials", errorCode(r));
        assertTrue(r.headers().allValues("Set-Cookie").stream().noneMatch(c -> c.startsWith("sid=") && !c.startsWith("sid=;")));
    }

    @Test
    void throttledLoginIs429InTheErrorShapeAndCountedBeforeTheCheck() throws Exception {
        for (int i = 0; i < LoginThrottle.USER_LIMIT; i++) {
            post("/auth/login", "{\"username\":\"jacob\",\"password\":\"bad\"}", null);
        }
        int before = loginCalls.get();
        HttpResponse<String> r = post("/auth/login", "{\"username\":\"jacob\",\"password\":\"good-password\"}", null);
        assertEquals(429, r.statusCode());
        assertEquals("rate_limited", errorCode(r));
        assertEquals(String.valueOf(LoginThrottle.WINDOW_SECONDS), r.headers().firstValue("Retry-After").orElse(null));
        assertEquals(before, loginCalls.get(), "a throttled attempt never reaches the password check");
    }

    @Test
    void patchMePassesThrough422() throws Exception {
        String sid = sid(post("/auth/login", "{\"username\":\"jacob\",\"password\":\"good-password\"}", null));
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/me"))
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"timezone\":\"EST\"}")), sid);
        assertEquals(422, r.statusCode());
        assertEquals("validation_failed", errorCode(r));
    }

    private HttpResponse<String> post(String path, String body, String sid) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(body)), sid);
    }

    private HttpResponse<String> get(String path, String sid) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), sid);
    }

    private static HttpResponse<String> send(HttpRequest.Builder b, String sid) throws Exception {
        b.header("Content-Type", "application/json");
        if (sid != null) b.header("Cookie", "sid=" + sid);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The session id from Set-Cookie. Read by hand: the cookie is Secure, which HttpClient won't send over http. */
    private static String sid(HttpResponse<String> r) {
        String cookie = r.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("sid=")).findFirst().orElseThrow();
        return cookie.substring(4, cookie.indexOf(';'));
    }

    private static String errorCode(HttpResponse<String> r) {
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        return o.getAsJsonObject("error").get("code").getAsString();
    }
}
