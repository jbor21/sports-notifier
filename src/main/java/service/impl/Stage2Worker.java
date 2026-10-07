package service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import model.NotificationMessage;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.errors.WakeupException;

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
     * @throws RuntimeException if delivery failed; the consumer is rewound to the last commit first
     */
    public int pollOnce(Duration timeout) {
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        List<NotificationMessage> batch = new ArrayList<>();
        for (ConsumerRecord<String, String> r : records) {
            NotificationMessage m = parse(r.value());
            if (m == null || m.getMessageId() == null || m.getUserId() == null) {
                // A record that can't be read never will be. Skip it so it doesn't stall the lane.
                System.err.println("[Stage2] skipped unreadable record at " + r.topic() + "-" + r.partition() + "@" + r.offset());
                continue;
            }
            batch.add(m);
        }
        try {
            delivery.deliverAll(batch);
        } catch (RuntimeException e) {
            Offsets.rewindToCommitted(consumer);
            throw e;
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
    public void run() {
        while (running) {
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
