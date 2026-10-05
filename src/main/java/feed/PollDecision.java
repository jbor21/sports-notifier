package feed;

import model.GameEvent;
import model.GameSnapshot;

import java.util.Map;
import java.util.Optional;

/**
 * Compares one ESPN snapshot with the game record the Lua script saved, and picks at most one
 * event to send. One event per snapshot, because the event id is built from the snapshot's seq:
 * a second event from the same snapshot would have the same id and be dropped as a duplicate.
 *
 * Alerts go out only for changes we saw happen. A game we have no record of (a fresh cluster,
 * AWS just came up, an expired record) is recorded quietly with GAME_SYNC, so nobody gets
 * "has started" for a game that started an hour ago.
 */
public final class PollDecision {

    public static final String GAME_START = "GAME_START";
    public static final String SCORE_UPDATE = "SCORE_UPDATE";
    public static final String GAME_END = "GAME_END";
    public static final String GAME_SYNC = "GAME_SYNC";

    private static final long PHASE_DIGIT = 10_000_000_000_000L;

    private PollDecision() {}

    /**
     * @param record the {@code game:<id>} hash, empty if there is none
     * @param snap   what ESPN shows now
     * @param now    wall-clock millis, for display only
     */
    public static Optional<GameEvent> decide(Map<String, String> record, GameSnapshot snap, long now) {
        if (record.isEmpty()) return Optional.of(event(GAME_SYNC, snap.homeTeam(), syncText(snap), snap, now));

        String status = record.getOrDefault("status", "");
        int home = intOf(record.get("homeScore"));
        int away = intOf(record.get("awayScore"));
        boolean live = snap.phase() == GameSnapshot.Phase.LIVE;

        // Only the NFL can end tied. Elsewhere a tied final is ESPN mid-correction (seen: an OT
        // winner briefly removed from the final score), so wait for the next poll.
        boolean tiedFinal = "FINAL".equals(snap.status()) && snap.homeScore() == snap.awayScore();
        if (tiedFinal && !"NFL".equals(snap.league())) return Optional.empty();
        if (!"FINAL".equals(status) && "FINAL".equals(snap.status())) {
            return Optional.of(event(GAME_END, snap.homeTeam(),
                "Final: " + scoreLine(snap), snap, now));
        }
        // "Not started" is read from the phase digit of the saved seq, so a game that was
        // delayed or postponed before its start still gets a "has started" when it goes live.
        if (longOf(record.get("lastSeq")) / PHASE_DIGIT == 0 && live) {
            return Optional.of(event(GAME_START, snap.homeTeam(),
                snap.homeTeam() + " vs " + snap.awayTeam() + " has started", snap, now));
        }
        int homeRise = snap.homeScore() - home;
        int awayRise = snap.awayScore() - away;
        if (live && (homeRise > 0 || awayRise > 0)) {
            String scorer = awayRise > homeRise ? snap.awayTeam() : snap.homeTeam();
            return Optional.of(event(SCORE_UPDATE, scorer, scorer + " scores! " + scoreLine(snap), snap, now));
        }
        // Halftime, delays, and corrections (a score going down, or changing after the final)
        // update the record quietly.
        if (!status.equals(snap.status()) || homeRise != 0 || awayRise != 0) {
            return Optional.of(event(GAME_SYNC, snap.homeTeam(), syncText(snap), snap, now));
        }
        return Optional.empty();
    }

    private static GameEvent event(String type, String team, String description, GameSnapshot snap, long now) {
        long seq = Seq.of(snap);
        return new GameEvent(snap.gameId(), snap.gameId() + ":" + seq, type, team, now, description,
            snap.homeTeam(), snap.awayTeam(), snap.homeScore(), snap.awayScore(),
            snap.status(), snap.league(), seq);
    }

    private static String scoreLine(GameSnapshot s) {
        return s.homeTeam() + " " + s.homeScore() + " - " + s.awayScore() + " " + s.awayTeam();
    }

    private static String syncText(GameSnapshot s) {
        return s.homeTeam() + " vs " + s.awayTeam() + ": " + s.status();
    }

    private static int intOf(String s) {
        try { return s == null ? 0 : Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static long longOf(String s) {
        try { return s == null ? 0L : Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }
}
