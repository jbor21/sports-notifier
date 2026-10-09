package checker;

import com.google.gson.JsonObject;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The checker's API client: transient failures are retried, then reported as setup failures (Phase 4.1 Review Focus 3). */
class ApiTest {

    private Javalin app;
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    /** A gateway whose signup fails with {@code status} the first {@code failures} times. */
    private Api api(int failures, int status) {
        app = Javalin.create().post("/api/v1/auth/signup", ctx -> {
            if (calls.incrementAndGet() <= failures) {
                ctx.status(status);
                return;
            }
            ctx.header("Set-Cookie", "sid=s3cr3t; Path=/; HttpOnly; SameSite=Lax");
            ctx.status(201).result("{\"id\":\"u-1\",\"username\":\"ck_abc123_0\"}");
        }).post("/api/v1/follows", ctx -> {
            assertEquals("s3cr3t", ctx.cookie("sid"));
            ctx.status(201).result("{\"id\":\"f-1\"}");
        }).start(0);
        return new Api(URI.create("http://localhost:" + app.port()), 3, Duration.ofMillis(10));
    }

    @Test
    void setupRetriesTransientErrors() {
        Api api = api(2, 504);
        Session s = api.signup("ck_abc123_0", "checker-pass");
        assertEquals(new Session("u-1", "s3cr3t"), s);
        assertEquals(3, calls.get());
        JsonObject f = api.follow(s, "team", "nfl-buf");
        assertEquals("f-1", f.get("id").getAsString());
    }

    @Test
    void threeFailuresThrow() {
        Api api = api(3, 502);
        SetupException e = assertThrows(SetupException.class, () -> api.signup("ck_abc123_0", "checker-pass"));
        assertTrue(e.getMessage().contains("502"), e.getMessage());
        assertEquals(3, calls.get());
    }

    @Test
    void ioErrorRetriedThenThrows() {
        Api api = new Api(URI.create("http://localhost:1"), 3, Duration.ofMillis(10));
        assertThrows(SetupException.class, () -> api.signup("ck_abc123_0", "checker-pass"));
    }

    @Test
    void noRetryOn4xx() {
        Api api = api(5, 409);
        assertThrows(SetupException.class, () -> api.signup("ck_abc123_0", "checker-pass"));
        assertEquals(1, calls.get());
    }
}
