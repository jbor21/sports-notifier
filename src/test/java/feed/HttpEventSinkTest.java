package feed;

import io.javalin.Javalin;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import model.GameEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import telemetry.Traces;
import testsupport.TelemetryTestSupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The poller's POST carries the poll span's context to Event Processing (Phase 4.3 spec, 3.1). */
class HttpEventSinkTest {

    private Javalin app;
    private final List<String> headers = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void start() {
        app = Javalin.create().post("/events", ctx -> {
            headers.add(ctx.header(Traces.HEADER));
            ctx.contentType("application/json").result("{\"result\":\"accepted\"}");
        }).start(0);
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    private static GameEvent event() {
        return new GameEvent("g1", "g1:10", "SCORE_UPDATE", "LAL", 1000L, "desc",
            "LAL", "BOS", 1, 0, "IN_PROGRESS", "NBA", 10);
    }

    @Test
    void sendsTraceparentHeader() throws Exception {
        HttpEventSink sink = new HttpEventSink("http://localhost:" + app.port());
        try (TelemetryTestSupport otel = TelemetryTestSupport.install()) {
            Span span = Traces.start("poll", SpanKind.CLIENT, Context.root());
            try (Scope s = span.makeCurrent()) {
                assertEquals("accepted", sink.post(event()).result());
            }
            span.end();
            assertEquals(Traces.traceparent(span), headers.get(0));
            sink.post(event());
            assertNull(headers.get(1), "outside a span there is no header");
        }
        sink.post(event());
        assertNull(headers.get(2), "telemetry off: no header");
    }
}
