package util;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.TopicPartition;

import java.util.Collection;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Logs every change to which lanes (partitions) a worker owns. A rebalance is what a crash, a cut, or a
 * deploy looks like from inside a worker, so these lines are how a person reads a fault's effect in
 * the logs. Revoked lanes were handed over cleanly; lost lanes were taken away while this worker was
 * unreachable (another member may already be working them). Runs on the polling thread.
 */
public final class LaneLog implements ConsumerRebalanceListener {

    private final String service;
    private final Consumer<Collection<TopicPartition>> dropped;

    public LaneLog(String service) {
        this(service, lanes -> {});
    }

    /** {@code dropped}: told about every lane this worker stops owning, revoked or lost. */
    public LaneLog(String service, Consumer<Collection<TopicPartition>> dropped) {
        this.service = service;
        this.dropped = dropped;
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> lanes) {
        log("assigned", lanes);
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> lanes) {
        log("revoked", lanes);
        dropped.accept(lanes);
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> lanes) {
        log("lost", lanes);
        dropped.accept(lanes);
    }

    private void log(String what, Collection<TopicPartition> lanes) {
        if (!lanes.isEmpty()) System.out.println("[" + service + "] lanes " + what + ": " + describe(lanes));
    }

    static String describe(Collection<TopicPartition> lanes) {
        return lanes.stream()
                .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
                .map(TopicPartition::toString)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
