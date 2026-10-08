package alerts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static alerts.Sequence.LABELS;
import static org.junit.jupiter.api.Assertions.*;

class OvertimeDetectorTest {

    private static Sequence seq() {
        return Sequence.of(new OvertimeDetector(LABELS));
    }

    @Test
    void doubleOvertimeFiresTwice() {
        PlayBuilder q4 = PlayBuilder.nba("g").period(4).clock(10).score(100, 100);
        Sequence s = seq().play(q4, q4.next().period(5).clock(3000), q4.next().next().period(6).clock(3000));
        assertEquals(List.of("g:OVERTIME:5", "g:OVERTIME:6"), s.ids());
        assertEquals(List.of("Overtime: Lakers 100, Celtics 100", "Double overtime: Lakers 100, Celtics 100"), s.texts());
        assertEquals(Alert.P_OVERTIME, s.alerts().get(0).priority());
        assertEquals(List.of("OVERTIME"), s.alerts().get(0).switches());
    }

    @Test
    void laterOvertimesAreNumbered() {
        PlayBuilder ot = PlayBuilder.nba("g").period(6).clock(10);
        Sequence s = seq().play(ot, ot.next().period(7).clock(3000), ot.next().next().period(8).clock(3000));
        assertEquals(List.of("Triple overtime: Lakers 0, Celtics 0", "4OT: Lakers 0, Celtics 0"), s.texts());
    }

    @Test
    void mlbExtraInningsOnce() {
        PlayBuilder ninth = PlayBuilder.mlb("g").period(9).half("END").score(2, 2);
        Sequence s = seq().play(ninth, ninth.next().period(10).half("TOP"), ninth.next().next().period(11).half("TOP"));
        assertEquals(List.of("g:OVERTIME:10"), s.ids());
        assertEquals("Extra innings: Cardinals 2, Cubs 2", s.texts().get(0));
    }

    @Test
    void nhlRegularSeasonPeriodFiveIsShootoutNotOvertime() {
        PlayBuilder p3 = PlayBuilder.nhl("g").period(3).clock(5).score(2, 2);
        assertEquals(List.of("g:OVERTIME:4"),
            seq().play(p3, p3.next().period(4).clock(3000), p3.next().next().period(5).clock(0)).ids());
    }

    @Test
    void nhlPostseasonPeriodFiveIsOvertime() {
        PlayBuilder p3 = PlayBuilder.nhl("g").period(3).clock(5).score(2, 2).season(3);
        Sequence s = seq().play(p3, p3.next().period(4).clock(12000), p3.next().next().period(5).clock(12000));
        assertEquals(List.of("g:OVERTIME:4", "g:OVERTIME:5"), s.ids());
        assertEquals("Double overtime: Rangers 2, Bruins 2", s.texts().get(1));
    }

    @Test
    void baselineInOvertimeMarksIt() {
        PlayBuilder ot = PlayBuilder.nfl("g").period(5).clock(5000);
        assertEquals(List.of(), seq().play(ot, ot.next().clock(4000)).ids());
    }
}
