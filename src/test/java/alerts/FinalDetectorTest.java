package alerts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static alerts.Sequence.LABELS;
import static org.junit.jupiter.api.Assertions.*;

class FinalDetectorTest {

    private static Sequence seq() {
        return Sequence.of(new FinalDetector(LABELS));
    }

    private static String finalText(PlayBuilder last, PlayBuilder fin) {
        Sequence s = seq().play(last, fin);
        assertEquals(List.of(), s.ids(), "the final is never in the moment");
        assertEquals(1, s.finals().size());
        return s.finals().get(0).text();
    }

    @Test
    void winText() {
        PlayBuilder q4 = PlayBuilder.nfl("g").period(4).clock(10).score(20, 17);
        Sequence s = seq().play(q4, q4.next().fin().clock(0));
        Alert f = s.finals().get(0);
        assertEquals("g:FINAL", f.id());
        assertEquals("FINAL", f.type());
        assertEquals(List.of("FINAL"), f.switches());
        assertEquals("Final: Bears 20, Raiders 17. Bears win.", f.text());
    }

    @Test
    void nflTie() {
        PlayBuilder ot = PlayBuilder.nfl("g").period(5).clock(10).score(20, 20);
        assertEquals("Final (OT): Raiders 20, Bears 20. Tie.", finalText(ot, ot.next().fin().clock(0)));
    }

    @Test
    void overtimeSuffix() {
        PlayBuilder ot = PlayBuilder.nba("g").period(5).clock(10).score(110, 108);
        assertEquals("Final (OT): Celtics 110, Lakers 108. Celtics win.", finalText(ot, ot.next().fin().clock(0)));
    }

    @Test
    void shootoutSuffix() {
        PlayBuilder so = PlayBuilder.nhl("g").period(5).clock(0).score(2, 2);
        assertEquals("Final (SO): Bruins 3, Rangers 2. Bruins win.", finalText(so, so.next().fin().score(3, 2)));
    }

    @Test
    void mlbExtraInnings() {
        PlayBuilder tenth = PlayBuilder.mlb("g").period(10).half("BOTTOM").score(4, 4);
        assertEquals("Final (10 innings): Cubs 5, Cardinals 4. Cubs win.", finalText(tenth, tenth.next().fin().score(5, 4)));
    }

    @Test
    void canceledIsNotFinal() {
        PlayBuilder pre = PlayBuilder.nhl("g").pre();
        assertEquals(List.of(), seq().play(pre, pre.next().phase("FINAL").status("CANCELED")).finals());
    }

    @Test
    void finalFiresOnce() {
        PlayBuilder q4 = PlayBuilder.nfl("g").period(4).clock(10).score(20, 17);
        PlayBuilder fin = q4.next().fin().clock(0);
        assertEquals(1, seq().play(q4, fin, fin.next().score(20, 18)).finals().size());
    }

    @Test
    void baselineFinalSendsNothing() {
        PlayBuilder fin = PlayBuilder.nfl("g").fin().period(4).score(20, 17);
        assertEquals(List.of(), seq().play(fin, fin.next()).finals());
    }
}
