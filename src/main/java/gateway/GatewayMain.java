package gateway;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import feed.EspnClient;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import redis.clients.jedis.JedisPool;
import util.GsonJsonMapper;
import util.JedisPoolHolder;
import util.SimpleHttp;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class GatewayMain {

    private static final Gson GSON = new Gson();
    /** A conn: entry lives 90 s and is renewed every 30 s: two missed renewals are survivable. */
    private static final int CONN_ENTRY_TTL_SECONDS = 90;
    private static final int CONN_RENEW_EVERY_SECONDS = 30;

    private static final String USER_PREF_URL = System.getenv().getOrDefault("USER_PREF_URL", "http://127.0.0.1:8081");
    private static final String GAME_STATE_URL = System.getenv().getOrDefault("GAME_STATE_URL", "http://127.0.0.1:8082");
    private static final String NOTIFICATION_URL = System.getenv().getOrDefault("NOTIFICATION_URL", "http://127.0.0.1:8087");

    public static void main(String[] args) {
        JedisPoolHolder.awaitRedisOrExit("gateway");
        int port = Integer.parseInt(System.getenv().getOrDefault("GATEWAY_PORT", "8080"));
        JedisPool pool = JedisPoolHolder.create();
        GameListingHandler gameListingHandler = new GameListingHandler(pool);
        LoginThrottle loginThrottle = new LoginThrottle(pool);
        EspnProxyHandler espnProxyHandler = new EspnProxyHandler(EspnClient.fromEnv());
        // Routing (DESIGN.md decision 13): this gateway listens only on its own channel, gw:<id>, and
        // records in conn:<userId> which users it holds, so Stage 2 publishes only to the right gateways.
        String gatewayId = System.getenv().getOrDefault("GATEWAY_ID", hostname());
        GatewayChannel channel = new GatewayChannel(JedisPoolHolder::dedicated, gatewayId);
        channel.start();
        ConnectionRegistry registry = new ConnectionRegistry(pool, gatewayId, CONN_ENTRY_TTL_SECONDS);
        ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "conn-renewals"));
        renewals.scheduleWithFixedDelay(() -> {
            try {
                registry.renewAll(channel.users());
            } catch (RuntimeException e) {
                System.err.println("[Gateway] renewing conn: entries failed: " + e.getMessage());
            }
        }, CONN_RENEW_EVERY_SECONDS, CONN_RENEW_EVERY_SECONDS, TimeUnit.SECONDS);

        Javalin app = Javalin.create(cfg -> {
            cfg.jsonMapper(new GsonJsonMapper());
            cfg.staticFiles.add(staticCfg -> {
                staticCfg.hostedPath = "/ui";
                staticCfg.directory = "/public";
                staticCfg.location = Location.CLASSPATH;
            });
        }).start(port);

        app.get("/", ctx -> ctx.redirect("/ui/"));

        app.get("/health", ctx -> ctx.json(Collections.singletonMap("status", "ok")));

        // --- Auth ---
        app.post("/api/signup", ctx -> {
            HttpResponse<String> r = SimpleHttp.postJson(USER_PREF_URL + "/users", ctx.body());
            if (r.statusCode() >= 400) { ctx.status(r.statusCode()).result(r.body()); return; }
            String userId = JsonParser.parseString(r.body()).getAsJsonObject().get("userId").getAsString();
            SessionAuth.createSession(pool, ctx, userId);
            ctx.json(JsonParser.parseString(r.body()));
        });

        app.post("/api/login", ctx -> {
            String username = usernameIn(ctx.body());
            String ip = ClientAddress.of(ctx.header("X-Real-Ip"), ctx.header("X-Forwarded-For"), ctx.ip());
            // Count the attempt before checking the password; see LoginThrottle for why.
            if (!loginThrottle.admit(username, ip)) {
                ctx.status(429).json(Map.of("error", "too many failed logins, try again later"));
                return;
            }
            HttpResponse<String> r = SimpleHttp.postJson(USER_PREF_URL + "/users/login", ctx.body());
            if (r.statusCode() >= 400) { ctx.status(r.statusCode()).result(r.body()); return; }
            loginThrottle.recordSuccess(username, ip);
            String userId = JsonParser.parseString(r.body()).getAsJsonObject().get("userId").getAsString();
            SessionAuth.createSession(pool, ctx, userId);
            ctx.json(JsonParser.parseString(r.body()));
        });

        app.post("/api/logout", ctx -> {
            SessionAuth.destroySession(pool, ctx);
            ctx.json(Collections.singletonMap("ok", true));
        });

        app.get("/api/me", ctx -> {
            String userId = requireAuth(pool, ctx);
            if (userId == null) return;
            HttpResponse<String> r = SimpleHttp.get(USER_PREF_URL + "/users/" + urlEnc(userId));
            ctx.status(r.statusCode()).result(r.body());
        });

        // --- Preferences ---
        app.get("/api/preferences", ctx -> {
            String userId = requireAuth(pool, ctx);
            if (userId == null) return;
            HttpResponse<String> r = SimpleHttp.get(USER_PREF_URL + "/prefs/" + urlEnc(userId));
            ctx.status(r.statusCode()).result(r.body());
        });

        app.put("/api/preferences", ctx -> {
            String userId = requireAuth(pool, ctx);
            if (userId == null) return;
            HttpResponse<String> r = SimpleHttp.putJson(USER_PREF_URL + "/prefs/" + urlEnc(userId), ctx.body());
            ctx.status(r.statusCode()).result(r.body());
        });

        // --- Game state ---
        app.get("/api/games", gameListingHandler::handleList);

        app.get("/api/games/{gameId}", ctx -> {
            HttpResponse<String> r = SimpleHttp.get(GAME_STATE_URL + "/games/" + urlEnc(ctx.pathParam("gameId")));
            ctx.status(r.statusCode()).result(r.body());
        });

        // --- Upcoming games (proxied from ESPN to avoid browser CORS issues) ---
        app.get("/api/upcoming", espnProxyHandler::handleUpcoming);

        // --- History ---
        app.get("/api/history", ctx -> {
            String userId = requireAuth(pool, ctx);
            if (userId == null) return;
            String limit = ctx.queryParamAsClass("limit", String.class).getOrDefault("50");
            HttpResponse<String> r = SimpleHttp.get(NOTIFICATION_URL + "/history/" + urlEnc(userId) + "?limit=" + urlEnc(limit));
            ctx.status(r.statusCode()).result(r.body());
        });

        // --- WebSocket push stream ---
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                String userId = SessionAuth.currentUserId(pool, ctx);
                if (userId == null) {
                    ctx.send("{\"event\":\"auth-error\"}");
                    ctx.closeSession();
                    return;
                }
                ctx.attribute("userId", userId);
                ClientSocket socket = socketOf(ctx);
                ctx.attribute("socket", socket);
                channel.register(userId, socket);
                registry.add(userId);
                JsonObject hello = new JsonObject();
                hello.addProperty("event", "connected");
                hello.addProperty("userId", userId);
                hello.addProperty("connectedAt", System.currentTimeMillis());
                ctx.send(GSON.toJson(hello));
            });
            ws.onClose(ctx -> socketGone(channel, registry, ctx.attribute("userId"), ctx.attribute("socket")));
            ws.onError(ctx -> socketGone(channel, registry, ctx.attribute("userId"), ctx.attribute("socket")));
        });

        // Graceful shutdown: stop being anyone's gateway first, then tell every client to reconnect
        // (code 4000), so Stage 2 stops publishing here while the browsers move to another gateway.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                registry.removeAll(channel.users());
            } catch (RuntimeException e) {
                System.err.println("[Gateway] could not remove conn: entries; they expire in 90 s: " + e.getMessage());
            }
            channel.closeAll(4000, "server shutting down");
            renewals.shutdownNow();
            channel.stop();
            app.stop();
            pool.close();
        }));

        System.out.println("[Gateway] listening on :" + port);
    }

    /** Javalin's socket, as the routing code sees it. */
    private static ClientSocket socketOf(WsContext ctx) {
        return new ClientSocket() {
            @Override public boolean isOpen() { return ctx.session.isOpen(); }
            @Override public void send(String text) { ctx.send(text); }
            @Override public void close(int code, String reason) { ctx.closeSession(code, reason); }
        };
    }

    private static void socketGone(GatewayChannel channel, ConnectionRegistry registry, String userId, ClientSocket socket) {
        if (userId == null || socket == null) return;
        if (channel.unregister(userId, socket)) registry.remove(userId);
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "gateway-" + UUID.randomUUID();
        }
    }

    private static String requireAuth(JedisPool pool, Context ctx) {
        String userId = SessionAuth.currentUserId(pool, ctx);
        if (userId == null) {
            ctx.status(401).json(Map.of("error", "not authenticated"));
            return null;
        }
        return userId;
    }

    /** The username in a login body, or null if the body isn't a JSON object with one. */
    private static String usernameIn(String body) {
        try {
            JsonElement el = JsonParser.parseString(body);
            if (!el.isJsonObject()) return null;
            JsonElement u = el.getAsJsonObject().get("username");
            return u != null && u.isJsonPrimitive() ? u.getAsString() : null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static String urlEnc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
