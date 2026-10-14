package telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryTest {

    @AfterEach
    void off() {
        Telemetry.close();
        Telemetry.install(OpenTelemetry.noop());
    }

    @Test
    void offWithoutEndpoint() {
        Telemetry.init("x", Map.of());
        Span span = Traces.start("s", SpanKind.INTERNAL, Context.root());
        assertFalse(span.getSpanContext().isValid());
    }

    @Test
    void onWithEndpoint() {
        Telemetry.init("x", Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:9"));
        Span span = Traces.start("s", SpanKind.INTERNAL, Context.root());
        assertTrue(span.getSpanContext().isValid());
        span.end();
    }

    @Test
    void unreachableCollectorNeverBlocks() {
        Telemetry.init("x", Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:9", "OTEL_METRIC_EXPORT_INTERVAL", "100"));
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            Span span = Traces.start("s", SpanKind.INTERNAL, Context.root());
            try (Scope s = span.makeCurrent()) {
                PipelineMetrics.latency(PipelineMetrics.Stage.GATEWAY, 0, i);
            }
            span.end();
        }
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "10,000 spans took over 2 s");
        long closing = System.nanoTime();
        Telemetry.close();
        assertTrue(System.nanoTime() - closing < 6_000_000_000L, "close took over 6 s");
    }
}
