package service.impl;

import model.GameEvent;
import service.PlayPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * The retry loop: re-sends plays still in the outbox after minAgeMillis (Event Processing runs it
 * every second with 5 s). That covers a pod that crashed after the script accepted a play and before
 * Kafka confirmed it, and a send that failed during a Kafka outage.
 *
 * Every Event Processing pod runs one. A loop skips a play while its own earlier send of it is
 * still in flight, so a slow or down Kafka doesn't collect a new copy every second. Different pods
 * (and the original send) can still send the same play; those copies carry the same version, and
 * Stage 1 drops them. They can't send one game's plays out of order, because the accept script
 * keeps at most one play per game in the outbox.
 */
public final class OutboxRelay {

    private final Outbox outbox;
    private final PlayPublisher publisher;
    private final LongSupplier nowMillis;
    private final long minAgeMillis;
    /** "gameId:version" of plays this loop is sending right now. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

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
            play.ifPresent(p -> {
                String key = p.getGameId() + ":" + p.getVersion();
                if (!inFlight.add(key)) return;
                sends.add(outbox.send(publisher, p)
                    .whenComplete((ok, err) -> inFlight.remove(key))
                    .exceptionally(err -> null));
            });
        }
        return CompletableFuture.allOf(sends.toArray(new CompletableFuture[0]));
    }
}
