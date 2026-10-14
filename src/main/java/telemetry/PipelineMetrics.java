package telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

import java.util.List;

/**
 * The pipeline's metrics (spec section 4). One histogram, {@code pipeline.latency}: time since the
 * poller saw the play ({@code detectedAt}), recorded at four stages, so the four p99 lines stack and
 * the space between two lines is that hop's cost. Each record uses the current context, so when a
 * sampled span is current the SDK keeps its trace id beside the sample as an exemplar: a point in a
 * spike on the dashboard opens that play's trace.
 */
public final class PipelineMetrics {

    public enum Stage {
        ACCEPTED("accepted"), STAGE1("stage1"), STAGE2("stage2"), GATEWAY("gateway");

        final Attributes attributes;

        Stage(String value) {
            this.attributes = Attributes.of(STAGE, value);
        }
    }

    /**
     * Seconds. Up to 120 s: a crashed worker stalls pushes for about 45 s (Kafka's session timeout), and
     * the spike must read as 45 s, not as "over 10 s" where the SDK's default buckets stop.
     */
    public static final List<Double> LATENCY_BUCKETS_SECONDS =
        List.of(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0);

    private static final AttributeKey<String> STAGE = AttributeKey.stringKey("stage");
    private static final AttributeKey<String> RESULT = AttributeKey.stringKey("result");

    private record Instruments(DoubleHistogram latency, LongCounter playsAccepted, LongCounter pushesProduced,
                               LongCounter deliveries, LongCounter socketSends) {}

    private static volatile Instruments in = build(OpenTelemetry.noop());

    private PipelineMetrics() {}

    /** Called by {@link Telemetry#install}: instruments always belong to the current implementation. */
    static void rebuild(OpenTelemetry otel) {
        in = build(otel);
    }

    private static Instruments build(OpenTelemetry otel) {
        Meter m = otel.getMeter(Telemetry.SCOPE);
        return new Instruments(
            m.histogramBuilder("pipeline.latency").setUnit("s")
                .setDescription("Time since the poller saw the play, at each stage")
                .setExplicitBucketBoundariesAdvice(LATENCY_BUCKETS_SECONDS).build(),
            m.counterBuilder("plays.accepted").setDescription("Accept script answers, by result").build(),
            m.counterBuilder("pushes.produced").setDescription("notifications messages Stage 1 sent").build(),
            m.counterBuilder("deliveries").setDescription("Stage 2 delivery scripts, by result").build(),
            m.counterBuilder("socket.sends").setDescription("Pushes the gateway wrote to a user's sockets").build());
    }

    /** Records now − detectedAt; a poller clock ahead of ours reads as 0, never negative. */
    public static void latency(Stage stage, long detectedAtMillis, long nowMillis) {
        in.latency().record(Math.max(0L, nowMillis - detectedAtMillis) / 1000.0, stage.attributes);
    }

    public static void playAccepted(String result) {
        in.playsAccepted().add(1, Attributes.of(RESULT, result));
    }

    public static void pushProduced() {
        in.pushesProduced().add(1);
    }

    public static void delivery(String result) {
        in.deliveries().add(1, Attributes.of(RESULT, result));
    }

    public static void socketSend() {
        in.socketSends().add(1);
    }
}
