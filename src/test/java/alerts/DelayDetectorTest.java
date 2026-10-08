package alerts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static alerts.Sequence.LABELS;
import static org.junit.jupiter.api.Assertions.*;

class DelayDetectorTest {

    private static Sequence seq() {
        return Sequence.of(new DelayDetector(LABELS));
    }

    private static final PlayBuilder LIVE = PlayBuilder.mlb("g").period(3).half("TOP").score(1, 0);

    @Test
    void delayedThenPostponedFiresTwice() {
        PlayBuilder delayed = LIVE.next().status("DELAYED").stage("DELAYED");
        PlayBuilder postponed = delayed.next().status("POSTPONED").phase("PRE");
        Sequence s = seq().play(LIVE, delayed, postponed);
        assertEquals(List.of("g:DELAYED:1", "g:DELAYED:2"), s.ids());
        assertEquals(List.of("Delayed: Cardinals at Cubs · Top 3rd", "Postponed: Cardinals at Cubs"), s.texts());
        assertEquals(List.of("DELAYED"), s.alerts().get(0).switches());
        assertEquals(Alert.P_FLOW, s.alerts().get(0).priority());
    }

    @Test
    void sameDelayNextPollNoSecond() {
        PlayBuilder delayed = LIVE.next().status("DELAYED").stage("DELAYED");
        assertEquals(List.of("g:DELAYED:1"), seq().play(LIVE, delayed, delayed.next()).ids());
    }

    @Test
    void mlbResumeFires() {
        PlayBuilder delayed = LIVE.next().status("DELAYED").stage("DELAYED");
        Sequence s = seq().play(LIVE, delayed, delayed.next().live());
        assertEquals(List.of("g:DELAYED:1", "g:RESUMED:1"), s.ids());
        Alert resumed = s.alerts().get(1);
        assertEquals("RESUMED", resumed.type());
        assertEquals(List.of("DELAYED"), resumed.switches());
        assertEquals("Play resumes: Cubs 1, Cardinals 0 · Top 3rd", resumed.text());
    }

    @Test
    void suspendedThenResumedFires() {
        PlayBuilder suspended = LIVE.next().status("SUSPENDED").stage("DELAYED");
        assertEquals(List.of("g:DELAYED:1", "g:RESUMED:1"), seq().play(LIVE, suspended, suspended.next().live()).ids());
    }

    @Test
    void otherSportsDoNotAlertOnResume() {
        PlayBuilder live = PlayBuilder.nfl("g").period(2).clock(3000);
        PlayBuilder delayed = live.next().status("DELAYED").stage("DELAYED");
        assertEquals(List.of("g:DELAYED:1"), seq().play(live, delayed, delayed.next().live()).ids());
    }

    @Test
    void pregameDelayThenStartIsNotResume() {
        PlayBuilder pre = PlayBuilder.mlb("g").pre();
        PlayBuilder delayed = pre.next().status("DELAYED").stage("DELAYED");
        Sequence s = seq().play(pre, delayed, delayed.next().live().period(1));
        assertEquals(List.of("g:DELAYED:1"), s.ids());
        assertEquals("Delayed: Cardinals at Cubs", s.texts().get(0));
    }

    @Test
    void canceledIsADelay() {
        PlayBuilder pre = PlayBuilder.nhl("g").pre();
        Sequence s = seq().play(pre, pre.next().phase("FINAL").status("CANCELED"));
        assertEquals(List.of("g:DELAYED:1"), s.ids());
        assertEquals("Canceled: Rangers at Bruins", s.texts().get(0));
    }
}
