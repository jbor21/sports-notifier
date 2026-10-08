package gateway;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.websocket.WsContext;
import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * {@code /api/v1/ws} (docs/api/websocket.md): {@code hello} on connect, notifications and the unread
 * count routed here by {@link GatewayChannel}, game updates for the games each tab asked for, and
 * {@code ping}/{@code pong}. A socket without a session is closed with 4401.
 */
public final class LiveSocket {

    static final int UNAUTHENTICATED = 4401;
    static final int MAX_GAMES = 100;
    static final long IDLE_MILLIS = 60_000L;
    static final long SWEEP_EVERY_SECONDS = 10L;
    private static final Pattern GAME_ID = Pattern.compile("^(nfl|nba|mlb|nhl)-[A-Za-z0-9-]+$");

    private final JedisPool pool;
    private final GatewayChannel channel;
    private final ConnectionRegistry registry;
    private final UnreadCount unread;
    private final GameSubscriptions games;
    private final LongSupplier now;
    private final Set<SocketSession> sessions = ConcurrentHashMap.newKeySet();

    public LiveSocket(JedisPool pool, GatewayChannel channel, ConnectionRegistry registry, UnreadCount unread,
                      GameSubscriptions games, LongSupplier now) {
        this.pool = pool;
        this.channel = channel;
        this.registry = registry;
        this.unread = unread;
        this.games = games;
        this.now = now;
    }

    /** Above IDLE_MILLIS, so the sweeper decides when a quiet tab is gone, not Jetty. */
    static final Duration JETTY_IDLE_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Jetty settings the socket contract needs; GatewayMain and the tests create their Javalin with this.
     * Jetty's own WebSocket idle timeout is 30 s, which would close a tab that pings every 25 s after
     * one late timer, with 1001, before the contract's 60 s.
     */
    public static void configure(JavalinConfig cfg) {
        cfg.jetty.modifyWebSocketServletFactory(f -> f.setIdleTimeout(JETTY_IDLE_TIMEOUT));
    }

    public void register(Javalin app) {
        app.ws("/api/v1/ws", ws -> {
            ws.onConnect(ctx -> {
                String userId = SessionAuth.currentUserId(pool, ctx);
                if (userId == null) {
                    ctx.closeSession(UNAUTHENTICATED, "unauthenticated");
                    return;
                }
                SocketSession session = new SocketSession(userId, socketOf(ctx), now.getAsLong());
                ctx.attribute("session", session);
                sessions.add(session);
                channel.register(userId, session);
                registry.add(userId);
                JsonObject hello = new JsonObject();
                hello.addProperty("type", "hello");
                hello.addProperty("userId", userId);
                hello.addProperty("serverTime", Instant.ofEpochMilli(now.getAsLong()).toString());
                hello.addProperty("unreadCount", unreadCount(userId));
                session.send(hello.toString());
            });
            ws.onMessage(ctx -> {
                SocketSession session = ctx.attribute("session");
                if (session != null) onMessage(session, ctx.message());
            });
            ws.onClose(ctx -> gone(ctx.attribute("session")));
            ws.onError(ctx -> gone(ctx.attribute("session")));
        });
    }

    /** 0 when neither Redis nor the Notification service can say; nothing is cached, so the next connect asks again. */
    private int unreadCount(String userId) {
        try {
            return unread.get(userId);
        } catch (RuntimeException e) {
            System.err.println("[LiveSocket] unread count unavailable for " + userId + ": " + e.getMessage());
            return 0;
        }
    }

    /** Runs {@link #sweep} every 10 seconds until the executor stops. */
    public void startSweeper(ScheduledExecutorService exec) {
        exec.scheduleWithFixedDelay(() -> {
            try {
                sweep();
            } catch (RuntimeException e) {
                System.err.println("[LiveSocket] sweep failed: " + e.getMessage());
            }
        }, SWEEP_EVERY_SECONDS, SWEEP_EVERY_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Closes tabs that have sent nothing for 60 seconds. Only what the client sends counts, which is
     * why this isn't Jetty's idle timeout: that one resets on our own writes too.
     */
    public void sweep() {
        long cutoff = now.getAsLong() - IDLE_MILLIS;
        for (SocketSession s : sessions) {
            if (s.lastReceivedMillis() <= cutoff) {
                try {
                    s.close(1000, "idle");
                } catch (RuntimeException e) {
                    gone(s);
                }
            }
        }
    }

    Set<SocketSession> sessions() {
        return sessions;
    }

    private void onMessage(SocketSession session, String text) {
        session.received(now.getAsLong());
        JsonObject m;
        try {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) return;
            m = el.getAsJsonObject();
        } catch (RuntimeException e) {
            return;   // ignore what we can't read; the tab stays open
        }
        JsonElement type = m.get("type");
        if (type == null || !type.isJsonPrimitive()) return;
        switch (type.getAsString()) {
            case "ping" -> session.send("{\"type\":\"pong\"}");
            case "subscribe_games" -> games.set(session, parseGameIds(m.get("gameIds")));
            default -> { }   // unknown types are skipped, as the contract says
        }
    }

    /** The first 100 well-formed game ids; anything else is dropped. */
    static List<String> parseGameIds(JsonElement raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || !raw.isJsonArray()) return out;
        int dropped = 0;
        for (JsonElement el : raw.getAsJsonArray()) {
            if (el.isJsonPrimitive() && GAME_ID.matcher(el.getAsString()).matches() && !out.contains(el.getAsString())) {
                if (out.size() < MAX_GAMES) out.add(el.getAsString());
                else dropped++;
            }
        }
        if (dropped > 0) System.err.println("[LiveSocket] subscribe_games: kept " + MAX_GAMES + ", ignored " + dropped);
        return out;
    }

    private void gone(SocketSession session) {
        if (session == null || !sessions.remove(session)) return;
        games.removeAll(session);
        if (channel.unregister(session.userId(), session)) registry.remove(session.userId());
    }

    /** Javalin's socket, as the routing code sees it. */
    private static ClientSocket socketOf(WsContext ctx) {
        return new ClientSocket() {
            @Override public boolean isOpen() { return ctx.session.isOpen(); }
            @Override public void send(String text) { ctx.send(text); }
            @Override public void close(int code, String reason) { ctx.closeSession(code, reason); }
        };
    }
}
