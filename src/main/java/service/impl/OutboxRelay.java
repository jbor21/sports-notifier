package service.impl;

import model.GameEvent;
import service.PlayPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * The retry loop: re-sends plays still in the outbox after minAgeMillis (Event Processing runs it
 * every second with 5 s). That covers a pod that crashed after the script accepted a play and before
 * Kafka confirmed it, and a send that failed during a Kafka outage.
 *
 * Every Event Processing pod runs one. Two can send the same play at once; that puts one duplicate
 * with the same version on the topic, which Stage 1 drops. They can't send one game's plays out of
 * order, because the accept script keeps at most one play per game in the outbox.
 */
public final class OutboxRelay {

    private final Outbox outbox;
    private final PlayPublisher publisher;
    private final LongSupplier nowMillis;
    private final long minAgeMillis;

    public OutboxRelay(Outbox outbox, PlayPublisher publisher, LongSupplier nowMillis, long minAgeMillis) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.nowMillis = nowMillis;
        this.minAgeMillis = minAgeMillis;
    }

    /** @return completes when every send this run started has been confirmed or has failed */
    public CompletableFuture<Void> runOnce() {
        List<CompletableFuture<Void>> sends = new ArrayList<>();
        for (String gameId : outbox.gamesWaitingSince(nowMillis.getAsLong() - minAgeMillis)) {
            Optional<GameEvent> play = outbox.play(gameId);
            play.ifPresent(p -> sends.add(outbox.send(publisher, p).exceptionally(err -> null)));
        }
        return CompletableFuture.allOf(sends.toArray(new CompletableFuture[0]));
    }
}
