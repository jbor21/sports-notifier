package checker;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The checker's rules (Phase 4.1 spec, 6.2–6.3), from hand-built logs. */
class VerifierTest {

    private static final String RUN = "abc123";
    private static final String G1 = "nhl-rpabc123-1";
    private static final String G2 = "nhl-rpabc123-2";
    private static final long T = 1_800_000_000_000L;

    private static JsonObject body(String id, String gameId) {
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        JsonObject game = new JsonObject();
        game.addProperty("id", gameId);
        b.add("game", game);
        b.addProperty("detectedAt", Instant.ofEpochMilli(T).toString());
        return b;
    }

    private static ExpectedPush exp(String user, String game, String id) {
        return new ExpectedPush(user, game, id, body(id, game), 1);
    }

    private static HistoryRow hist(ExpectedPush e, long createdAt) {
        return new HistoryRow(e.userId(), e.messageId(), e.gameId(), Instant.ofEpochMilli(createdAt), e.body().deepCopy());
    }

    private static SocketLog socket(String user, int tab) {
        return new SocketLog(user, tab);
    }

    private static void arrive(SocketLog log, ExpectedPush e, long at) {
        log.arrived(new SocketLog.Arrival(e.messageId(), e.gameId(), e.body(), at));
    }

    private static List<String> kinds(Verifier.Result r) {
        return r.findings().stream().map(Finding::kind).toList();
    }

    @Test
    void allDeliveredPasses() {
        ExpectedPush a = exp("u1", G1, G1 + ":A"), b = exp("u1", G1, G1 + ":B");
        SocketLog s = socket("u1", 0);
        arrive(s, a, T + 100);
        arrive(s, b, T + 300);
        Verifier.Result r = Verifier.verify(RUN, List.of(a, b), List.of(hist(a, T + 50), hist(b, T + 250)), List.of(s));
        assertEquals(List.of(), r.findings());
        assertEquals(List.of(100L, 300L), r.latenciesMillis());
    }

    @Test
    void historyMissingReported() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        arrive(s, a, T + 100);
        assertEquals(List.of("history_missing"), kinds(Verifier.verify(RUN, List.of(a), List.of(), List.of(s))));
    }

    @Test
    void historyUnexpectedReported() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        assertEquals(List.of("history_unexpected"), kinds(Verifier.verify(RUN, List.of(), List.of(hist(a, T)), List.of())));
    }

    @Test
    void historyWrongBodyReported() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        HistoryRow h = hist(a, T);
        h.body().addProperty("extra", "different");
        assertEquals(List.of("history_wrong_body"), kinds(Verifier.verify(RUN, List.of(a), List.of(h), List.of())));
    }

    @Test
    void bodyDiffIgnoresCreatedAt() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        HistoryRow h = hist(a, T);
        h.body().addProperty("createdAt", "2026-10-09T12:00:00Z");
        assertEquals(List.of(), kinds(Verifier.verify(RUN, List.of(a), List.of(h), List.of())));
    }

    @Test
    void socketDuplicateReported() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        arrive(s, a, T + 100);
        arrive(s, a, T + 200);
        assertEquals(List.of("socket_duplicate"), kinds(Verifier.verify(RUN, List.of(a), List.of(hist(a, T)), List.of(s))));
    }

    @Test
    void socketOutOfOrderReported() {
        ExpectedPush a = exp("u1", G1, G1 + ":A"), b = exp("u1", G1, G1 + ":B");
        SocketLog s = socket("u1", 0);
        arrive(s, b, T + 100);
        arrive(s, a, T + 200);
        Verifier.Result r = Verifier.verify(RUN, List.of(a, b), List.of(hist(a, T), hist(b, T + 10)), List.of(s));
        assertEquals(List.of("socket_out_of_order"), kinds(r));
        assertEquals(G1 + ":A", r.findings().get(0).messageId());
    }

    @Test
    void socketUnexpectedReported() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        arrive(s, a, T + 100);
        assertEquals(List.of("socket_unexpected"), kinds(Verifier.verify(RUN, List.of(), List.of(), List.of(s)))
            .stream().filter(k -> !k.equals("history_unexpected")).toList());
    }

    @Test
    void missingOutsideGapNotAllowed() {
        // A arrived in history at T, B arrived on the socket later, and the socket never dropped: A was lost.
        ExpectedPush a = exp("u1", G1, G1 + ":A"), b = exp("u1", G1, G1 + ":B");
        SocketLog s = socket("u1", 0);
        arrive(s, b, T + 500);
        Verifier.Result r = Verifier.verify(RUN, List.of(a, b), List.of(hist(a, T), hist(b, T + 400)), List.of(s));
        assertEquals(List.of("socket_missing"), kinds(r));
        assertEquals(G1 + ":A", r.findings().get(0).messageId());
    }

    @Test
    void missingInsideGapWithHistoryAllowed() {
        ExpectedPush a = exp("u1", G1, G1 + ":A"), b = exp("u1", G1, G1 + ":B");
        SocketLog s = socket("u1", 0);
        s.disconnected(T - 10);
        s.connected(T + 200);
        arrive(s, b, T + 500);
        Verifier.Result r = Verifier.verify(RUN, List.of(a, b), List.of(hist(a, T), hist(b, T + 400)), List.of(s));
        assertEquals(List.of(), kinds(r));
        assertEquals(1, r.allowedInGaps());
    }

    @Test
    void missingInsideGapWithoutHistoryNotAllowed() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        s.disconnected(T - 10);
        Verifier.Result r = Verifier.verify(RUN, List.of(a), List.of(), List.of(s));
        assertTrue(kinds(r).contains("socket_missing"), kinds(r).toString());
        assertTrue(kinds(r).contains("history_missing"));
    }

    @Test
    void missingAfterLastArrivalWithOpenGapAllowed() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        s.disconnected(T + 1000);                       // dropped later and never came back
        Verifier.Result r = Verifier.verify(RUN, List.of(a), List.of(hist(a, T)), List.of(s));
        assertEquals(List.of(), kinds(r));
    }

    @Test
    void twoTabsEachGetOneCopy() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog tab0 = socket("u1", 0), tab1 = socket("u1", 1);
        arrive(tab0, a, T + 100);
        arrive(tab1, a, T + 120);
        assertEquals(List.of(), kinds(Verifier.verify(RUN, List.of(a), List.of(hist(a, T)), List.of(tab0, tab1))));
    }

    @Test
    void orderOnlyWithinAGame() {
        ExpectedPush a1 = exp("u1", G1, G1 + ":A"), b1 = exp("u1", G1, G1 + ":B");
        ExpectedPush a2 = exp("u1", G2, G2 + ":A");
        SocketLog s = socket("u1", 0);
        arrive(s, a2, T + 50);                         // the other game first: fine
        arrive(s, a1, T + 100);
        arrive(s, b1, T + 200);
        List<HistoryRow> h = List.of(hist(a2, T), hist(a1, T + 10), hist(b1, T + 20));
        assertEquals(List.of(), kinds(Verifier.verify(RUN, List.of(a1, b1, a2), h, List.of(s))));
    }

    @Test
    void otherGamesCountedOutsideRun() {
        ExpectedPush live = exp("u1", "nhl-401891803", "nhl-401891803:A");
        ExpectedPush otherRun = exp("u1", "nhl-rpzzz999-1", "nhl-rpzzz999-1:A");
        SocketLog s = socket("u1", 0);
        arrive(s, live, T);
        arrive(s, otherRun, T);
        List<HistoryRow> h = new ArrayList<>(List.of(hist(live, T), hist(otherRun, T)));
        Verifier.Result r = Verifier.verify(RUN, List.of(), h, List.of(s));
        assertEquals(List.of(), r.findings());
        assertEquals(2, r.outsideRun());
    }
}
