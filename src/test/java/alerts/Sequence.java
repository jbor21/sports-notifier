package alerts;

import catalog.Teams;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Runs plays through detectors in order, the first play as the baseline, and collects what came out. */
final class Sequence {

    static final GameLabels LABELS = new GameLabels(Teams.load());

    final List<Detectors.Result> results = new ArrayList<>();
    private final Detectors detectors;
    private GameMemory memory;

    private Sequence(Detectors detectors) {
        this.detectors = detectors;
    }

    static Sequence of(Detector... chain) {
        return new Sequence(new Detectors(List.of(chain)));
    }

    static Sequence standard() {
        return new Sequence(Detectors.standard(LABELS));
    }

    /** Starts from an existing memory instead of a baseline. */
    Sequence from(GameMemory m) {
        memory = m;
        return this;
    }

    Sequence play(PlayBuilder... plays) {
        for (PlayBuilder p : plays) {
            Detectors.Result r = detectors.run(memory, p.build());
            results.add(r);
            memory = r.after();
        }
        return this;
    }

    GameMemory memory() {
        return memory;
    }

    /** Every moment alert after the baseline, in order. */
    List<Alert> alerts() {
        List<Alert> all = new ArrayList<>();
        results.forEach(r -> all.addAll(r.moment()));
        return all;
    }

    List<String> ids() {
        return alerts().stream().map(Alert::id).toList();
    }

    List<String> texts() {
        return alerts().stream().map(Alert::text).toList();
    }

    List<Alert> finals() {
        return results.stream().map(Detectors.Result::finalAlert).filter(Objects::nonNull).toList();
    }

    /** The moment of the last play. */
    List<Alert> last() {
        return results.get(results.size() - 1).moment();
    }
}
