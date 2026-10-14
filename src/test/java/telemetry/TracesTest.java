package telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testsupport.TelemetryTestSupport;

import static org.junit.jupiter.api.Assertions.*;

class TracesTest {

    private TelemetryTestSupport otel;

    @BeforeEach
    void on() { otel = TelemetryTestSupport.install(); }

    @AfterEach
    void off() { otel.close(); }

    @Test
    void stringRoundTrip() {
        Span span = Traces.start("s", SpanKind.INTERNAL, Context.root());
        Context back = Traces.fromTraceparent(Traces.traceparent(span));
        var sc = Span.fromContext(back).getSpanContext();
        assertTrue(sc.isValid());
        assertTrue(sc.isRemote());
        assertEquals(span.getSpanContext().getTraceId(), sc.getTraceId());
        assertEquals(span.getSpanContext().getSpanId(), sc.getSpanId());
        span.end();
    }

    @Test
    void kafkaHeadersRoundTrip() {
        Span span = Traces.start("s", SpanKind.PRODUCER, Context.root());
        RecordHeaders headers = new RecordHeaders();
        Traces.inject(span, headers);
        var sc = Span.fromContext(Traces.extract(headers)).getSpanContext();
        assertEquals(span.getSpanContext().getTraceId(), sc.getTraceId());
        assertEquals(span.getSpanContext().getSpanId(), sc.getSpanId());
        span.end();
    }

    @Test
    void malformedIsRoot() {
        for (String bad : new String[] {null, "", "abc", "00-00000000000000000000000000000000-0000000000000000-01"}) {
            assertFalse(Span.fromContext(Traces.fromTraceparent(bad)).getSpanContext().isValid(), String.valueOf(bad));
        }
        assertFalse(Span.fromContext(Traces.extract(new RecordHeaders())).getSpanContext().isValid());
    }

    @Test
    void offWritesNothing() {
        Telemetry.install(OpenTelemetry.noop());
        Span span = Traces.start("s", SpanKind.INTERNAL, Context.root());
        assertNull(Traces.traceparent(span));
        RecordHeaders headers = new RecordHeaders();
        Traces.inject(span, headers);
        assertFalse(headers.iterator().hasNext());
    }

    @Test
    void startAtUsesGivenTime() {
        Traces.startAt("poll", SpanKind.CLIENT, Context.root(), 1_700_000_000_123L).end();
        assertEquals(1_700_000_000_123_000_000L, otel.spans("poll").get(0).getStartEpochNanos());
    }
}
