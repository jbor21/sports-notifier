package gateway;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import telemetry.PipelineMetrics;
import telemetry.Traces;
import util.RedisKeys;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * This gateway's sockets, by user, and its own channel {@code gw:<gatewayId>}. Stage 2 publishes
 * there only for users this gateway holds (see {@link ConnectionRegistry}), so a gateway reads only
 * its own users' notifications and adding gateways spreads the work. Replaces the course version's
 * PSUBSCRIBE notify:*, where every gateway read every notification.
 *
 * The same connection also carries the {@code live:<gameId>} channels of the games this gateway's
 * tabs show ({@link GameSubscriptions} decides which); messages are routed by channel prefix.
 */
public final class GatewayChannel {

    private final Supplier<Jedis> connect;
    private volatile Jedis blocking;
    private final String gatewayId;
    private final ConcurrentHashMap<String, Set<ClientSocket>> socketsByUser = new ConcurrentHashMap<>();
    private final JedisPubSub subscriber = new JedisPubSub() {
        @Override
        public void onMessage(String channel, String message) {
            if (channel.startsWith(RedisKeys.LIVE_CHANNEL_PREFIX)) {
                BiConsumer<String, String> l = liveListener;
                if (l != null) l.accept(channel.substring(RedisKeys.LIVE_CHANNEL_PREFIX.length()), message);
            } else {
                deliverLocally(message);
            }
        }

        @Override
        public void onSubscribe(String channel, int count) {
            CompletableFuture<Void> f = subscriptions.get(channel);
            if (f != null) f.complete(null);
            sendUnrequested();
            if (resubscribing && count >= expectedOnConnect) {
                resubscribing = false;
                Runnable r = afterResubscribe;
                if (r != null) callbacks.execute(r);
            }
        }
    };
    /**
     * live:<gameId> channels this gateway wants, each with a future Redis's confirmation completes.
     * Jedis writes SUBSCRIBE on the caller's thread with no lock of its own, so every change goes
     * through {@link #commandLock}.
     */
    private final Map<String, CompletableFuture<Void>> subscriptions = new ConcurrentHashMap<>();
    private final Object commandLock = new Object();
    /** Channels sent to Redis on the current connection; a game wanted but not in here still needs a SUBSCRIBE. */
    private final Set<String> requested = ConcurrentHashMap.newKeySet();
    private volatile BiConsumer<String, String> liveListener;
    private volatile Runnable afterResubscribe;
    private volatile boolean everConnected;
    private volatile boolean resubscribing;
    private volatile int expectedOnConnect;
    private final ExecutorService callbacks = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gateway-channel-callbacks");
        t.setDaemon(true);
        return t;
    });
    private Thread subscriberThread;
    private volatile boolean running = true;

    /**
     * @param connect opens a dedicated connection for SUBSCRIBE. A fresh one per attempt: after Redis
     *                restarts, the old connection is broken and Jedis keeps trying to reuse it, so the
     *                gateway would go on renewing its users' routing entries while hearing nothing.
     */
    public GatewayChannel(Supplier<Jedis> connect, String gatewayId) {
        this.connect = connect;
        this.gatewayId = gatewayId;
    }

    public String gatewayId() {
        return gatewayId;
    }

    public void register(String userId, ClientSocket socket) {
        socketsByUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(socket);
    }

    /** @return true if that was the user's last socket on this gateway */
    public boolean unregister(String userId, ClientSocket socket) {
        if (userId == null) return false;
        boolean[] last = {false};
        socketsByUser.computeIfPresent(userId, (k, set) -> {
            set.remove(socket);
            if (!set.isEmpty()) return set;
            last[0] = true;
            return null;
        });
        return last[0];
    }

    /** Users with at least one socket here, for renewals and for graceful shutdown. */
    public Set<String> users() {
        return Set.copyOf(socketsByUser.keySet());
    }

    /**
     * Messages are {"userId": ..., "payload": {notification}, "traceparent": ...}; the socket gets the
     * payload. A notification is the last hop of its play's trace: a {@code send} span, a child of
     * Stage 2's deliver, and the headline latency, poller saw it → gateway sent it (Phase 4.3 spec, 4.1),
     * recorded inside the span so its exemplar opens this play's trace.
     */
    private void deliverLocally(String message) {
        String userId;
        String payload;
        JsonObject notification = null;
        String traceparent = null;
        try {
            JsonObject m = JsonParser.parseString(message).getAsJsonObject();
            userId = m.get("userId").getAsString();
            JsonElement p = m.get("payload");
            payload = p.toString();
            if (p.isJsonObject() && "notification".equals(stringOrNull(p.getAsJsonObject().get("type")))
                    && p.getAsJsonObject().get("notification") instanceof JsonObject n) {
                notification = n;
                traceparent = stringOrNull(m.get("traceparent"));
            }
        } catch (RuntimeException e) {
            System.err.println("[GatewayChannel] unreadable message on " + RedisKeys.GATEWAY_CHANNEL_PREFIX + gatewayId);
            return;
        }
        if (notification == null) {
            sendTo(userId, payload);
            return;
        }
        Span span = Traces.start("send", SpanKind.CONSUMER, Traces.fromTraceparent(traceparent));
        try (Scope scope = span.makeCurrent()) {
            int written = sendTo(userId, payload);
            span.setAttribute("send.sockets", (long) written);
            if (written > 0) {
                PipelineMetrics.socketSend();
                Long detectedAt = detectedAtMillis(notification);
                if (detectedAt != null) {
                    PipelineMetrics.latency(PipelineMetrics.Stage.GATEWAY, detectedAt, System.currentTimeMillis());
                }
            }
        } finally {
            span.end();
        }
    }

    /** @return how many open sockets the payload was written to */
    private int sendTo(String userId, String payload) {
        // The user may have closed their last tab since Stage 2 looked them up: nothing to do.
        Set<ClientSocket> sockets = socketsByUser.get(userId);
        if (sockets == null) return 0;
        int written = 0;
        for (ClientSocket s : sockets) {
            try {
                if (s.isOpen()) {
                    s.send(payload);
                    written++;
                }
            } catch (RuntimeException e) {
                System.err.println("[GatewayChannel] send error: " + e.getMessage());
            }
        }
        return written;
    }

    private static String stringOrNull(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static Long detectedAtMillis(JsonObject notification) {
        try {
            String at = stringOrNull(notification.get("detectedAt"));
            return at == null ? null : Instant.parse(at).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Where live:<gameId> messages go, and what to run after a reconnect has re-subscribed every
     * channel (the tabs may have missed plays while the connection was down).
     */
    public void onGames(BiConsumer<String, String> live, Runnable afterResubscribe) {
        this.liveListener = live;
        this.afterResubscribe = afterResubscribe;
    }

    /**
     * Starts listening on a channel. Completes when Redis confirms; at once if already confirmed.
     * While the connection is down the future waits for the reconnect, which subscribes it.
     */
    public CompletableFuture<Void> subscribe(String channel) {
        synchronized (commandLock) {
            CompletableFuture<Void> existing = subscriptions.get(channel);
            if (existing != null) return existing;
            CompletableFuture<Void> f = new CompletableFuture<>();
            subscriptions.put(channel, f);
            if (subscriber.isSubscribed()) send(channel);
            // Not connected yet (starting, or between reconnects): the next confirmation sends it.
            return f;
        }
    }

    /** Called with commandLock held. */
    private void send(String channel) {
        try {
            subscriber.subscribe(channel);
            requested.add(channel);
        } catch (RuntimeException e) {
            System.err.println("[GatewayChannel] subscribe " + channel + " failed; the reconnect retries it: " + e.getMessage());
        }
    }

    /**
     * A game asked for while the connection was being set up was neither in the connect command nor
     * sent itself; every confirmation sends what is still missing.
     */
    private void sendUnrequested() {
        synchronized (commandLock) {
            for (String ch : subscriptions.keySet()) if (!requested.contains(ch)) send(ch);
        }
    }

    public void unsubscribe(String channel) {
        synchronized (commandLock) {
            if (subscriptions.remove(channel) == null) return;
            if (requested.remove(channel) && subscriber.isSubscribed()) {
                try {
                    subscriber.unsubscribe(channel);
                } catch (RuntimeException e) {
                    System.err.println("[GatewayChannel] unsubscribe " + channel + " failed: " + e.getMessage());
                }
            }
        }
    }

    public void start() {
        subscriberThread = new Thread(() -> {
            while (running) {
                try (Jedis conn = connect.get()) {
                    blocking = conn;
                    String[] channels;
                    synchronized (commandLock) {
                        // A reconnect: every wanted game channel waits for its new confirmation.
                        subscriptions.replaceAll((ch, f) -> f.isDone() ? new CompletableFuture<>() : f);
                        List<String> all = new ArrayList<>();
                        all.add(RedisKeys.GATEWAY_CHANNEL_PREFIX + gatewayId);
                        all.addAll(subscriptions.keySet());
                        channels = all.toArray(new String[0]);
                        requested.clear();
                        requested.addAll(subscriptions.keySet());
                        expectedOnConnect = channels.length;
                        resubscribing = everConnected;
                        everConnected = true;
                    }
                    conn.subscribe(subscriber, channels);
                } catch (RuntimeException e) {
                    if (!running) return;
                    System.err.println("[GatewayChannel] subscribe error: " + e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }, "gateway-channel-" + gatewayId);
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    /** Graceful shutdown: tell every client to reconnect (4000 = "reconnect now", websocket.md). */
    public void closeAll(int code, String reason) {
        for (Set<ClientSocket> sockets : socketsByUser.values()) {
            for (ClientSocket s : sockets) {
                try { s.close(code, reason); } catch (RuntimeException ignored) {}
            }
        }
    }

    public void stop() {
        running = false;
        callbacks.shutdownNow();
        try { subscriber.unsubscribe(); } catch (RuntimeException ignored) {}
        Jedis conn = blocking;
        if (conn != null) try { conn.close(); } catch (RuntimeException ignored) {}
        if (subscriberThread != null) subscriberThread.interrupt();
    }
}
