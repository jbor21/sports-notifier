package alerts;

import java.util.List;

/**
 * Score alerts (spec 3.4): NFL touchdowns with their try, field goals, safeties; MLB runs; NHL goals.
 * NBA has none (decision 15: no alert per basket).
 *
 * A score's id number is the team's count of scoring alerts fired, which a reversal never lowers: a
 * goal overturned (3 → 2) and then a real goal is the score's "3rd" again, but the counter says 3.
 *
 * A score that goes down is an overturn only once it holds (user, 2026-10-08). In the recordings a real
 * overturn drops at the stopped clock of the review and stays down as play goes on; an ESPN glitch drops
 * while the clock runs and is back within seconds (up to 27 s seen). So a drop waits for 60 s of game clock
 * (MLB: two more plays), a new period, or the final, then alerts under the same scoring switch with the next counter
 * value; one that comes back first sends nothing, and the goal is not announced twice.
 */
public final class ScoreDetector implements Detector {

    private static final List<String> ANY_NFL_SCORE = List.of("TOUCHDOWN", "FIELD_GOAL", "SAFETY");
    /** How long a drop must hold, in tenths of a second of game clock. */
    private static final int DROP_HOLD_TENTHS = 600;

    private final GameLabels labels;

    public ScoreDetector(GameLabels labels) {
        this.labels = labels;
    }

    @Override
    public void detect(GameMemory before, Play now, GameMemory after, List<Alert> out) {
        // Live plays, and the play that ends a live game. A game seen before kickoff and next at its
        // final (the poller or Stage 1 was down) sends only the final, not "Bears score 24".
        boolean canAlert = now.live() || (now.isFinal() && "LIVE".equals(before.phase));
        if (!canAlert) {
            for (Side side : Side.values()) {
                after.setCounted(side, now.score(side));
                after.setDrop(side, null);
            }
            return;
        }
        int[] rise = new int[2];
        boolean[] overturned = new boolean[2];
        for (Side side : Side.values()) {
            int counted = after.counted(side);
            int score = now.score(side);
            if (score >= counted) {
                rise[side.ordinal()] = score - counted;
                after.setCounted(side, score);
                after.setDrop(side, null);
                continue;
            }
            GameMemory.Drop d = after.drop(side);
            d = d == null ? new GameMemory.Drop(now.period(), now.clockTenths(), 0)
                          : new GameMemory.Drop(d.period(), d.clockTenths(), d.plays() + 1);
            if (!held(d, now)) {
                after.setDrop(side, d);
                continue;
            }
            after.setDrop(side, null);
            after.setCounted(side, score);
            overturned[side.ordinal()] = true;
            Alert o = overturn(now, after, side, counted - score);
            if (o != null) out.add(o);
        }
        switch (now.league() == null ? "" : now.league()) {
            case "NFL" -> nfl(now, after, rise, overturned, out);
            case "MLB" -> {
                run(now, after, Side.HOME, rise[0], out);
                run(now, after, Side.AWAY, rise[1], out);
            }
            case "NHL" -> {
                goal(now, after, Side.HOME, rise[0], out);
                goal(now, after, Side.AWAY, rise[1], out);
            }
            default -> { }
        }
    }

    /** A drop is believed after 60 s of game clock (MLB: two more plays), a new period, or the final. */
    private static boolean held(GameMemory.Drop d, Play now) {
        if (now.isFinal() || now.period() != d.period()) return true;
        if ("MLB".equals(now.league())) return d.plays() >= 2;
        return d.clockTenths() >= 0 && now.clockTenths() >= 0 && d.clockTenths() - now.clockTenths() >= DROP_HOLD_TENTHS;
    }

    /** "Rangers goal overturned", with the corrected score, under the scoring switch it undoes. NBA: none. */
    private Alert overturn(Play now, GameMemory after, Side side, int drop) {
        String team = labels.name(now, side);
        String line = labels.scoreLine(now);
        String type;
        List<String> switches;
        String text;
        switch (now.league() == null ? "" : now.league()) {
            case "NHL" -> { type = "GOAL_OVERTURNED"; switches = List.of("GOAL");
                            text = team + (drop == 1 ? " goal overturned. " : " goals overturned. "); }
            case "MLB" -> { type = "RUN_OVERTURNED"; switches = List.of("RUN_SCORED");
                            text = team + (drop == 1 ? " run overturned. " : " runs overturned. "); }
            case "NFL" -> {
                if (drop >= 6 && drop <= 8) { type = "TOUCHDOWN_OVERTURNED"; switches = List.of("TOUCHDOWN"); text = team + " touchdown overturned. "; }
                else if (drop == 3) { type = "FIELD_GOAL_OVERTURNED"; switches = List.of("FIELD_GOAL"); text = team + " field goal overturned. "; }
                else { type = "SCORE_OVERTURNED"; switches = ANY_NFL_SCORE; text = team + " score corrected. "; }
            }
            default -> { return null; }
        }
        return new Alert(AlertIds.score(now.gameId(), side, after.nextScore(side)), type, switches, Alert.P_SCORE, side, text + line);
    }

    @Override
    public void baseline(Play now, GameMemory after) {
        after.setCounted(Side.HOME, now.homeScore());
        after.setCounted(Side.AWAY, now.awayScore());
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
    private void nfl(Play now, GameMemory after, int[] rise, boolean[] overturned, List<Alert> out) {
        Side defensive = null;
        GameMemory.TryState t = after.pending;
        if (t != null && after.drop(t.side()) != null) {
            t = null;   // the scoring team's score is down, unconfirmed: decide nothing about the try yet
        }
        if (t != null) {
            Side s = t.side();
            int own = rise[s.ordinal()];
            int theirs = rise[s.other().ordinal()];
            if (overturned[s.ordinal()] || (now.isFinal() && own == 0 && theirs == 0)) {
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
