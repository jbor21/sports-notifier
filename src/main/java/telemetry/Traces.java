package telemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Spans, and the trace context carried between processes as one W3C {@code traceparent} string
 * ("00-<trace id>-<parent span id>-<flags>"). One play is one trace (spec 3.1); the string travels in
 * an HTTP header, the outbox slot, Kafka record headers, and the gateway's pub/sub JSON. A missing
 * or malformed value reads as "no parent": the reader starts a new trace and carries on.
 */
public final class Traces {

    public static final String HEADER = "traceparent";

    private static final W3CTraceContextPropagator W3C = W3CTraceContextPropagator.getInstance();

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        @Override public String get(Map<String, String> carrier, String key) { return carrier == null ? null : carrier.get(key); }
    };

    private Traces() {}

    public static Span start(String name, SpanKind kind, Context parent) {
        return Telemetry.tracer().spanBuilder(name).setSpanKind(kind).setParent(parent).startSpan();
    }

    /** A span that began earlier than now: the poller's {@code poll} starts at {@code detectedAt}. */
    public static Span startAt(String name, SpanKind kind, Context parent, long epochMillis) {
        return Telemetry.tracer().spanBuilder(name).setSpanKind(kind).setParent(parent)
            .setStartTimestamp(epochMillis, TimeUnit.MILLISECONDS).startSpan();
    }

    /** This span's context as a traceparent, or null when there is none to carry (telemetry off). */
    public static String traceparent(Span span) {
        if (!span.getSpanContext().isValid()) return null;
        Map<String, String> carrier = new HashMap<>();
        W3C.inject(Context.root().with(span), carrier, Map::put);
        return carrier.get(HEADER);
    }

    /** A context whose parent is the span the value names, or the root context if it names none. */
    public static Context fromTraceparent(String value) {
        if (value == null || value.isBlank()) return Context.root();
        return W3C.extract(Context.root(), Map.of(HEADER, value), MAP_GETTER);
    }

    /** Adds the span's traceparent to a Kafka record's headers (nothing when telemetry is off). */
    public static void inject(Span span, Headers headers) {
        String value = traceparent(span);
        if (value == null) return;
        headers.remove(HEADER);
        headers.add(HEADER, value.getBytes(StandardCharsets.UTF_8));
    }

    /** The context a Kafka record carries, or the root context. */
    public static Context extract(Headers headers) {
        Header h = headers.lastHeader(HEADER);
        return h == null || h.value() == null ? Context.root() : fromTraceparent(new String(h.value(), StandardCharsets.UTF_8));
    }
}
