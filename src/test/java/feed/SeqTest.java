package feed;

import model.GameSnapshot;
import model.GameSnapshot.Half;
import model.GameSnapshot.Phase;
import model.GameSnapshot.Stage;
import org.junit.jupiter.api.Test;

import static model.GameSnapshot.Phase.*;
import static model.GameSnapshot.Stage.*;
import static org.junit.jupiter.api.Assertions.*;

class SeqTest {

    private static GameSnapshot nfl(Phase phase, int period, int clockTenths, Stage stage, int home, int away) {
        return new GameSnapshot("espn-1", "NFL", "CHI", "LV", home, away,
            phase, period, Half.TOP, clockTenths, 0, stage, "IN_PROGRESS", 0L);
    }

    private static GameSnapshot mlb(Phase phase, int inning, Half half, int outs, Stage stage, int home, int away) {
        return new GameSnapshot("espn-2", "MLB", "CHC", "STL", home, away,
            phase, inning, half, 0, outs, stage, "IN_PROGRESS", 0L);
    }

    @Test
    void specExampleNflFourthQuarter() {
        // Spec 2.1: phase 1 | period 04 | half 0 | clock 34800 | outs 0 | stage 0 | score 045
        assertEquals(10403480000045L, Seq.of(nfl(LIVE, 4, 1200, IN_PLAY, 24, 21)));
    }

    @Test
    void lessTimeLeftIsBigger() {
        assertTrue(Seq.of(nfl(LIVE, 4, 600, IN_PLAY, 0, 0)) > Seq.of(nfl(LIVE, 4, 1200, IN_PLAY, 0, 0)));
    }

    @Test
    void nextPeriodBeatsAnyClockInPrevious() {
        assertTrue(Seq.of(nfl(LIVE, 3, 9000, IN_PLAY, 0, 0)) > Seq.of(nfl(LIVE, 2, 0, HALFTIME, 99, 99)));
    }

    @Test
    void halftimeBeatsEndOfQ2LastPlay() {
        assertTrue(Seq.of(nfl(LIVE, 2, 0, HALFTIME, 14, 10)) > Seq.of(nfl(LIVE, 2, 0, IN_PLAY, 14, 10)));
    }

    @Test
    void extraPointBreaksTieAtStoppedClock() {
        // Touchdown makes it 6-0; the extra point (7-0) happens with the clock stopped at the same spot.
        assertTrue(Seq.of(nfl(LIVE, 1, 4000, IN_PLAY, 7, 0)) > Seq.of(nfl(LIVE, 1, 4000, IN_PLAY, 6, 0)));
    }

    @Test
    void delayedBeatsInPlayAtSamePosition() {
        assertTrue(Seq.of(nfl(LIVE, 3, 5000, DELAYED, 7, 7)) > Seq.of(nfl(LIVE, 3, 5000, IN_PLAY, 7, 7)));
    }

    @Test
    void finalBeatsEverythingLive() {
        assertTrue(Seq.of(nfl(FINAL, 4, 0, IN_PLAY, 0, 0)) > Seq.of(nfl(LIVE, 9, 0, HALFTIME, 99, 99)));
    }

    @Test
    void mlbHalfAndOuts() {
        long top4TwoOut = Seq.of(mlb(LIVE, 4, Half.TOP, 2, IN_PLAY, 1, 1));
        long mid4 = Seq.of(mlb(LIVE, 4, Half.MIDDLE, 3, IN_PLAY, 1, 1));
        long bot4NoOut = Seq.of(mlb(LIVE, 4, Half.BOTTOM, 0, IN_PLAY, 1, 1));
        long bot4ThreeOut = Seq.of(mlb(LIVE, 4, Half.BOTTOM, 3, IN_PLAY, 1, 1));
        long end4 = Seq.of(mlb(LIVE, 4, Half.END, 3, IN_PLAY, 1, 1));
        assertTrue(top4TwoOut < mid4);
        assertTrue(mid4 < bot4NoOut);
        assertTrue(bot4NoOut < bot4ThreeOut);
        assertTrue(bot4ThreeOut < end4);
        // MLB has no clock: the clock block is all zeros.
        assertEquals(0, (bot4NoOut / 100_000L) % 100_000L);
    }

    @Test
    void mlbClockFieldIsIgnored() {
        GameSnapshot withClock = new GameSnapshot("espn-2", "MLB", "CHC", "STL", 1, 1,
            LIVE, 4, Half.TOP, 1234, 1, IN_PLAY, "IN_PROGRESS", 0L);
        assertEquals(Seq.of(mlb(LIVE, 4, Half.TOP, 1, IN_PLAY, 1, 1)), Seq.of(withClock));
    }

    @Test
    void suspendedThenResumedKeepsRising() {
        long suspended = Seq.of(mlb(LIVE, 5, Half.TOP, 1, DELAYED, 2, 2));
        long resumedNextOut = Seq.of(mlb(LIVE, 5, Half.TOP, 2, IN_PLAY, 2, 2));
        assertTrue(resumedNextOut > suspended);
    }

    @Test
    void clockOutOfRangeIsClamped() {
        assertEquals(Seq.of(nfl(LIVE, 1, 0, IN_PLAY, 0, 0)), Seq.of(nfl(LIVE, 1, -5, IN_PLAY, 0, 0)));
        assertEquals(Seq.of(nfl(LIVE, 1, 36000, IN_PLAY, 0, 0)), Seq.of(nfl(LIVE, 1, 50000, IN_PLAY, 0, 0)));
    }

    @Test
    void scoreTotalCappedAt999() {
        assertEquals(999, Seq.of(nfl(LIVE, 1, 0, IN_PLAY, 600, 600)) % 1000);
    }

    @Test
    void neverZero() {
        // Event Processing rejects seq <= 0, so a zero here would lose the game's record and its start alert.
        GameSnapshot mlbPreNoInning = new GameSnapshot("espn-2", "MLB", "CHC", "STL", 0, 0,
            PRE, 0, Half.TOP, 0, 0, IN_PLAY, "SCHEDULED", 0L);
        assertTrue(Seq.of(mlbPreNoInning) > 0);
    }

    @Test
    void fitsInLuaNumber() {
        long max = Seq.of(new GameSnapshot("espn-3", "NBA", "A", "B", 999, 999,
            FINAL, 99, Half.END, 0, 3, HALFTIME, "FINAL", 0L));
        assertTrue(max < (1L << 53));
        assertTrue(max < 100_000_000_000_000L);
    }
}
