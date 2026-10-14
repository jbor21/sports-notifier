package service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import model.NotificationMessage;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.errors.WakeupException;

import telemetry.PipelineMetrics;
import telemetry.Traces;
import util.LoopHealth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 2: reads notifications (keyed by user, so a user's messages share a lane) and delivers each
 * through {@link Delivery}'s script. One thread, one batch at a time, in lane order: a user sees
 * their notifications in the order Stage 1 sent them. Offsets are committed after the batch is
 * delivered; a crash re-reads, and the script's dedup key makes the re-reads no-ops.
 */
public final class Stage2Worker implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final Consumer<String, String> consumer;
    private final Delivery delivery;
    private volatile boolean running = true;

    public Stage2Worker(Consumer<String, String> consumer, Delivery delivery) {
        this.consumer = consumer;
        this.delivery = delivery;
    }

    /**
     * Delivers one batch and commits it.
     *
     * @return records handled, including unreadable ones that were skipped
     * @throws RuntimeException if delivery failed; the consumer is rewound to the batch's start first
     */
    public int pollOnce(Duration timeout) {
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        List<NotificationMessage> batch = new ArrayList<>();
        List<Span> spans = new ArrayList<>();
        for (ConsumerRecord<String, String> r : records) {
            NotificationMessage m = parse(r.value());
            if (m == null || m.getMessageId() == null || m.getUserId() == null) {
                // A record that can't be read never will be. Skip it so it doesn't stall the lane.
                System.err.println("[Stage2] skipped unreadable record at " + r.topic() + "-" + r.partition() + "@" + r.offset());
                continue;
            }
            m.setCreatedAt(r.timestamp());
            // One deliver span per message, a child of Stage 1's fanout. The batch's scripts run as one
            // pipeline, so every span of a batch lasts the pipeline's round trip, not one script's.
            Span span = Traces.start("deliver", SpanKind.CONSUMER, Traces.extract(r.headers()));
            m.setTraceparent(Traces.traceparent(span));
            spans.add(span);
            batch.add(m);
        }
        List<Boolean> delivered;
        try {
            delivered = delivery.deliverAll(batch);
        } catch (RuntimeException e) {
            for (Span span : spans) {
                span.recordException(e);
                span.setStatus(StatusCode.ERROR);
                span.end();
            }
            Offsets.rewindTo(consumer, records);
            throw e;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < batch.size(); i++) {
            Span span = spans.get(i);
            String result = delivered.get(i) ? "delivered" : "duplicate";
            span.setAttribute("deliver.result", result);
            // Recorded with the span current, so the sample's exemplar names this push's trace.
            try (Scope scope = span.makeCurrent()) {
                PipelineMetrics.delivery(result);
                if (delivered.get(i)) PipelineMetrics.latency(PipelineMetrics.Stage.STAGE2, batch.get(i).getDetectedAt(), now);
            }
            span.end();
        }
        if (!records.isEmpty()) consumer.commitSync();
        return records.count();
    }

    private static NotificationMessage parse(String json) {
        try {
            return GSON.fromJson(json, NotificationMessage.class);
        } catch (JsonParseException e) {
            return null;
        }
    }

    /** The loop: poll, deliver, commit. A failure is logged and the batch is retried after a second. */
    public void run(LoopHealth health) {
        while (running) {
            health.beat();
            try {
                pollOnce(Duration.ofMillis(500));
            } catch (WakeupException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                System.err.println("[Stage2] batch failed, retrying from the last commit: " + e.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    /** Ends {@link #run()} from another thread. */
    public void stop() {
        running = false;
        consumer.wakeup();
    }

    /** Call on the polling thread, or after {@link #run()} has returned. */
    @Override
    public void close() {
        consumer.close();
    }
}
