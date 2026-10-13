package gateway;

import catalog.AlertCatalog;
import catalog.Leagues;
import catalog.Teams;

import com.google.gson.JsonParser;
import games.GameSummaries;
import io.javalin.Javalin;
import redis.clients.jedis.JedisPool;
import util.GsonJsonMapper;
import util.JedisPoolHolder;
import util.SimpleHttp;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class GatewayMain {

    /** A conn: entry lives 90 s and is renewed every 30 s: two missed renewals are survivable. */
    private static final int CONN_ENTRY_TTL_SECONDS = 90;
    private static final int CONN_RENEW_EVERY_SECONDS = 30;
    /** How long a new game subscription waits for Redis to confirm before reading current state anyway. */
    private static final long SUBSCRIBE_CONFIRM_MILLIS = 2000;

    private static final String USER_PREF_URL = System.getenv().getOrDefault("USER_PREF_URL", "http://127.0.0.1:8081");
    private static final String GAME_STATE_URL = System.getenv().getOrDefault("GAME_STATE_URL", "http://127.0.0.1:8082");
    private static final String NOTIFICATION_URL = System.getenv().getOrDefault("NOTIFICATION_URL", "http://127.0.0.1:8087");

    public static void main(String[] args) {
        JedisPoolHolder.awaitRedisOrExit("gateway");
        int port = Integer.parseInt(System.getenv().getOrDefault("GATEWAY_PORT", "8080"));
        JedisPool pool = JedisPoolHolder.create();
        LoginThrottle loginThrottle = new LoginThrottle(pool);
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
            LiveSocket.configure(cfg);
        }).start(port);

        app.get("/health", ctx -> ctx.json(Collections.singletonMap("status", "ok")));

        ApiV1Routes.register(app, pool, loginThrottle, USER_PREF_URL, NOTIFICATION_URL);
        GameRoutes.register(app, pool, GAME_STATE_URL, USER_PREF_URL, Instant::now);
        ReferenceRoutes.register(app, Leagues.load(), Teams.load(), AlertCatalog.load());
        WebFiles.register(app, Path.of(System.getenv().getOrDefault("WEB_DIR", "/app/web")));

        // --- Live socket: /api/v1/ws ---
        GameSubscriptions games = new GameSubscriptions(pool, channel, new GameSummaries(Teams.load()), SUBSCRIBE_CONFIRM_MILLIS);
        UnreadCount unread = new UnreadCount(pool, GatewayMain::unreadFromHistory);
        LiveSocket live = new LiveSocket(pool, channel, registry, unread, games, System::currentTimeMillis);
        live.register(app);
        ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "idle-sweeper"));
        live.startSweeper(sweeper);

        // Graceful shutdown. Kubernetes has already waited 5 s (preStop) so new requests go elsewhere.
        // Stop the renewals first, or a running one re-adds the entries just removed (#10); then stop
        // being anyone's gateway, so Stage 2 stops publishing here; then tell every client to
        // reconnect (4000) while the browsers move to another gateway; the Redis pool closes last,
        // after anything that could still use it.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> GatewayShutdown.run(
            List.of(renewals, sweeper), Duration.ofSeconds(5), List.of(
                () -> registry.removeAll(channel.users()),
                () -> channel.closeAll(4000, "server shutting down"),
                app::stop,
                channel::stop,
                pool::close))));

        System.out.println("[Gateway] listening on :" + port);
    }

    /** Postgres's count through the Notification service, for a tab whose badge Redis doesn't have. Throws if it can't say. */
    private static int unreadFromHistory(String userId) {
        HttpResponse<String> r;
        try {
            r = SimpleHttp.get(NOTIFICATION_URL + "/users/" + urlEnc(userId) + "/notifications/unread-count");
        } catch (Exception e) {
            throw new IllegalStateException("notification service unreachable", e);
        }
        if (r.statusCode() != 200) throw new IllegalStateException("unread count answered " + r.statusCode());
        return JsonParser.parseString(r.body()).getAsJsonObject().get("count").getAsInt();
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "gateway-" + UUID.randomUUID();
        }
    }

    private static String urlEnc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
