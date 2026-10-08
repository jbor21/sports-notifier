package alerts;

import com.google.gson.Gson;

import java.util.Set;
import java.util.TreeSet;

/**
 * What the detectors remember about one game between plays (spec 2.2). Stage 1 keeps it in
 * {@code det:<gameId>} as JSON and writes it only after the play's notifications are confirmed.
 *
 * Detection is a pure function of (this memory, the play), so a crash and re-read, or a stalled old
 * worker finishing the same play, works out the same alerts with the same ids.
 */
public final class GameMemory {

    private static final Gson GSON = new Gson();

    /** An NFL touchdown whose try hasn't been decided yet. */
    public record TryState(Side side, int n, int period, int clockTenths, int playsBelow) {}

    // The last play's position.
    String phase;
    String status;
    String stage;
    int period;
    String half;
    int clockTenths;
    int homeScore;
    int awayScore;
    /** Whether the last play met the late-game condition; close game late needs two in a row. */
    boolean wasLate;

    /** Once-per-game alerts already sent, e.g. {@code GAME_START}, {@code END_OF_PERIOD:1}. Sorted, so the JSON is stable. */
    Set<String> fired = new TreeSet<>();

    // Counters that only go up. A score reversal never lowers them, so an id is never reused.
    int homeScores;
    int awayScores;
    int leadChanges;
    int delays;
    int resumes;

    /** The last team that was strictly ahead; null before anyone has led. */
    Side lastLeader;

    // NFL try state.
    TryState pending;
    boolean homeTryNoGood;
    boolean awayTryNoGood;
    /** The counter value of each team's last touchdown, for a late extra point's id. */
    int homeLastTd;
    int awayLastTd;

    /** A drop in one team's score, not yet believed: where it was first seen, and plays since. */
    public record Drop(int period, int clockTenths, int plays) {}

    /**
     * The score each team's alerts are counted from. It follows the score up at once, but down only
     * when a drop has held (an overturn), so an ESPN glitch that comes back sends nothing. Null in a
     * memory that has never seen a score: the last position stands in.
     */
    Integer homeCounted;
    Integer awayCounted;
    Drop homeDrop;
    Drop awayDrop;

    public GameMemory copy() {
        return fromJson(toJson());
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static GameMemory fromJson(String json) {
        GameMemory m = GSON.fromJson(json, GameMemory.class);
        if (m.fired == null) m.fired = new TreeSet<>();
        else m.fired = new TreeSet<>(m.fired);
        return m;
    }

    public boolean fired(String flag) {
        return fired.contains(flag);
    }

    public void fire(String flag) {
        fired.add(flag);
    }

    /** Adds one scoring alert for {@code side} and returns the new count: the number in its id. */
    public int nextScore(Side side) {
        return side == Side.HOME ? ++homeScores : ++awayScores;
    }

    public int score(Side side) {
        return side == Side.HOME ? homeScore : awayScore;
    }

    boolean tryNoGood(Side side) {
        return side == Side.HOME ? homeTryNoGood : awayTryNoGood;
    }

    void setTryNoGood(Side side, boolean v) {
        if (side == Side.HOME) homeTryNoGood = v; else awayTryNoGood = v;
    }

    int lastTd(Side side) {
        return side == Side.HOME ? homeLastTd : awayLastTd;
    }

    void setLastTd(Side side, int n) {
        if (side == Side.HOME) homeLastTd = n; else awayLastTd = n;
    }

    int counted(Side side) {
        Integer c = side == Side.HOME ? homeCounted : awayCounted;
        return c != null ? c : score(side);
    }

    void setCounted(Side side, int score) {
        if (side == Side.HOME) homeCounted = score; else awayCounted = score;
    }

    Drop drop(Side side) {
        return side == Side.HOME ? homeDrop : awayDrop;
    }

    void setDrop(Side side, Drop d) {
        if (side == Side.HOME) homeDrop = d; else awayDrop = d;
    }

    /** Remembers the play's position. The chain calls it last, after every detector has compared. */
    public void takePosition(Play p) {
        phase = p.phase();
        status = p.status();
        stage = p.stage();
        period = p.period();
        half = p.half();
        clockTenths = p.clockTenths();
        homeScore = p.homeScore();
        awayScore = p.awayScore();
        wasLate = SportRules.isLate(p);
    }
}
