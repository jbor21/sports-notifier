package service.impl;

import model.GameEvent;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import service.PlayPublisher;
import util.GameEventParser;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Sends accepted plays to game-events. The key is the game id, so every play of a game hashes to
 * the same partition and is read in order by the one Stage 1 worker that owns it (DESIGN.md
 * decision 4). The producer is built from KafkaSettings.ingestProducer: acks=all and idempotence,
 * so "confirmed" means stored, and Kafka's own retries can't duplicate or reorder a game's plays.
 */
public final class KafkaPlayPublisher implements PlayPublisher, AutoCloseable {

    private final Producer<String, String> producer;
    private final String topic;

    public KafkaPlayPublisher(Producer<String, String> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    @Override
    public CompletableFuture<Void> publish(GameEvent play) {
        CompletableFuture<Void> confirmed = new CompletableFuture<>();
        try {
            producer.send(new ProducerRecord<>(topic, play.getGameId(), GameEventParser.toJson(play)), (meta, err) -> {
                if (err != null) confirmed.completeExceptionally(err);
                else confirmed.complete(null);
            });
        } catch (RuntimeException e) {
            // send() itself throws when the broker's metadata can't be fetched within max.block.ms.
            confirmed.completeExceptionally(e);
        }
        return confirmed;
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(5));
    }
}
