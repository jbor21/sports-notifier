package testsupport;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import telemetry.Telemetry;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Telemetry on, for a test: every span is kept in memory as it ends, and metrics are read on demand.
 * {@link #close()} puts the no-op implementation back, so other tests see telemetry off.
 */
public final class TelemetryTestSupport implements AutoCloseable {

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    private final OpenTelemetrySdk sdk;

    private TelemetryTestSupport() {
        sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(spans))
                .build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
        Telemetry.install(sdk);
    }

    public static TelemetryTestSupport install() {
        return new TelemetryTestSupport();
    }

    /** Spans that have ended, in the order they ended. */
    public List<SpanData> spans() {
        return spans.getFinishedSpanItems();
    }

    /** Ended spans with this name. */
    public List<SpanData> spans(String name) {
        return spans().stream().filter(s -> s.getName().equals(name)).toList();
    }

    public Collection<MetricData> metrics() {
        return metrics.collectAllMetrics();
    }

    /** The {@code pipeline.latency} point for one stage, if anything was recorded for it. */
    public Optional<HistogramPointData> latency(String stage) {
        return metrics().stream().filter(m -> m.getName().equals("pipeline.latency"))
            .flatMap(m -> m.getHistogramData().getPoints().stream())
            .filter(p -> stage.equals(p.getAttributes().get(AttributeKey.stringKey("stage"))))
            .findFirst();
    }

    /** How many times {@code pipeline.latency} was recorded for one stage. */
    public long latencyCount(String stage) {
        return latency(stage).map(HistogramPointData::getCount).orElse(0L);
    }

    /** A counter's value, for the point whose {@code key} is {@code value} (key null: the sum of all points). */
    public long counter(String name, String key, String value) {
        return metrics().stream().filter(m -> m.getName().equals(name))
            .flatMap(m -> m.getLongSumData().getPoints().stream())
            .filter(p -> key == null || value.equals(p.getAttributes().get(AttributeKey.stringKey(key))))
            .mapToLong(LongPointData::getValue).sum();
    }

    @Override
    public void close() {
        Telemetry.install(OpenTelemetry.noop());
        sdk.close();
    }
}
