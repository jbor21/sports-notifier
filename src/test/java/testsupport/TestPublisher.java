package testsupport;

import model.GameEvent;
import service.PlayPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Stands in for Kafka in tests that only care what Event Processing sent: records every play, and
 * either confirms it at once (so the outbox clears) or holds it unconfirmed (a crash, a slow broker).
 */
public final class TestPublisher implements PlayPublisher {

    public final List<GameEvent> plays = new ArrayList<>();
    public final List<CompletableFuture<Void>> pending = new ArrayList<>();
    private final List<String> traceparents = new ArrayList<>();
    private final boolean autoComplete;

    public TestPublisher(boolean autoComplete) {
        this.autoComplete = autoComplete;
    }

    @Override
    public synchronized CompletableFuture<Void> publish(GameEvent play, String traceparent) {
        plays.add(play);
        traceparents.add(traceparent);
        CompletableFuture<Void> f = autoComplete ? CompletableFuture.completedFuture(null) : new CompletableFuture<>();
        pending.add(f);
        return f;
    }

    /** The trace context sent with each play, in order (null: none). */
    public synchronized List<String> traceparents() {
        return new ArrayList<>(traceparents);
    }

    /** Event types sent, in order. */
    public synchronized List<String> types() {
        return plays.stream().map(GameEvent::getEventType).toList();
    }
}
