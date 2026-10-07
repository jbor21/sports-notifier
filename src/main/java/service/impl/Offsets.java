package service.impl;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;

/** Consumer position helpers shared by Stage 1 and Stage 2. */
final class Offsets {

    private Offsets() {}

    /**
     * After a failure mid-batch, go back to the start of the batch so it is read again. Without
     * this the consumer's in-memory position has already moved past the failed records, and they
     * would be skipped until the next rebalance.
     *
     * Seeks to each partition's first record in the batch rather than asking the broker for the
     * committed offset: the failure is often Kafka being down, and that request would fail too.
     * The workers commit after every batch, so the batch's start is the last commit.
     */
    static void rewindTo(Consumer<?, ?> consumer, ConsumerRecords<?, ?> batch) {
        for (TopicPartition tp : batch.partitions()) {
            consumer.seek(tp, batch.records(tp).get(0).offset());
        }
    }
}
