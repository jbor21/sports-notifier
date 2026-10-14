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

    private static final io.opentelemetry.api.common.AttributeKey<String> INSTANCE =
        io.opentelemetry.api.common.AttributeKey.stringKey("service.instance.id");

    @Test
    void eachProcessIsItsOwnInstance() {
        // Two copies of a service must not share a metric series: Prometheus would read every
        // alternation between their counters as a reset.
        var r = Telemetry.withInstanceId(io.opentelemetry.sdk.resources.Resource.empty(), Map.of("HOSTNAME", "gateway-7c9-x2k"));
        assertEquals("gateway-7c9-x2k", r.getAttribute(INSTANCE));
    }

    @Test
    void givenInstanceIdIsKept() {
        var given = io.opentelemetry.sdk.resources.Resource.create(io.opentelemetry.api.common.Attributes.of(INSTANCE, "mine"));
        assertEquals("mine", Telemetry.withInstanceId(given, Map.of("HOSTNAME", "h")).getAttribute(INSTANCE));
    }

    @Test
    void noHostnameStillUnique() {
        var empty = io.opentelemetry.sdk.resources.Resource.empty();
        String a = Telemetry.withInstanceId(empty, Map.of()).getAttribute(INSTANCE);
        String b = Telemetry.withInstanceId(empty, Map.of()).getAttribute(INSTANCE);
        assertNotNull(a);
        assertNotEquals(a, b);
    }

    @Test
    void instanceIdSurvivesResourceAttributesSetOutside() {
        // A system property (like an env var) outranks the SDK's properties supplier: the instance id
        // must still be on the resource of the SDK that gets built, not only in our map.
        System.setProperty("otel.resource.attributes", "deployment.environment=laptop");
        try {
            Telemetry.init("gateway", Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:9", "HOSTNAME", "gateway-7c9-x2k"));
            Span span = Traces.start("s", SpanKind.INTERNAL, Context.root());
            span.end();
            var resource = ((io.opentelemetry.sdk.trace.ReadableSpan) span).toSpanData().getResource();
            assertEquals("gateway-7c9-x2k", resource.getAttribute(io.opentelemetry.api.common.AttributeKey.stringKey("service.instance.id")));
            assertEquals("laptop", resource.getAttribute(io.opentelemetry.api.common.AttributeKey.stringKey("deployment.environment")));
        } finally {
            System.clearProperty("otel.resource.attributes");
        }
    }
}
