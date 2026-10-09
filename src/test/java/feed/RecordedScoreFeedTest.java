package feed;

import model.GameSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Recorded per-game timelines played back on a virtual clock (Phase 4.1 spec, 2.2). */
class RecordedScoreFeedTest {

    private static final String RUN = "abc123";
    private static final long RUN_START = 1_800_000_000_000L;
    private static final String NFL = "nfl-rpabc123-401872971";
    private static final String NHL = "nhl-rpabc123-401891803";

    private List<RecordedScoreFeed.Line> lines;
    private final AtomicLong clock = new AtomicLong(RUN_START);

    @BeforeEach
    void load() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/replay/tiny.jsonl")) {
            lines = RecordedScoreFeed.read(in);
        }
    }

    private RecordedScoreFeed feed(double speed) {
        return new RecordedScoreFeed(lines, RUN, RUN_START, clock::get, speed);
    }

    /** The NFL game's lines, in order. */
    private List<RecordedScoreFeed.Line> nflLines() {
        return lines.stream().filter(l -> l.game().equals("nfl-401872971")).toList();
    }

    private static GameSnapshot only(List<GameSnapshot> snaps) {
        assertEquals(1, snaps.size());
        return snaps.get(0);
    }

    @Test
    void readsEveryLine() {
        assertEquals(30, lines.size());
        assertEquals("NFL", nflLines().get(0).league());
    }

    @Test
    void primeReturnsFirstSnapshotPerGame() {
        RecordedScoreFeed f = feed(10);
        clock.addAndGet(3_600_000);                       // time passes, but the clock has not started
        GameSnapshot nfl = only(f.fetch("NFL"));
        assertEquals(NFL, nfl.gameId());
        assertEquals("SCHEDULED", nfl.status());
        assertEquals(NHL, only(f.fetch("NHL")).gameId(), "an offset game is primed too");
        assertTrue(f.fetch("MLB").isEmpty());
    }

    @Test
    void picksLatestAtOrBeforeVirtualTime() {
        RecordedScoreFeed f = feed(1);
        f.startClock();
        RecordedScoreFeed.Line fifth = nflLines().get(4);
        clock.addAndGet(fifth.t() - 1);
        GameSnapshot before = only(f.fetch("NFL"));
        clock.addAndGet(1);
        GameSnapshot at = only(f.fetch("NFL"));
        assertNotEquals(key(parse(nflLines().get(3))), key(parse(fifth)), "the fixture must tell the two apart");
        assertEquals(key(parse(nflLines().get(3))), key(before));
        assertEquals(key(parse(fifth)), key(at));
    }

    @Test
    void speedScalesTime() {
        RecordedScoreFeed f = feed(10);
        f.startClock();
        clock.addAndGet(3_000);                            // 3 s of wall time at 10× = 30 s of replay
        assertEquals(key(parse(nflLines().get(1))), key(only(f.fetch("NFL"))));
    }

    @Test
    void gameNotStartedIsOmitted() {
        RecordedScoreFeed f = feed(1);
        f.startClock();
        clock.addAndGet(60_000);
        assertTrue(f.fetch("NHL").isEmpty(), "the NHL game starts 20 minutes in");
        clock.addAndGet(20 * 60_000);
        assertEquals(NHL, only(f.fetch("NHL")).gameId());
    }

    @Test
    void idsRewritten() {
        assertEquals(NFL, RecordedScoreFeed.gameId("NFL", RUN, "401872971"));
        assertEquals(List.of(NFL, NHL), feed(1).gameIds());
    }

    @Test
    void startTimesShifted() {
        // Each game's scheduled start moves to when its replay begins, so it shows as today in /games.
        RecordedScoreFeed f = feed(10);
        assertEquals(RUN_START, f.firstSnapshots().get(NFL).startTimeMillis());
        assertEquals(RUN_START + 120_000, f.firstSnapshots().get(NHL).startTimeMillis());
    }

    @Test
    void doneAfterLastLine() {
        RecordedScoreFeed f = feed(1);
        assertFalse(f.done());
        f.startClock();
        long last = lines.stream().mapToLong(RecordedScoreFeed.Line::t).max().orElseThrow();
        clock.addAndGet(last - 1);
        assertFalse(f.done());
        clock.addAndGet(1);
        assertTrue(f.done());
        assertEquals("FINAL", only(f.fetch("NHL")).status());
    }

    @Test
    void gameIdsListsEveryGame() {
        assertEquals(2, feed(1).gameIds().size());
        assertEquals(2, feed(1).firstSnapshots().size());
    }

    /** Everything but the id and start time, which the feed rewrites. */
    private static String key(GameSnapshot s) {
        return s.status() + " " + s.period() + " " + s.clockTenths() + " " + s.homeScore() + "-" + s.awayScore();
    }

    private static GameSnapshot parse(RecordedScoreFeed.Line l) {
        return EspnScoreFeed.parse("{\"events\":[" + l.eventJson() + "]}", l.league()).get(0);
    }
}
