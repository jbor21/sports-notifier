package alerts;

import java.util.List;

/**
 * Score alerts (spec 3.4): NFL touchdowns with their try, field goals, safeties; MLB runs; NHL goals.
 * NBA has none (decision 15: no alert per basket).
 *
 * A score's id number is the team's count of scoring alerts fired, which a reversal never lowers: a
 * goal overturned (3 → 2) and then a real goal is the score's "3rd" again, but the counter says 2.
 */
public final class ScoreDetector implements Detector {

    private static final List<String> ANY_NFL_SCORE = List.of("TOUCHDOWN", "FIELD_GOAL", "SAFETY");

    private final GameLabels labels;

    public ScoreDetector(GameLabels labels) {
        this.labels = labels;
    }

    @Override
    public void detect(GameMemory before, Play now, GameMemory after, List<Alert> out) {
        // Live plays, and the play that ends a live game. A game seen before kickoff and next at its
        // final (the poller or Stage 1 was down) sends only the final, not "Bears score 24".
        boolean canAlert = now.live() || (now.isFinal() && "LIVE".equals(before.phase));
        if (!canAlert) return;
        int homeRise = now.homeScore() - before.homeScore;
        int awayRise = now.awayScore() - before.awayScore;
        switch (now.league() == null ? "" : now.league()) {
            case "NFL" -> nfl(now, after, homeRise, awayRise, out);
            case "MLB" -> {
                run(now, after, Side.HOME, homeRise, out);
                run(now, after, Side.AWAY, awayRise, out);
            }
            case "NHL" -> {
                goal(now, after, Side.HOME, homeRise, out);
                goal(now, after, Side.AWAY, awayRise, out);
            }
            default -> { }
        }
    }

    private void run(Play now, GameMemory after, Side side, int rise, List<Alert> out) {
        if (rise <= 0) return;
        String t = labels.name(now, side);
        out.add(Alert.of(AlertIds.score(now.gameId(), side, after.nextScore(side)), "RUN_SCORED", Alert.P_SCORE, side,
            (rise == 1 ? t + " score! " : t + " score " + rise + "! ") + labels.scoreLine(now)));
    }

    private void goal(Play now, GameMemory after, Side side, int rise, List<Alert> out) {
        if (rise <= 0) return;
        String t = labels.name(now, side);
        out.add(Alert.of(AlertIds.score(now.gameId(), side, after.nextScore(side)), "GOAL", Alert.P_SCORE, side,
            (rise == 1 ? "Goal " + t + "! " : rise + " goals " + t + "! ") + labels.scoreLine(now)));
    }

    /**
     * The try needs memory: after a touchdown it is pending. The scoring team's +1 or +2 decides it
     * good (a +2 is a conversion, not a safety). It fails when the other team scores, the period or
     * game ends, or the clock has run below the touchdown's time for two plays: one snapshot can carry
     * a wrong clock, and ESPN can post the point a few seconds after the clock restarts.
     */
    private void nfl(Play now, GameMemory after, int homeRise, int awayRise, List<Alert> out) {
        int[] rise = {homeRise, awayRise};
        Side defensive = null;
        GameMemory.TryState t = after.pending;
        if (t != null) {
            Side s = t.side();
            int own = rise[s.ordinal()];
            int theirs = rise[s.other().ordinal()];
            if (own < 0 || (now.isFinal() && own == 0 && theirs == 0)) {
                // The touchdown was overturned, or it ended the game and has no try: drop it, say nothing.
                after.pending = null;
            } else if ((own == 1 || own == 2) && theirs == 0) {
                after.pending = null;
                rise[s.ordinal()] = 0;
                out.add(tryAlert(now, s, AlertIds.tryResult(now.gameId(), s, t.n()),
                    own == 1 ? "Extra point good. " : "2-point conversion good. "));
            } else if (own != 0 || theirs > 0) {
                noGood(now, after, t, out);
                if (theirs > 0) defensive = s.other();
            } else if (now.period() != t.period() || now.betweenPeriods()) {
                noGood(now, after, t, out);
            } else if (now.clockTenths() >= 0 && now.clockTenths() < t.clockTenths()) {
                if (t.playsBelow() + 1 >= 2) noGood(now, after, t, out);
                else after.pending = new GameMemory.TryState(s, t.n(), t.period(), t.clockTenths(), t.playsBelow() + 1);
            } else if (t.playsBelow() != 0) {
                after.pending = new GameMemory.TryState(s, t.n(), t.period(), t.clockTenths(), 0);
            }
        }
        for (Side side : Side.values()) {
            int d = rise[side.ordinal()];
            if (d <= 0) continue;
            String team = labels.name(now, side);
            String line = labels.scoreLine(now);
            if (d == 1 && after.tryNoGood(side)) {
                after.setTryNoGood(side, false);
                out.add(tryAlert(now, side, AlertIds.tryLate(now.gameId(), side, after.lastTd(side)), "Extra point good after all. "));
                continue;
            }
            after.setTryNoGood(side, false);
            int n = after.nextScore(side);
            String id = AlertIds.score(now.gameId(), side, n);
            if (d == 6) {
                after.setLastTd(side, n);
                if (now.isFinal()) {
                    out.add(Alert.of(id, "TOUCHDOWN", Alert.P_SCORE, side, "Touchdown " + team + "! " + line));
                } else {
                    after.pending = new GameMemory.TryState(side, n, now.period(), now.clockTenths(), 0);
                    out.add(Alert.of(id, "TOUCHDOWN", Alert.P_SCORE, side, "Touchdown " + team + "! Extra point pending. " + line));
                }
            } else if (d == 7 || d == 8) {
                after.setLastTd(side, n);
                out.add(Alert.of(id, "TOUCHDOWN", Alert.P_SCORE, side, "Touchdown " + team
                    + (d == 7 ? ", extra point good. " : ", 2-point conversion good. ") + line));
            } else if (d == 3) {
                out.add(Alert.of(id, "FIELD_GOAL", Alert.P_SCORE, side, "Field goal " + team + ". " + line));
            } else if (d == 2 && side != defensive) {
                out.add(Alert.of(id, "SAFETY", Alert.P_SCORE, side, "Safety! " + team + " score 2. " + line));
            } else {
                // A defensive conversion, or two scores between polls: say what happened, reach every scoring switch.
                out.add(new Alert(id, "SCORE", ANY_NFL_SCORE, Alert.P_SCORE, side, team + " score " + d + ". " + line));
            }
        }
    }

    private void noGood(Play now, GameMemory after, GameMemory.TryState t, List<Alert> out) {
        after.pending = null;
        if (now.isFinal()) return;   // the game ended before the try mattered
        after.setTryNoGood(t.side(), true);
        out.add(tryAlert(now, t.side(), AlertIds.tryResult(now.gameId(), t.side(), t.n()), "Try no good. "));
    }

    private Alert tryAlert(Play now, Side side, String id, String text) {
        return Alert.of(id, "TRY_RESULT", Alert.P_TRY, side, text + labels.scoreLine(now));
    }
}
