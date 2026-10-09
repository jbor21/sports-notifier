package service.impl;

import alerts.Detectors;
import alerts.GameMemory;
import alerts.Play;
import com.google.gson.Gson;
import model.GameEvent;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisException;
import util.GameEventParser;

import util.LoopHealth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stage 1: reads accepted plays from game-events, runs the detectors over each, and writes one
 * notifications message per user per moment, plus the final as its own (DESIGN.md decisions 3 and 15;
 * Phase 3.1 spec, section 2.2; Phase 3.3 spec, sections 2 and 5).
 *
 * Kafka gives each game's lane to one worker in the group, so a game's plays arrive here in version
 * order. Everything runs on one thread, one record at a time: per-user order on the notifications
 * topic depends on it. Offsets are committed only after a whole batch is handled, so a crash
 * re-reads instead of skipping. What a re-read does depends on where the crash was:
 *
 * <pre>
 *   crash point                                  replacement worker            result
 *   before Kafka confirms the messages           re-reads, sends again         duplicates; Stage 2 dedups them
 *   after writing det:, before commit            re-reads, finds the version   skips; messages were confirmed
 *   after dead-lettering, before det:/commit     re-reads, fails it 3 times    dead-lettered again (a duplicate there)
 * </pre>
 *
 * That is why det:<gameId> (the version and the detectors' memory) is written only after every message
 * is confirmed: writing first and crashing before the sends would skip the play's alerts for good. A
 * re-read runs the detectors against the same memory, so it finds the same alerts with the same ids.
 *
 * While the sub:* sets are being rebuilt ({@code sub:built} missing), an audience built from them
 * could be empty and the play's alerts silently lost. So Stage 1 waits: it rewinds the batch and
 * pauses its lanes, but keeps polling (a paused consumer's poll returns nothing and keeps it in the
 * group) and keeps beating, so a long rebuild delays alerts without failing /health or causing a
 * rebalance (Phase 3.2 spec, section 6).
 */
public final class Stage1Worker implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final DetectorMemory memory;
    private final Consumer<String, String> consumer;
    private final Producer<String, String> producer;
    private final String notificationsTopic;
    private final String deadLetterTopic;
    private static final long WAIT_WARN_EVERY_MILLIS = 30_000L;
    /** Tries of a play that fails for a reason other than I/O before it is dead-lettered. */
    static final int POISON_ATTEMPTS = 3;

    private final Detectors detectors;
    private final MomentFanout fanout;
    private final BooleanSupplier setsReady;
    private volatile boolean running = true;
    private volatile boolean waitingForSets;
    /** Failed tries per record ("partition@offset"), for plays failing for a non-I/O reason. */
    private final Map<String, Integer> attempts = new HashMap<>();
    private final AtomicLong deadLettered = new AtomicLong();
    private long lastWaitWarning;

    /** {@code setsReady}: whether the sub:* sets can be trusted ({@code sub:built} exists). */
    public Stage1Worker(DetectorMemory memory, Detectors detectors, MomentFanout fanout, Consumer<String, String> consumer,
                        Producer<String, String> producer, String notificationsTopic, String deadLetterTopic,
                        BooleanSupplier setsReady) {
        this.memory = memory;
        this.detectors = detectors;
        this.fanout = fanout;
        this.consumer = consumer;
        this.producer = producer;
        this.notificationsTopic = notificationsTopic;
        this.deadLetterTopic = deadLetterTopic;
        this.setsReady = setsReady;
    }

    /** Plays given up on and sent to the dead-letter topic since start. */
    public long deadLettered() {
        return deadLettered.get();
    }

    public boolean isWaitingForSets() {
        return waitingForSets;
    }

    /**
     * Handles one batch and commits it.
     *
     * @return records handled, including skipped duplicates and unreadable records
     * @throws RuntimeException if a play could not be handled; the consumer is rewound to the start
     *         of the batch first, so the next poll reads it again
     */
    public int pollOnce(Duration timeout) {
        if (waitingForSets) {
            if (setsReady.getAsBoolean()) {
                consumer.resume(consumer.paused());
                waitingForSets = false;
                System.out.println("[Stage1] sub sets are rebuilt; handling plays again");
            } else {
                consumer.pause(consumer.assignment());   // a rebalance can hand us new, unpaused lanes
                warnWaiting();
            }
        }
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        boolean hold;
        try {
            hold = !records.isEmpty() && (waitingForSets || !setsReady.getAsBoolean());
        } catch (RuntimeException e) {
            // The check is the first Redis call after the poll: if Redis is down, rewind so the batch
            // is read again, or the next commit would move past it and its alerts would be lost.
            Offsets.rewindTo(consumer, records);
            throw e;
        }
        if (hold) {
            Offsets.rewindTo(consumer, records);
            consumer.pause(consumer.assignment());
            if (!waitingForSets) {
                waitingForSets = true;
                lastWaitWarning = 0;
                warnWaiting();
            }
            return 0;
        }
        int handled = 0;
        try {
            for (ConsumerRecord<String, String> r : records) {
                try {
                    handle(r);
                    attempts.remove(attemptKey(r));
                } catch (RuntimeException | StackOverflowError e) {
                    // An outage (Redis, Kafka) is retried for as long as it lasts: waiting fixes it, and
                    // skipping would lose the play's alerts. Anything else (a detector bug, a value the
                    // fan-out can't handle) fails the same way every time, so after a few tries the play
                    // is set aside and the lane, a twelfth of all games, moves on (issue #15). A detector
                    // that recurses forever counts too: uncaught, it would kill the loop and every lane.
                    if (isOutage(e) || attempts.merge(attemptKey(r), 1, Integer::sum) < POISON_ATTEMPTS) {
                        throw e instanceof RuntimeException re ? re : new IllegalStateException("play failed", e);
                    }
                    deadLetter(r, e);
                    attempts.remove(attemptKey(r));
                }
                handled++;
            }
        } catch (RuntimeException e) {
            Offsets.rewindTo(consumer, records);
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
        DetectorMemory.Stored stored = memory.read(play.getGameId());
        if (play.getVersion() <= stored.version()) return;

        Play p = Play.of(play);
        Detectors.Result result = detectors.run(stored.memory(), p);
        if (!result.moment().isEmpty() || result.finalAlert() != null) {
            AtomicReference<Exception> failed = new AtomicReference<>();
            fanout.send(p, result, (userId, message) ->
                producer.send(new ProducerRecord<>(notificationsTopic, userId, GSON.toJson(message)),
                    (meta, err) -> { if (err != null) failed.compareAndSet(null, err); }));
            producer.flush();
            if (failed.get() != null) throw new IllegalStateException("notifications not all confirmed", failed.get());
        }
        memory.writeIfHigher(play.getGameId(), play.getVersion(), result.after());
    }

    /**
     * Sends the play to the dead-letter topic with why it failed, then moves the game's det: version
     * past it (its memory unchanged), so a re-read after a crash or rebalance skips it instead of
     * dead-lettering it again. A failure here is I/O and fails the batch, so a play is never skipped
     * without being kept. If the play had sent some pushes before failing, its score counter was not
     * saved: the game's next score can reuse that alert id, and the users who got the first push
     * miss it (Stage 2's dedup). Rare, since it needs a fan-out that fails part way.
     */
    private void deadLetter(ConsumerRecord<String, String> r, Throwable cause) {
        GameEvent play = GameEventParser.parse(r.value());
        // Read the memory before sending, so nothing below can fail for a non-I/O reason after the
        // send and dead-letter the play again on every retry. Memory that can't be read (its shape
        // changed in a deploy, say) is forgotten: the game's next play starts from a baseline.
        GameMemory keep = null;
        boolean unreadable = false;
        try {
            keep = memory.read(play.getGameId()).memory();
        } catch (RuntimeException e) {
            if (isOutage(e)) throw e;
            unreadable = true;
        }
        ProducerRecord<String, String> dead = new ProducerRecord<>(deadLetterTopic, r.key(), r.value());
        dead.headers().add("error", String.valueOf(cause).getBytes(StandardCharsets.UTF_8));
        dead.headers().add("source", (r.topic() + "-" + r.partition() + "@" + r.offset()).getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(dead).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaException("interrupted while dead-lettering", e);
        } catch (ExecutionException e) {
            throw new KafkaException("dead-letter send failed", e.getCause());
        }
        if (unreadable) memory.forget(play.getGameId());
        else if (keep != null) memory.writeIfHigher(play.getGameId(), play.getVersion(), keep);
        deadLettered.incrementAndGet();
        System.err.println("[Stage1] DEAD-LETTERED play " + play.getGameId() + " v" + play.getVersion() + " from "
            + r.topic() + "-" + r.partition() + "@" + r.offset() + " after " + POISON_ATTEMPTS + " tries: " + cause);
        cause.printStackTrace();
    }

    /** Redis error replies that clear up on their own; any other reply will be the same next time. */
    private static final List<String> PASSING_REDIS_ERRORS =
        List.of("LOADING", "BUSY", "TRYAGAIN", "MASTERDOWN", "READONLY", "OOM", "NOREPLICAS", "CLUSTERDOWN");

    /**
     * Redis or Kafka trouble anywhere in the cause chain: an outage, not a bad play. Except errors
     * that a retry can never fix: a Redis command error (wrong arguments, wrong type), and a record
     * too large or unserializable for Kafka. Those are the play's fault.
     */
    private static boolean isOutage(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof JedisDataException d) {
                String reply = String.valueOf(d.getMessage());
                return PASSING_REDIS_ERRORS.stream().anyMatch(reply::startsWith);
            }
            if (t instanceof RecordTooLargeException || t instanceof SerializationException) return false;
            if (t instanceof JedisException || t instanceof KafkaException) return true;
        }
        return false;
    }

    private static String attemptKey(ConsumerRecord<String, String> r) {
        return r.partition() + "@" + r.offset();
    }

    /** The loop: poll, handle, commit. A failure is logged and the batch is retried after a second. */
    public void run(LoopHealth health) {
        while (running) {
            health.beat();
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

    private void warnWaiting() {
        long now = System.currentTimeMillis();
        if (now - lastWaitWarning >= WAIT_WARN_EVERY_MILLIS) {
            System.err.println("[Stage1] waiting: sub:built is missing, so the sub sets are being rebuilt; plays are held, not dropped");
            lastWaitWarning = now;
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
