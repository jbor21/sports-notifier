package simulator;

import feed.GameIds;
import feed.ScoreFeed;
import model.GameSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * A score feed that plays back a wave of {@link GameScript}s, so the simulator runs through the same
 * {@code Poller} as ESPN (spec 1.4). Each game moves one step per {@code stepMillis} from its start
 * and then holds its final step.
 *
 * Game ids are {@code <league>-sim<run start, epoch seconds>-<slot>}, new for every run. Alert ids
 * are built from the game id, so a reused id would find the last run's detector memory (start and
 * final already sent), Stage 2's dedup keys, and history's rows, and the rerun would alert nobody.
 */
public final class SimulatedScoreFeed implements ScoreFeed {

    /** Starts are spread by up to this much, the only randomness, so games don't move in lockstep. */
    private static final long MAX_JITTER_MILLIS = 600;

    private record Game(String id, GameScript script, long startMillis) {}

    private final List<Game> games = new ArrayList<>();
    private final LongSupplier clock;
    private final long stepMillis;

    public SimulatedScoreFeed(long runStartSeconds, List<GameScript> wave, LongSupplier clock, long stepMillis) {
        this.clock = clock;
        this.stepMillis = stepMillis;
        long created = clock.getAsLong();
        // Under half a step, so a game is always on the step a whole number of steps after creation.
        long maxJitter = Math.min(MAX_JITTER_MILLIS, stepMillis / 2);
        for (int i = 0; i < wave.size(); i++) {
            GameScript script = wave.get(i);
            int slot = script.slot() > 0 ? script.slot() : i + 1;
            long jitter = maxJitter > 0 ? ThreadLocalRandom.current().nextLong(maxJitter) : 0;
            games.add(new Game(GameIds.of(script.league(), "sim" + runStartSeconds + "-" + slot), script, created + jitter));
        }
    }

    @Override
    public List<GameSnapshot> fetch(String league) {
        long now = clock.getAsLong();
        List<GameSnapshot> out = new ArrayList<>();
        for (Game g : games) {
            if (!g.script().league().equalsIgnoreCase(league)) continue;
            // The scheduled start is when the first live step comes up.
            out.add(g.script().snapshot(g.id(), stepOf(g, now), g.startMillis() + stepMillis));
        }
        return out;
    }

    /** True once every game has reached its last (final) step. */
    public boolean done() {
        long now = clock.getAsLong();
        for (Game g : games) {
            if (stepOf(g, now) < g.script().steps().size() - 1) return false;
        }
        return true;
    }

    /** The wave's game ids, in wave order. */
    public List<String> gameIds() {
        return games.stream().map(Game::id).toList();
    }

    private int stepOf(Game g, long now) {
        if (now <= g.startMillis()) return 0;
        long step = (now - g.startMillis()) / stepMillis;
        return (int) Math.min(step, g.script().steps().size() - 1);
    }
}
