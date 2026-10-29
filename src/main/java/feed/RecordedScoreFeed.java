package feed;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import model.GameSnapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;

/**
 * Recorded ESPN games played back as a {@link ScoreFeed} (Phase 4.1 spec, 2.2), through the real
 * {@link Poller} the way live ESPN data is.
 *
 * A replay set ({@code scripts/pack-replay.py}) holds one timeline per game. Until {@link #startClock()}
 * the feed answers each game's first snapshot, so a priming poll creates every game's record before
 * the run (follows need it). Then replay time runs at {@code speed} times wall time, and each game
 * answers its latest snapshot at or before that time; a game whose timeline has not begun is absent.
 * Game ids carry the run id, so every run starts from empty state and checks can be scoped to it.
 */
public final class RecordedScoreFeed implements ScoreFeed {

    /** One line of a replay set: {@code t} ms on the game's timeline, {@code eventJson} a trimmed ESPN event. */
    public record Line(String game, String league, long t, String eventJson) {}

    private record Snap(long t, GameSnapshot snap) {}

    private final Map<String, List<Snap>> byGame = new LinkedHashMap<>();
    private final Map<String, String> leagueOf = new LinkedHashMap<>();
    private final LongSupplier clock;
    private final double speed;
    private final long lastT;
    private volatile long origin = -1;

    /**
     * @param runStartMillis when the run began; each game's scheduled start is moved to when its own
     *                       timeline begins (run start + its offset at {@code speed}), so it shows as today
     */
    public RecordedScoreFeed(List<Line> lines, String runId, long runStartMillis, LongSupplier clock, double speed) {
        this.clock = clock;
        this.speed = speed;
        Map<String, Long> shift = new LinkedHashMap<>();
        long last = 0;
        for (Line l : lines) {
            List<GameSnapshot> parsed = EspnScoreFeed.parse("{\"events\":[" + l.eventJson() + "]}", l.league());
            if (parsed.isEmpty()) continue;
            GameSnapshot raw = parsed.get(0);
            String id = gameId(l.league(), runId, l.game().substring(l.game().indexOf('-') + 1));
            long s = shift.computeIfAbsent(id, k ->
                raw.startTimeMillis() > 0 ? runStartMillis + Math.round(l.t() / speed) - raw.startTimeMillis() : 0L);
            byGame.computeIfAbsent(id, k -> new ArrayList<>()).add(new Snap(l.t(), raw.rebased(id, s)));
            leagueOf.put(id, l.league().toUpperCase());
            last = Math.max(last, l.t());
        }
        this.lastT = last;
    }

    /** {@code nfl-rp<runId>-401872971}. */
    public static String gameId(String league, String runId, String espnId) {
        return GameIds.replay(league, runId, espnId);
    }

    /** A replay set packed into the JAR: {@code replay/<set>.jsonl.gz}. */
    public static List<Line> load(String set) {
        InputStream in = RecordedScoreFeed.class.getResourceAsStream("/replay/" + set + ".jsonl.gz");
        if (in == null) throw new IllegalArgumentException("no replay set " + set);
        try (InputStream gz = new GZIPInputStream(in)) {
            return read(gz);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Lines of plain JSONL, in file order. */
    public static List<Line> read(InputStream jsonl) {
        List<Line> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(jsonl, StandardCharsets.UTF_8))) {
            String s;
            while ((s = r.readLine()) != null) {
                if (s.isBlank()) continue;
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                out.add(new Line(o.get("game").getAsString(), o.get("league").getAsString(),
                        o.get("t").getAsLong(), o.get("event").toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Starts replay time. Before this, {@link #fetch} answers each game's first snapshot (priming). */
    public void startClock() {
        origin = clock.getAsLong();
    }

    private long elapsed() {
        return origin < 0 ? -1 : (long) ((clock.getAsLong() - origin) * speed);
    }

    @Override
    public List<GameSnapshot> fetch(String league) {
        long e = elapsed();
        List<GameSnapshot> out = new ArrayList<>();
        for (Map.Entry<String, List<Snap>> g : byGame.entrySet()) {
            if (!leagueOf.get(g.getKey()).equalsIgnoreCase(league)) continue;
            if (e < 0) {
                out.add(g.getValue().get(0).snap());
                continue;
            }
            GameSnapshot latest = null;
            for (Snap s : g.getValue()) {
                if (s.t() > e) break;
                latest = s.snap();
            }
            if (latest != null) out.add(latest);
        }
        return out;
    }

    /** The set's length in replay time: its last snapshot's time. At speed {@code s} a run lasts this ÷ s. */
    public long lengthMillis() {
        return lastT;
    }

    /** True once replay time has passed every game's last snapshot. */
    public boolean done() {
        long e = elapsed();
        return e >= 0 && e >= lastT;
    }

    /** The run's game ids, in the set's order. */
    public List<String> gameIds() {
        return List.copyOf(byGame.keySet());
    }

    /** Each game's first snapshot (pre-game), by run game id: the teams and leagues a run can follow. */
    public Map<String, GameSnapshot> firstSnapshots() {
        Map<String, GameSnapshot> out = new LinkedHashMap<>();
        byGame.forEach((id, snaps) -> out.put(id, snaps.get(0).snap()));
        return out;
    }
}
