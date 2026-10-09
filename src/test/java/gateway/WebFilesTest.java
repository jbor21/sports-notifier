package gateway;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The gateway serving the built web UI (Phase 3b spec, 4.2). */
class WebFilesTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path tmp;

    private Path web;
    private Javalin gateway;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        web = tmp.resolve("web");
        Files.createDirectories(web.resolve("assets"));
        Files.writeString(web.resolve("index.html"), "<!doctype html><title>sideline</title>");
        Files.writeString(web.resolve("assets/app-abc123.js"), "console.log('hi')");
        Files.writeString(web.resolve("sw.js"), "self.addEventListener('install', () => {})");
        Files.writeString(web.resolve("manifest.webmanifest"), "{\"name\":\"Sideline\"}");
        Files.writeString(tmp.resolve("secret.txt"), "top secret");

        gateway = Javalin.create().start(0);
        gateway.get("/api/v1/thing", ctx -> ctx.status(404).contentType("application/json")
                .result("{\"error\":{\"code\":\"not_found\",\"message\":\"Not found.\"}}"));
        gateway.get("/health", ctx -> ctx.result("ok"));
        assertTrue(WebFiles.register(gateway, web));
        base = "http://localhost:" + gateway.port();
    }

    @AfterEach
    void tearDown() {
        gateway.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<?> r, String name) {
        return r.headers().firstValue(name).orElse(null);
    }

    @Test
    void rootServesIndex() throws Exception {
        HttpResponse<String> r = get("/");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("<title>sideline"), r.body());
        assertEquals("no-cache", header(r, "Cache-Control"));
        assertTrue(header(r, "Content-Type").startsWith("text/html"), header(r, "Content-Type"));
    }

    @Test
    void appRoutesFallBackToIndex() throws Exception {
        for (String path : new String[] {"/games/nfl-1", "/following/f_1"}) {
            HttpResponse<String> r = get(path);
            assertEquals(200, r.statusCode(), path);
            assertTrue(r.body().contains("<title>sideline"), path);
            assertEquals("no-cache", header(r, "Cache-Control"), path);
        }
    }

    @Test
    void hashedAssetIsImmutable() throws Exception {
        HttpResponse<String> r = get("/assets/app-abc123.js");
        assertEquals(200, r.statusCode());
        assertEquals("console.log('hi')", r.body());
        assertEquals("public, max-age=31536000, immutable", header(r, "Cache-Control"));
        String type = header(r, "Content-Type");
        assertTrue(type.startsWith("text/javascript") || type.startsWith("application/javascript"), type);
    }

    @Test
    void missingAssetStays404() throws Exception {
        HttpResponse<String> r = get("/assets/missing.js");
        assertEquals(404, r.statusCode());
        assertFalse(r.body().contains("<title>sideline"));
    }

    @Test
    void apiNotFoundKeepsItsBody() throws Exception {
        HttpResponse<String> thing = get("/api/v1/thing");
        assertEquals(404, thing.statusCode());
        assertTrue(thing.body().contains("\"not_found\""), thing.body());
        HttpResponse<String> nope = get("/api/v1/nope");
        assertEquals(404, nope.statusCode());
        assertFalse(nope.body().contains("<title>sideline"));
    }

    @Test
    void healthUnchanged() throws Exception {
        assertEquals("ok", get("/health").body());
    }

    @Test
    void serviceWorkerAndManifestNotCached() throws Exception {
        assertEquals("no-cache", header(get("/sw.js"), "Cache-Control"));
        HttpResponse<String> m = get("/manifest.webmanifest");
        assertEquals(200, m.statusCode());
        assertEquals("no-cache", header(m, "Cache-Control"));
        assertTrue(header(m, "Content-Type").startsWith("application/manifest+json"), header(m, "Content-Type"));
    }

    @Test
    void traversalRefused() throws Exception {
        for (String path : new String[] {"/..%2Fsecret.txt", "/../secret.txt", "/assets/..%2F..%2Fsecret.txt"}) {
            HttpResponse<String> r = get(path);
            assertNotEquals(200, r.statusCode(), path);
            assertFalse(r.body().contains("top secret"), path);
        }
    }

    @Test
    void securityHeaders() throws Exception {
        HttpResponse<String> r = get("/");
        assertEquals("nosniff", header(r, "X-Content-Type-Options"));
        assertEquals("strict-origin-when-cross-origin", header(r, "Referrer-Policy"));
        String csp = header(r, "Content-Security-Policy");
        assertNotNull(csp);
        assertTrue(csp.contains("img-src 'self' data: https://a.espncdn.com;"), csp);
        assertTrue(csp.contains("frame-ancestors 'none'"), csp);
        assertTrue(csp.contains("connect-src 'self' ws://localhost:" + gateway.port() + " wss://localhost:" + gateway.port() + ";"), csp);
    }

    @Test
    void cspNamesTheSocketHost() {
        String csp = WebFiles.contentSecurityPolicy("localhost:8080");
        assertTrue(csp.contains("connect-src 'self' ws://localhost:8080 wss://localhost:8080;"), csp);
    }

    @Test
    void cspRejectsABadHost() {
        for (String host : new String[] {"evil.com; script-src *", "a b", null}) {
            String csp = WebFiles.contentSecurityPolicy(host);
            assertTrue(csp.contains("connect-src 'self';"), String.valueOf(host));
            assertFalse(csp.contains("ws://"), String.valueOf(host));
            assertFalse(csp.contains("script-src *"), String.valueOf(host));
        }
    }

    @Test
    void noFolderNoFiles() throws Exception {
        Javalin bare = Javalin.create().start(0);
        try {
            bare.get("/health", ctx -> ctx.result("ok"));
            assertFalse(WebFiles.register(bare, tmp.resolve("missing")));
            String b = "http://localhost:" + bare.port();
            assertEquals(404, HTTP.send(HttpRequest.newBuilder(URI.create(b + "/")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("ok", HTTP.send(HttpRequest.newBuilder(URI.create(b + "/health")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
        } finally {
            bare.stop();
        }
    }

    @Test
    void courseUiIsGone() {
        assertNull(WebFilesTest.class.getResource("/public/index.html"));
    }
}
