package alerts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static alerts.Sequence.LABELS;
import static org.junit.jupiter.api.Assertions.*;

class ShootoutDetectorTest {

    private static Sequence seq() {
        return Sequence.of(new ShootoutDetector(LABELS));
    }

    private static final PlayBuilder OT = PlayBuilder.nhl("g").period(4).clock(10).score(2, 2);

    @Test
    void fromPeriodFive() {
        Sequence s = seq().play(OT, OT.next().period(5).clock(0), OT.next().next().period(5).clock(0).detail("SO"));
        assertEquals(List.of("g:SHOOTOUT"), s.ids());
        assertEquals("Shootout: Rangers 2, Bruins 2", s.texts().get(0));
        assertEquals(Alert.P_OVERTIME, s.alerts().get(0).priority());
    }

    @Test
    void fromDetailSO() {
        assertEquals(List.of("g:SHOOTOUT"), seq().play(OT, OT.next().clock(0).detail("SO")).ids());
    }

    @Test
    void preseasonCounts() {
        PlayBuilder pre = OT.next().season(1);
        assertEquals(List.of("g:SHOOTOUT"), seq().play(pre, pre.next().period(5).clock(0)).ids());
    }

    @Test
    void postseasonNever() {
        PlayBuilder post = OT.next().season(3);
        assertEquals(List.of(), seq().play(post, post.next().period(5).clock(12000)).ids());
    }

    @Test
    void otherSportsNever() {
        PlayBuilder nba = PlayBuilder.nba("g").period(4).clock(10);
        assertEquals(List.of(), seq().play(nba, nba.next().period(5).clock(3000).detail("SO")).ids());
    }
}
