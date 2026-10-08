package simulator;

import model.GameSnapshot;
import model.GameSnapshot.Half;
import model.GameSnapshot.Phase;
import model.GameSnapshot.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One simulated game: a fixed list of positions, played back one per step by
 * {@link SimulatedScoreFeed}. Fixed so that tests and the smoke test can expect exact alerts.
 *
 * Every step moves the game forward (a later seq than the step before), so the real poller sends a
 * play for each one, as it does for ESPN while a clock runs. The first step is before the game:
 * the poller records a game it has never seen quietly, and only a game it saw before the start
 * gets a "has started".
 */
public final class GameScript {

    /** One position: what a scoreboard shows at one moment. */
    public record Step(Phase phase, String status, Stage stage, int period, Half half, int clockTenths, int outs,
                       int homeScore, int awayScore, String detail) {}

    private final String league;
    private final String home;
    private final String away;
    private final int slot;
    private final List<Step> steps;

    private GameScript(String league, String home, String away, int slot, List<Step> steps) {
        this.league = league;
        this.home = home;
        this.away = away;
        this.slot = slot;
        this.steps = List.copyOf(steps);
    }

    public String league() { return league; }
    public String home() { return home; }
    public String away() { return away; }
    /** Its number in the run, the last part of the game id; 0 means its place in the wave. */
    public int slot() { return slot; }
    public List<Step> steps() { return steps; }

    /** The same game with a slot fixed for the whole run, so two waves never share a game id. */
    public GameScript inSlot(int slot) {
        return new GameScript(league, home, away, slot, steps);
    }

    /** Step {@code i} as the feed reports it. */
    public GameSnapshot snapshot(String gameId, int i, long startTimeMillis) {
        Step s = steps.get(i);
        return new GameSnapshot(gameId, league, home, away, s.homeScore(), s.awayScore(), s.phase(), s.period(),
            s.half(), s.clockTenths(), s.outs(), s.stage(), s.status(), startTimeMillis, 2, s.detail());
    }

    /**
     * Quarters of 15:00. A touchdown with the extra point one step later at the same stopped clock,
     * an away field goal, a touchdown with a 2-point try in one step, halftime, the end of Q3, an
     * away lead change in Q4, then a close finish: under 5:00 within 8 points on two steps in a row.
     */
    public static GameScript nfl(String home, String away) {
        Builder b = new Builder("NFL").pre();
        b.at(1, 9000).at(1, 7600).at(1, 6050)
            .score(6, 0).at(1, 4320)          // touchdown, try pending
            .score(1, 0).at(1, 4320)          // extra point, same stopped clock
            .at(1, 3300).at(1, 1820)
            .score(0, 3).at(1, 800)           // away field goal
            .at(1, 150).endPeriod(1);
        b.at(2, 9000).at(2, 8200).at(2, 7450).at(2, 5900)
            .score(8, 0).at(2, 4940)          // touchdown and 2-point conversion in one step
            .at(2, 3600).at(2, 2210)
            .score(0, 7).at(2, 1180)          // touchdown and extra point in one step
            .at(2, 350).halftime();
        b.at(3, 9000).at(3, 7300).at(3, 5730)
            .score(0, 3).at(3, 4080)
            .at(3, 2600).at(3, 1150).at(3, 300).endPeriod(3);
        b.at(4, 9000)
            .score(3, 0).at(4, 7670)
            .at(4, 6200).at(4, 4850)
            .score(0, 6).at(4, 3700)          // away touchdown takes the lead
            .score(0, 1).at(4, 3700)
            .at(4, 2700).at(4, 1650)          // late and close, two steps in a row
            .score(3, 0).at(4, 650)           // home field goal takes it back
            .at(4, 240).fin(4);
        return b.build(home, away);
    }

    /**
     * Nine innings, each top, middle, bottom, and end. An away run in the 2nd, two home runs in the
     * 5th, an away run in the 7th, the winning home run in the 8th. The home team leads after the
     * top of the 9th, so the bottom is not played.
     */
    public static GameScript mlb(String home, String away) {
        Builder b = new Builder("MLB").pre();
        for (int inning = 1; inning <= 9; inning++) {
            b.half(inning, Half.TOP, 0);
            if (inning == 2 || inning == 7) b.score(0, 1);
            b.half(inning, Half.TOP, 2);
            if (inning == 9) break;
            b.half(inning, Half.MIDDLE, 0);
            if (inning == 5) b.score(2, 0);
            if (inning == 8) b.score(1, 0);
            b.half(inning, Half.BOTTOM, 1);
            b.half(inning, Half.END, 0);
        }
        return b.fin(9).build(home, away);
    }

    /**
     * Three periods of 20:00. A home goal in the 1st, an away goal in the 2nd, the winning home goal
     * early in the 3rd, then a 1-goal game under 5:00 to the end.
     */
    public static GameScript nhl(String home, String away) {
        Builder b = new Builder("NHL").pre();
        for (int period = 1; period <= 3; period++) {
            for (int clock = 12000; clock >= 1000; clock -= 1000) {
                if (period == 1 && clock == 8000) b.score(1, 0);
                if (period == 2 && clock == 6000) b.score(0, 1);
                if (period == 3 && clock == 9000) b.score(1, 0);
                b.at(period, clock);
            }
            if (period < 3) b.endPeriod(period);
        }
        return b.fin(3).build(home, away);
    }

    /** Four quarters of 12:00, halftime, a final. NBA sends no score alerts, so the score just climbs. */
    public static GameScript nba(String home, String away) {
        Builder b = new Builder("NBA").pre();
        int tick = 0;
        for (int quarter = 1; quarter <= 4; quarter++) {
            for (int clock = 7200; clock > 0; clock -= 720) {
                tick++;
                b.score(2 + tick % 3, 2 + (tick * 2) % 3);
                if (quarter == 4 && clock == 720) b.score(3, 0);   // the winning three; the rest would end tied
                b.at(quarter, clock);
            }
            if (quarter == 2) b.halftime();
            else if (quarter < 4) b.endPeriod(quarter);
        }
        return b.fin(4).build(home, away);
    }

    /** Appends positions; {@link #score} changes the score the next position carries. */
    private static final class Builder {
        private final String league;
        private final List<Step> steps = new ArrayList<>();
        private int home, away;

        Builder(String league) {
            this.league = league;
        }

        Builder pre() {
            steps.add(new Step(Phase.PRE, "SCHEDULED", Stage.IN_PLAY, 0, Half.TOP, 0, 0, 0, 0, "Scheduled"));
            return this;
        }

        Builder score(int homePoints, int awayPoints) {
            home += homePoints;
            away += awayPoints;
            return this;
        }

        /** In play, {@code clockTenths} left in the period. */
        Builder at(int period, int clockTenths) {
            steps.add(new Step(Phase.LIVE, "IN_PROGRESS", Stage.IN_PLAY, period, Half.TOP, clockTenths, 0,
                home, away, clock(clockTenths) + " - " + ordinal(period)));
            return this;
        }

        Builder endPeriod(int period) {
            steps.add(new Step(Phase.LIVE, "END_PERIOD", Stage.END_PERIOD, period, Half.TOP, 0, 0,
                home, away, "End of " + ordinal(period)));
            return this;
        }

        Builder halftime() {
            steps.add(new Step(Phase.LIVE, "HALFTIME", Stage.HALFTIME, 2, Half.TOP, 0, 0, home, away, "Halftime"));
            return this;
        }

        /** MLB: the half-inning and its outs; ESPN keeps the status in progress between halves. */
        Builder half(int inning, Half half, int outs) {
            String word = switch (half) {
                case TOP -> "Top";
                case MIDDLE -> "Mid";
                case BOTTOM -> "Bot";
                case END -> "End";
            };
            steps.add(new Step(Phase.LIVE, "IN_PROGRESS", Stage.IN_PLAY, inning, half, 0, outs,
                home, away, word + " " + ordinal(inning)));
            return this;
        }

        Builder fin(int period) {
            steps.add(new Step(Phase.FINAL, "FINAL", Stage.IN_PLAY, period, Half.TOP, 0, 0, home, away, "Final"));
            return this;
        }

        GameScript build(String homeTeam, String awayTeam) {
            return new GameScript(league, homeTeam, awayTeam, 0, steps);
        }

        private static String clock(int tenths) {
            int seconds = tenths / 10;
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }

        private static String ordinal(int n) {
            String suffix = (n % 100 >= 11 && n % 100 <= 13) ? "th"
                : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
            return n + suffix;
        }
    }
}
