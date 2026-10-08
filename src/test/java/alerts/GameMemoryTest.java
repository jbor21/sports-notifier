package alerts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameMemoryTest {

    private static GameMemory full() {
        GameMemory m = new GameMemory();
        m.takePosition(PlayBuilder.nfl("g").period(4).clock(2400).score(17, 20).build());
        m.fire("GAME_START");
        m.fire("END_OF_PERIOD:1");
        m.nextScore(Side.HOME);
        m.nextScore(Side.AWAY);
        m.leadChanges = 2;
        m.delays = 1;
        m.resumes = 1;
        m.lastLeader = Side.AWAY;
        m.pending = new GameMemory.TryState(Side.AWAY, 1, 4, 2400, 1);
        m.homeTryNoGood = true;
        m.homeLastTd = 3;
        m.awayLastTd = 1;
        return m;
    }

    @Test
    void jsonRoundTrip() {
        GameMemory m = full();
        GameMemory back = GameMemory.fromJson(m.toJson());
        assertEquals(m.toJson(), back.toJson());
        assertEquals("LIVE", back.phase);
        assertEquals(4, back.period);
        assertEquals(2400, back.clockTenths);
        assertEquals(17, back.homeScore);
        assertEquals(20, back.awayScore);
        assertTrue(back.wasLate);
        assertTrue(back.fired("END_OF_PERIOD:1"));
        assertEquals(1, back.homeScores);
        assertEquals(Side.AWAY, back.lastLeader);
        assertEquals(new GameMemory.TryState(Side.AWAY, 1, 4, 2400, 1), back.pending);
        assertTrue(back.homeTryNoGood);
        assertEquals(3, back.homeLastTd);
    }

    @Test
    void copyIsIndependent() {
        GameMemory m = full();
        String before = m.toJson();
        GameMemory c = m.copy();
        c.fire("FINAL");
        c.nextScore(Side.HOME);
        c.pending = null;
        assertEquals(before, m.toJson());
        assertFalse(m.fired("FINAL"));
    }

    @Test
    void nextScoreCounts() {
        GameMemory m = new GameMemory();
        assertEquals(1, m.nextScore(Side.HOME));
        assertEquals(2, m.nextScore(Side.HOME));
        assertEquals(1, m.nextScore(Side.AWAY));
        assertEquals(3, m.nextScore(Side.HOME));
    }
}
