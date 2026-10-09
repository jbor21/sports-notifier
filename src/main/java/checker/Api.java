package checker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * The checker's calls to {@code /api/v1}, the same API the UI uses, so follows go through the real save
 * path. A 5xx or an I/O error (a gateway rollout answers 504 for a few seconds, #31) is retried; a 4xx
 * or the last failure is a {@link SetupException}, reported as a failed setup, never as a finding.
 */
public final class Api {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final URI gateway;
    private final int tries;
    private final Duration between;

    public Api(URI gateway) {
        this(gateway, 3, Duration.ofSeconds(1));
    }

    public Api(URI gateway, int tries, Duration between) {
        this.gateway = gateway;
        this.tries = tries;
        this.between = between;
    }

    public Session signup(String username, String password) {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        HttpResponse<String> r = call("POST", "/api/v1/auth/signup", null, body.toString());
        String sid = r.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("sid=")).findFirst()
                .map(c -> c.substring(4, c.contains(";") ? c.indexOf(';') : c.length()))
                .orElseThrow(() -> new SetupException("signup of " + username + " set no session cookie", null));
        return new Session(json(r).get("id").getAsString(), sid);
    }

    /** {@code kind}: league, team, or game. Returns the API's Follow. */
    public JsonObject follow(Session s, String kind, String id) {
        JsonObject target = new JsonObject();
        target.addProperty("kind", kind);
        target.addProperty("id", id);
        JsonObject body = new JsonObject();
        body.add("target", target);
        return json(call("POST", "/api/v1/follows", s, body.toString()));
    }

    public JsonObject patchAlerts(Session s, String followId, JsonObject alerts) {
        JsonObject body = new JsonObject();
        body.add("alerts", alerts);
        return json(call("PATCH", "/api/v1/follows/" + followId, s, body.toString()));
    }

    public void deleteFollow(Session s, String followId) {
        call("DELETE", "/api/v1/follows/" + followId, s, null);
    }

    public void logout(Session s) {
        call("POST", "/api/v1/auth/logout", s, null);
    }

    private HttpResponse<String> call(String method, String path, Session s, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(gateway.resolve(path)).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        if (s != null) b.header("Cookie", "sid=" + s.sid());
        HttpRequest req = b.build();
        String last = null;
        Throwable cause = null;
        for (int attempt = 1; attempt <= tries; attempt++) {
            try {
                HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() < 400) return r;
                last = r.statusCode() + " " + r.body();
                if (r.statusCode() < 500) break;
            } catch (IOException e) {
                last = e.toString();
                cause = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SetupException(method + " " + path + " interrupted", e);
            }
            if (attempt < tries) sleep(between);
        }
        throw new SetupException(method + " " + path + " failed: " + last, cause);
    }

    private static JsonObject json(HttpResponse<String> r) {
        return Optional.ofNullable(r.body()).filter(b -> !b.isBlank())
                .map(b -> JsonParser.parseString(b).getAsJsonObject()).orElseGet(JsonObject::new);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
