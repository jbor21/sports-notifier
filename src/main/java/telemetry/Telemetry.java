package telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenTelemetry for this process (Phase 4.3 spec, section 2). {@link #init} builds the SDK from the
 * standard OTEL_* environment variables the chart sets. Without OTEL_EXPORTER_OTLP_ENDPOINT it stays
 * the no-op implementation: spans and metrics cost nothing and no traceparent is written anywhere,
 * which is what tests and a cluster with observability off get.
 *
 * Spans and metrics are queued and exported by a background thread, so a slow or dead collector
 * drops telemetry; it never blocks a push.
 */
public final class Telemetry {

    static final String SCOPE = "sports-notifier";

    private static volatile OpenTelemetry otel = OpenTelemetry.noop();
    private static volatile Tracer tracer = otel.getTracer(SCOPE);
    private static volatile OpenTelemetrySdk sdk;

    private Telemetry() {}

    public static void init(String serviceName) {
        init(serviceName, System.getenv());
    }

    static void init(String serviceName, Map<String, String> env) {
        String endpoint = env.get("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) return;
        // Our defaults first; every OTEL_* variable given overrides them. Only the HTTP sender is in the
        // JAR, so the protocol defaults to http/protobuf, and logs stay in stdout.
        Map<String, String> props = new HashMap<>();
        props.put("otel.service.name", serviceName);
        props.put("otel.exporter.otlp.protocol", "http/protobuf");
        props.put("otel.logs.exporter", "none");
        env.forEach((k, v) -> {
            if (k.startsWith("OTEL_")) props.put(k.toLowerCase(Locale.ROOT).replace('_', '.'), v);
        });
        OpenTelemetrySdk built = AutoConfiguredOpenTelemetrySdk.builder()
            .addPropertiesSupplier(() -> props)
            .disableShutdownHook()          // Launcher's hook calls close()
            .build()
            .getOpenTelemetrySdk();
        sdk = built;
        install(built);
    }

    /** Makes {@code o} the implementation every span and metric uses from now on (tests install their own). */
    public static void install(OpenTelemetry o) {
        otel = o;
        tracer = o.getTracer(SCOPE);
        PipelineMetrics.rebuild(o);
    }

    public static OpenTelemetry get() {
        return otel;
    }

    static Tracer tracer() {
        return tracer;
    }

    /** Flushes what is queued (at most 5 s) and stops exporting. A no-op if {@link #init} turned nothing on. */
    public static void close() {
        OpenTelemetrySdk s = sdk;
        if (s == null) return;
        sdk = null;
        // shutdown() itself can block for the exporter's retries (about 10 s against a dead collector),
        // so it runs on its own thread and we wait for that, not for the result it returns.
        Thread t = new Thread(() -> s.shutdown().join(5, TimeUnit.SECONDS), "telemetry-close");
        t.setDaemon(true);
        t.start();
        try {
            t.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
