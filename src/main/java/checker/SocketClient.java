package checker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * One synthetic tab on {@code /api/v1/ws} (Phase 4.1 spec, 4), behaving as {@code docs/api/websocket.md}
 * asks of a client: pings on a schedule (the server closes a silent socket after 60 s) and reconnects after
 * any close with backoff and full jitter. A ping with nothing received by the next one means the line
 * died without saying so (a machine that died sends no close and no reset): the socket is aborted, and
 * its gap starts at the last frame received (Phase 6.1 spec, 2). Every {@code notification} goes into the {@link SocketLog}
 * with its arrival time; every time the socket is down is a gap there, from the close until the next
 * {@code hello}. Other message types are ignored,
 * and so is when {@code hello} arrives (issue #24).
 */
public final class SocketClient implements WebSocket.Listener {

    private static final long BACKOFF_BASE_MILLIS = 1_000;
    private static final long BACKOFF_MAX_MILLIS = 30_000;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final URI uri;
    private final String sid;
    private final SocketLog log;
    private final LongSupplier clock;
    private final Duration pingEvery;
    private final ScheduledExecutorService timer;
    private final StringBuilder partial = new StringBuilder();
    private volatile WebSocket ws;
    private volatile boolean helloSeen;
    private volatile boolean closed;
    private volatile long lastReceivedAt;
    /** When this connection's last ping went out; 0 until its first. */
    private volatile long lastPingAt;
    private int failures;

    public SocketClient(URI uri, String sid, SocketLog log, LongSupplier clock, Duration pingEvery) {
        this.uri = uri;
        this.sid = sid;
        this.log = log;
        this.clock = clock;
        this.pingEvery = pingEvery;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "socket-" + log.userId() + "-" + log.tab());
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        timer.scheduleAtFixedRate(this::ping, pingEvery.toMillis(), pingEvery.toMillis(), TimeUnit.MILLISECONDS);
        connect();
    }

    /** True once a {@code hello} has arrived on any connection. */
    public boolean helloSeen() {
        return helloSeen;
    }

    public void close() {
        closed = true;
        timer.shutdownNow();
        WebSocket w = ws;
        if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "done").exceptionally(e -> null);
    }

    private void connect() {
        if (closed) return;
        HTTP.newWebSocketBuilder().header("Cookie", "sid=" + sid).buildAsync(uri, this)
            .whenComplete((w, err) -> {
                if (err != null) down(null, clock.getAsLong(), false);
            });
    }

    /**
     * Records the gap and schedules the reconnect, once per connection: {@code which} must still be the
     * current socket ({@code null} for a connect that never opened). An aborted socket's own late
     * {@code onError} or {@code onClose} then finds {@code ws} already cleared and is ignored.
     */
    private synchronized void down(WebSocket which, long atMillis, boolean silent) {
        if (closed || which != ws) return;
        ws = null;
        log.disconnected(atMillis, silent);
        long cap = Math.min(BACKOFF_MAX_MILLIS, BACKOFF_BASE_MILLIS << Math.min(failures, 5));
        failures++;
        long delay = ThreadLocalRandom.current().nextLong(cap + 1);
        try {
            timer.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closing
        }
    }

    private void ping() {
        WebSocket w = ws;
        if (w == null) return;
        if (lastPingAt != 0 && lastReceivedAt < lastPingAt) {
            // Recorded before the abort, so the abort's own callbacks find nothing to do.
            down(w, lastReceivedAt, true);
            w.abort();
            return;
        }
        lastPingAt = clock.getAsLong();
        w.sendText("{\"type\":\"ping\"}", true).exceptionally(e -> null);
    }

    @Override
    public synchronized void onOpen(WebSocket webSocket) {
        lastPingAt = 0;
        lastReceivedAt = clock.getAsLong();
        ws = webSocket;
        failures = 0;
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            handle(text);
        }
        webSocket.request(1);
        return null;
    }

    private void handle(String text) {
        long at = clock.getAsLong();
        lastReceivedAt = at;
        JsonObject m;
        try {
            m = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String type = m.has("type") ? m.get("type").getAsString() : "";
        if ("hello".equals(type)) {
            // The gateway registers this socket for delivery (conn:<userId>) before it sends hello, not
            // before the handshake completes: until hello, a push can still miss this tab.
            log.connected(at);
            helloSeen = true;
        } else if ("notification".equals(type) && m.has("notification")) {
            JsonObject n = m.getAsJsonObject("notification");
            String gameId = n.has("game") ? n.getAsJsonObject("game").get("id").getAsString() : null;
            log.arrived(new SocketLog.Arrival(n.get("id").getAsString(), gameId, n, at));
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        down(webSocket, clock.getAsLong(), false);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        down(webSocket, clock.getAsLong(), false);
    }
}
