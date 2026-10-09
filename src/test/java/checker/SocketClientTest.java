package checker;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** The checker's socket client against a stand-in gateway (Phase 4.1 spec, 4). */
class SocketClientTest {

    private Javalin app;
    private SocketClient client;
    private final List<String> fromClient = new CopyOnWriteArrayList<>();
    private final List<String> cookies = new CopyOnWriteArrayList<>();
    private final AtomicInteger connects = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (app != null) app.stop();
    }

    private static String notification(String id, String gameId) {
        return "{\"type\":\"notification\",\"notification\":{\"id\":\"" + id + "\",\"game\":{\"id\":\"" + gameId
            + "\"},\"headline\":{\"type\":\"GOAL\",\"text\":\"Goal\"},\"extras\":[],\"detectedAt\":\"2026-10-09T12:00:00Z\"}}";
    }

    /** A gateway that runs {@code onConnect} for every new socket. */
    private URI serve(java.util.function.BiConsumer<WsContext, Integer> onConnect) {
        app = Javalin.create(cfg -> {}).ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                cookies.add(String.valueOf(ctx.cookie("sid")));
                onConnect.accept(ctx, connects.incrementAndGet());
            });
            ws.onMessage(ctx -> fromClient.add(ctx.message()));
        }).start(0);
        return URI.create("ws://localhost:" + app.port() + "/ws");
    }

    private static void await(BooleanSupplier done, long millis) throws InterruptedException {
        long until = System.currentTimeMillis() + millis;
        while (!done.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(20);
        assertTrue(done.getAsBoolean(), "timed out");
    }

    @Test
    void logsNotificationsWithArrivalTime() throws Exception {
        URI uri = serve((ctx, n) -> {
            ctx.send("{\"type\":\"hello\",\"unreadCount\":0}");
            ctx.send(notification("g1:GAME_START", "nhl-rpabc123-1"));
        });
        SocketLog log = new SocketLog("u1", 0);
        long before = System.currentTimeMillis();
        client = new SocketClient(uri, "abc", log, System::currentTimeMillis, Duration.ofSeconds(25));
        client.start();
        await(() -> log.arrivals().size() == 1, 5000);
        SocketLog.Arrival a = log.arrivals().get(0);
        assertEquals("g1:GAME_START", a.messageId());
        assertEquals("nhl-rpabc123-1", a.gameId());
        assertEquals("GOAL", a.body().getAsJsonObject("headline").get("type").getAsString());
        assertTrue(a.atMillis() >= before);
        assertTrue(client.helloSeen());
    }

    @Test
    void ignoresOtherTypesAndLateHello() throws Exception {
        URI uri = serve((ctx, n) -> {
            ctx.send(notification("g1:SCORE:home:1", "g1"));           // before hello (issue #24)
            ctx.send("{\"type\":\"unread_count\",\"count\":1}");
            ctx.send("{\"type\":\"game_update\",\"game\":{\"id\":\"g1\"}}");
            ctx.send("{\"type\":\"hello\",\"unreadCount\":1}");
        });
        SocketLog log = new SocketLog("u1", 0);
        client = new SocketClient(uri, "abc", log, System::currentTimeMillis, Duration.ofSeconds(25));
        client.start();
        await(client::helloSeen, 5000);
        assertEquals(List.of("g1:SCORE:home:1"), log.arrivals().stream().map(SocketLog.Arrival::messageId).toList());
    }

    @Test
    void reconnectsAfterServerCloseAndRecordsGap() throws Exception {
        URI uri = serve((ctx, n) -> {
            ctx.send("{\"type\":\"hello\"}");
            if (n == 1) new Thread(() -> {
                try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                ctx.closeSession(4000, "shutting down");
            }).start();
        });
        SocketLog log = new SocketLog("u1", 0);
        client = new SocketClient(uri, "abc", log, System::currentTimeMillis, Duration.ofSeconds(25));
        client.start();
        await(() -> connects.get() == 2, 5000);
        await(() -> log.gaps().size() == 1 && log.gaps().get(0).toMillis() != Long.MAX_VALUE, 2000);
        SocketLog.Gap g = log.gaps().get(0);
        assertTrue(g.toMillis() >= g.fromMillis());
    }

    @Test
    void sendsPingOnSchedule() throws Exception {
        URI uri = serve((ctx, n) -> ctx.send("{\"type\":\"hello\"}"));
        client = new SocketClient(uri, "abc", new SocketLog("u1", 0), System::currentTimeMillis, Duration.ofMillis(100));
        client.start();
        await(() -> fromClient.stream().filter(m -> m.contains("\"ping\"")).count() >= 3, 3000);
    }

    @Test
    void sendsSessionCookie() throws Exception {
        URI uri = serve((ctx, n) -> ctx.send("{\"type\":\"hello\"}"));
        client = new SocketClient(uri, "s3cr3t", new SocketLog("u1", 0), System::currentTimeMillis, Duration.ofSeconds(25));
        client.start();
        await(client::helloSeen, 5000);
        assertEquals(List.of("s3cr3t"), cookies);
    }
}
