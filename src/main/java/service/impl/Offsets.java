package service.impl;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.util.Map;
import java.util.Set;

/** Consumer position helpers shared by Stage 1 and Stage 2. */
final class Offsets {

    private Offsets() {}

    /**
     * After a failure mid-batch, go back to the last commit so the batch is read again. Without
     * this the consumer's in-memory position has already moved past the failed records, and they
     * would be skipped until the next rebalance.
     */
    static void rewindToCommitted(Consumer<?, ?> consumer) {
        Set<TopicPartition> assigned = consumer.assignment();
        Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(assigned);
        for (TopicPartition tp : assigned) {
            OffsetAndMetadata o = committed.get(tp);
            if (o != null) consumer.seek(tp, o.offset());
            else consumer.seekToBeginning(Set.of(tp));
        }
    }
}
