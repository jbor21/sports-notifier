package service;

import model.GameEvent;

import java.util.concurrent.CompletableFuture;

/** Sends an accepted play to the log that Stage 1 reads. */
public interface PlayPublisher {

    /**
     * @param play an accepted play, with its version set
     * @return completes once the log has stored the play; completes exceptionally if it did not
     */
    CompletableFuture<Void> publish(GameEvent play);
}
