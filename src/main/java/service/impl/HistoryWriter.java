package service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import model.NotificationMessage;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.errors.WakeupException;
import util.LoopHealth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saves notification history to Postgres (Phase 3.2 spec, 7.2). A consumer group of its own on the
 * notifications topic, beside Stage 2: if Postgres is slow or down, history falls behind and catches
 * up later, but Stage 2's pushes never wait for it. Same loop as Stage 2: poll, insert the batch,
 * commit; a failure rewinds to the batch's start and retries.
 */
public final class HistoryWriter implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final Consumer<String, String> consumer;
    private final HistoryStore store;
    private volatile boolean running = true;

    public HistoryWriter(Consumer<String, String> consumer, HistoryStore store) {
        this.consumer = consumer;
        this.store = store;
    }

    /**
     * Inserts one batch and commits it.
     *
     * @return records handled, including unreadable ones that were skipped
     * @throws RuntimeException if the insert failed; the consumer is rewound to the batch's start first
     */
    public int pollOnce(Duration timeout) {
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        // Keyed by (user, message): a duplicate inside one batch would otherwise be two rows in one statement.
        Map<String, HistoryStore.HistoryRow> rows = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> r : records) {
            HistoryStore.HistoryRow row = toRow(r);
            if (row == null) {
                // A record that can't be read never will be. Skip it so it doesn't stall the lane.
                System.err.println("[History] skipped unreadable record at " + r.topic() + "-" + r.partition() + "@" + r.offset());
                continue;
            }
            rows.putIfAbsent(row.userId() + " " + row.messageId(), row);
        }
        try {
            store.insertBatch(new ArrayList<>(rows.values()));
        } catch (Exception e) {
            Offsets.rewindTo(consumer, records);
            throw new IllegalStateException("history insert failed", e);
        }
        if (!records.isEmpty()) consumer.commitSync();
        return records.count();
    }

    /**
     * {@code created_at} is the record's Kafka timestamp, set when Stage 1 sent it, not the insert
     * time: history that catches up after a Postgres outage keeps its real order and isn't unread.
     */
    private static HistoryStore.HistoryRow toRow(ConsumerRecord<String, String> r) {
        NotificationMessage m;
        try {
            m = GSON.fromJson(r.value(), NotificationMessage.class);
        } catch (JsonParseException e) {
            return null;
        }
        if (m == null || m.getMessageId() == null || m.getNotification() == null || !isUuid(m.getUserId())) return null;
        JsonObject game = m.getNotification().getAsJsonObject("game");
        return new HistoryStore.HistoryRow(m.getUserId(), m.getMessageId(), Instant.ofEpochMilli(r.timestamp()),
                m.getLeague(), m.getGameId(), teamId(game, "home"), teamId(game, "away"), m.getNotification().toString());
    }

    private static String teamId(JsonObject game, String side) {
        try {
            return game.getAsJsonObject(side).getAsJsonObject("team").get("id").getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isUuid(String s) {
        try {
            return s != null && UUID.fromString(s) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The loop: poll, insert, commit. A failure is logged and the batch is retried after a second. */
    public void run(LoopHealth health) {
        while (running) {
            health.beat();
            try {
                pollOnce(Duration.ofMillis(500));
            } catch (WakeupException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                System.err.println("[History] batch failed, retrying from the last commit: " + e.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    /** Ends {@link #run} from another thread. */
    public void stop() {
        running = false;
        consumer.wakeup();
    }

    /** Call on the polling thread, or after {@link #run} has returned. */
    @Override
    public void close() {
        consumer.close();
    }
}
