package service.impl;

import com.google.gson.Gson;
import model.GameEvent;
import model.NotificationMessage;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import redis.clients.jedis.JedisPool;
import util.GameEventParser;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stage 1: reads accepted plays from game-events and writes one notifications message per user who
 * should hear about each (DESIGN.md decision 3; Phase 3.1 spec, section 2.2).
 *
 * Kafka gives each game's lane to one worker in the group, so a game's plays arrive here in version
 * order. Everything runs on one thread, one record at a time: per-user order on the notifications
 * topic depends on it. Offsets are committed only after a whole batch is handled, so a crash
 * re-reads instead of skipping. What a re-read does depends on where the crash was:
 *
 * <pre>
 *   crash point                                  replacement worker            result
 *   before Kafka confirms the messages           re-reads, sends again         duplicates; Stage 2 dedups them
 *   after recording the version, before commit  re-reads, finds the version   skips; messages were confirmed
 * </pre>
 *
 * That is why the version is recorded only after every message is confirmed: recording first and
 * crashing before the sends would skip the play for good.
 */
public final class Stage1Worker implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final Stage1Progress progress;
    private final Consumer<String, String> consumer;
    private final Producer<String, String> producer;
    private final String notificationsTopic;
    private final Audience audience;
    private volatile boolean running = true;

    public Stage1Worker(JedisPool pool, Stage1Progress progress, Consumer<String, String> consumer,
                        Producer<String, String> producer, String notificationsTopic) {
        this.progress = progress;
        this.consumer = consumer;
        this.producer = producer;
        this.notificationsTopic = notificationsTopic;
        this.audience = new Audience(pool);
    }

    /**
     * Handles one batch and commits it.
     *
     * @return records handled, including skipped duplicates and unreadable records
     * @throws RuntimeException if a play could not be handled; the consumer is rewound to the last
     *         commit first, so the next poll reads the batch again
     */
    public int pollOnce(Duration timeout) {
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        int handled = 0;
        try {
            for (ConsumerRecord<String, String> r : records) {
                handle(r);
                handled++;
            }
        } catch (RuntimeException e) {
            rewindToCommitted();
            throw e;
        }
        if (!records.isEmpty()) consumer.commitSync();
        return handled;
    }

    private void handle(ConsumerRecord<String, String> r) {
        GameEvent play;
        try {
            play = GameEventParser.parse(r.value());
        } catch (RuntimeException e) {
            play = null;
        }
        if (play == null || play.getGameId() == null || play.getVersion() <= 0) {
            // A record that can't be read never will be. Skip it so it doesn't stall the lane.
            System.err.println("[Stage1] skipped unreadable record at " + r.topic() + "-" + r.partition() + "@" + r.offset());
            return;
        }
        if (play.getVersion() <= progress.last(play.getGameId())) return;

        if (!EventProcessingServiceImpl.GAME_SYNC.equals(play.getEventType())) {
            AtomicReference<Exception> failed = new AtomicReference<>();
            final GameEvent p = play;
            audience.forEachPage(p, page -> {
                for (String userId : page) {
                    producer.send(new ProducerRecord<>(notificationsTopic, userId, GSON.toJson(NotificationMessage.of(p, userId))),
                        (meta, err) -> { if (err != null) failed.compareAndSet(null, err); });
                }
            });
            producer.flush();
            if (failed.get() != null) throw new IllegalStateException("notifications not all confirmed", failed.get());
        }
        progress.recordIfHigher(play.getGameId(), play.getVersion());
    }

    /** After a failure mid-batch: go back to the last commit, so the failed play is read again. */
    private void rewindToCommitted() {
        Set<TopicPartition> assigned = consumer.assignment();
        Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(assigned);
        for (TopicPartition tp : assigned) {
            OffsetAndMetadata o = committed.get(tp);
            if (o != null) consumer.seek(tp, o.offset());
            else consumer.seekToBeginning(Set.of(tp));
        }
    }

    /** The loop: poll, handle, commit. A failure is logged and the batch is retried after a second. */
    public void run() {
        while (running) {
            try {
                pollOnce(Duration.ofMillis(500));
            } catch (WakeupException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                System.err.println("[Stage1] batch failed, retrying from the last commit: " + e.getMessage());
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
        producer.close(Duration.ofSeconds(5));
    }
}
