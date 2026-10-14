package telemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testsupport.TelemetryTestSupport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelineMetricsTest {

    private TelemetryTestSupport otel;

    @BeforeEach
    void on() { otel = TelemetryTestSupport.install(); }

    @AfterEach
    void off() { otel.close(); }

    @Test
    void bucketsAreTheSpecs() {
        assertEquals(List.of(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0),
            PipelineMetrics.LATENCY_BUCKETS_SECONDS);
        PipelineMetrics.latency(PipelineMetrics.Stage.ACCEPTED, 0, 10);
        assertEquals(PipelineMetrics.LATENCY_BUCKETS_SECONDS, otel.latency("accepted").orElseThrow().getBoundaries());
    }

    @Test
    void latencyInSecondsWithExemplar() {
        Span span = Traces.start("send", SpanKind.CONSUMER, Context.root());
        try (Scope s = span.makeCurrent()) {
            PipelineMetrics.latency(PipelineMetrics.Stage.GATEWAY, 1000, 1250);
        }
        span.end();
        var point = otel.latency("gateway").orElseThrow();
        assertEquals(1, point.getCount());
        assertEquals(0.25, point.getSum(), 1e-9);
        assertEquals(1, point.getExemplars().size());
        assertEquals(span.getSpanContext().getTraceId(), point.getExemplars().get(0).getSpanContext().getTraceId());
    }

    @Test
    void negativeLatencyRecordsZero() {
        PipelineMetrics.latency(PipelineMetrics.Stage.STAGE1, 2000, 1000);
        var point = otel.latency("stage1").orElseThrow();
        assertEquals(1, point.getCount());
        assertEquals(0.0, point.getSum());
    }

    @Test
    void countersCount() {
        PipelineMetrics.playAccepted("accepted");
        PipelineMetrics.playAccepted("accepted");
        PipelineMetrics.delivery("duplicate");
        PipelineMetrics.pushProduced();
        PipelineMetrics.socketSend();
        assertEquals(2, otel.counter("plays.accepted", "result", "accepted"));
        assertEquals(1, otel.counter("deliveries", "result", "duplicate"));
        assertEquals(1, otel.counter("pushes.produced", null, null));
        assertEquals(1, otel.counter("socket.sends", null, null));
    }
}
