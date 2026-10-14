package checker;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private static Verifier.Result verify(List<ExpectedPush> expected, List<HistoryRow> history, List<SocketLog> sockets) {
        return Verifier.verify(RUN, expected, history, sockets, Map.of(), 0);
    }

    private static Verifier.Result verify(List<ExpectedPush> expected, List<HistoryRow> history, List<SocketLog> sockets,
                                          Map<String, Long> deliveredAt, long clockOffsetMillis) {
        return Verifier.verify(RUN, expected, history, sockets, deliveredAt, clockOffsetMillis);
    }

    private static Map<String, Long> delivered(ExpectedPush e, long redisMillis) {
        return Map.of(Verifier.key(e.userId(), e.messageId()), redisMillis);
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
        Verifier.Result r = verify(List.of(a, b), List.of(hist(a, T + 50), hist(b, T + 250)), List.of(s));
        assertEquals(List.of(), r.findings());
        assertEquals(List.of(100L, 300L), r.latenciesMillis());
    }

    @Test
    void historyMissingReported() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        arrive(s, a, T + 100);
        assertEquals(List.of("history_missing"), kinds(verify(List.of(a), List.of(), List.of(s))));
    }

    @Test
    void historyUnexpectedReported() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        assertEquals(List.of("history_unexpected"), kinds(verify(List.of(), List.of(hist(a, T)), List.of())));
    }

    @Test
    void historyWrongBodyReported() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        HistoryRow h = hist(a, T);
        h.body().addProperty("extra", "different");
        assertEquals(List.of("history_wrong_body"), kinds(verify(List.of(a), List.of(h), List.of())));
    }

    @Test
    void bodyDiffIgnoresCreatedAt() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        HistoryRow h = hist(a, T);
        h.body().addProperty("createdAt", "2026-10-09T12:00:00Z");
        assertEquals(List.of(), kinds(verify(List.of(a), List.of(h), List.of())));
    }

    @Test
    void socketDuplicateReported() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        arrive(s, a, T + 100);
        arrive(s, a, T + 200);
        assertEquals(List.of("socket_duplicate"), kinds(verify(List.of(a), List.of(hist(a, T)), List.of(s))));
    }

    @Test
    void socketOutOfOrderReported() {
        ExpectedPush a = exp("u1", G1, G1 + ":A"), b = exp("u1", G1, G1 + ":B");
        SocketLog s = socket("u1", 0);
        arrive(s, b, T + 100);
        arrive(s, a, T + 200);
        Verifier.Result r = verify(List.of(a, b), List.of(hist(a, T), hist(b, T + 10)), List.of(s));
        assertEquals(List.of("socket_out_of_order"), kinds(r));
        assertEquals(G1 + ":A", r.findings().get(0).messageId());
    }

    @Test
    void socketUnexpectedReported() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        arrive(s, a, T + 100);
        assertEquals(List.of("socket_unexpected"), kinds(verify(List.of(), List.of(), List.of(s)))
            .stream().filter(k -> !k.equals("history_unexpected")).toList());
    }

    @Test
    void missingWithGapAtDeliveryAllowed() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        s.disconnected(T + 500);
        s.connected(T + 1500);
        Verifier.Result r = verify(List.of(a), List.of(hist(a, T)), List.of(s), delivered(a, T + 1000), 0);
        assertEquals(List.of(), kinds(r));
        assertEquals(1, r.allowedInGaps());
    }

    @Test
    void gapThreeSecondsAfterDeliveryNotAllowed() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        s.disconnected(T + 4000);
        s.connected(T + 5000);
        Verifier.Result r = verify(List.of(a), List.of(hist(a, T)), List.of(s), delivered(a, T + 1000), 0);
        assertEquals(List.of("socket_missing"), kinds(r));
        assertTrue(r.findings().get(0).detail().contains("socket was up"), r.findings().get(0).detail());
    }

    @Test
    void lastPushWithLateDisconnectNotAllowed() {
        // Issue #48: the user's last push (often the final) has no later arrival to bound the window,
        // so a disconnect ten minutes later used to excuse it.
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        s.disconnected(T + 600_000);                    // dropped much later and never came back
        Verifier.Result r = verify(List.of(a), List.of(hist(a, T)), List.of(s), delivered(a, T + 1000), 0);
        assertEquals(List.of("socket_missing"), kinds(r));
    }

    @Test
    void missingInsideGapWithoutHistoryNotAllowed() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        s.disconnected(T - 10);
        Verifier.Result r = verify(List.of(a), List.of(), List.of(s), delivered(a, T + 1000), 0);
        assertTrue(kinds(r).contains("socket_missing"), kinds(r).toString());
        assertTrue(kinds(r).contains("history_missing"));
    }

    @Test
    void missingWithoutClaimNotAllowed() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        s.disconnected(T - 10);
        Verifier.Result r = verify(List.of(a), List.of(hist(a, T)), List.of(s), Map.of(), 0);
        assertEquals(List.of("socket_missing"), kinds(r));
        assertTrue(r.findings().get(0).detail().contains("no delivery time"), r.findings().get(0).detail());
    }

    @Test
    void clockOffsetShiftsTheWindow() {
        // Redis's clock runs 10 s ahead of the checker's: delivered at T+11 s in Redis time is T+1 s locally.
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        s.disconnected(T + 500);
        s.connected(T + 1500);
        assertEquals(List.of(), kinds(verify(List.of(a), List.of(hist(a, T)), List.of(s), delivered(a, T + 11_000), 10_000)));
        assertEquals(List.of("socket_missing"), kinds(verify(List.of(a), List.of(hist(a, T)), List.of(s), delivered(a, T + 11_000), 0)));
    }

    @Test
    void openGapAtEndExcusesWithoutOverflow() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog s = socket("u1", 0);
        s.disconnected(T + 500);                        // still down when the run ended
        Verifier.Result r = verify(List.of(a), List.of(hist(a, T)), List.of(s), delivered(a, T - 4000), -5000);
        assertEquals(List.of(), kinds(r));
        assertEquals(1, r.allowedInGaps());
    }

    @Test
    void twoTabsEachGetOneCopy() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        SocketLog tab0 = socket("u1", 0), tab1 = socket("u1", 1);
        arrive(tab0, a, T + 100);
        arrive(tab1, a, T + 120);
        assertEquals(List.of(), kinds(verify(List.of(a), List.of(hist(a, T)), List.of(tab0, tab1))));
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
        assertEquals(List.of(), kinds(verify(List.of(a1, b1, a2), h, List.of(s))));
    }

    @Test
    void otherGamesCountedOutsideRun() {
        ExpectedPush live = exp("u1", "nhl-401891803", "nhl-401891803:A");
        ExpectedPush otherRun = exp("u1", "nhl-rpzzz999-1", "nhl-rpzzz999-1:A");
        SocketLog s = socket("u1", 0);
        arrive(s, live, T);
        arrive(s, otherRun, T);
        List<HistoryRow> h = new ArrayList<>(List.of(hist(live, T), hist(otherRun, T)));
        Verifier.Result r = verify(List.of(), h, List.of(s));
        assertEquals(List.of(), r.findings());
        assertEquals(2, r.outsideRun());
    }

    @Test
    void nullAndAbsentFieldsAreEqual() {
        // Stage 1 serializes with Gson's defaults, which leave out null members; the API allows both
        // ("clock": null between periods, or no clock at all). Found by the first cluster run.
        ExpectedPush a = exp("u1", G1, G1 + ":END_OF_PERIOD:1");
        a.body().getAsJsonObject("game").add("clock", com.google.gson.JsonNull.INSTANCE);
        HistoryRow h = hist(a, T);
        h.body().getAsJsonObject("game").remove("clock");
        assertEquals(List.of(), kinds(verify(List.of(a), List.of(h), List.of())));
    }

    @Test
    void aRealValueStillDiffers() {
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        a.body().getAsJsonObject("game").addProperty("clock", "4:12");
        HistoryRow h = hist(a, T);
        h.body().getAsJsonObject("game").remove("clock");
        assertEquals(List.of("history_wrong_body"), kinds(verify(List.of(a), List.of(h), List.of())));
    }

    @Test
    void legacyClaimMessage() {
        // A claim written as '1' before 4.2 reaches the verifier as no time, like no claim at all (Claims).
        ExpectedPush a = exp("u1", G1, G1 + ":A");
        Verifier.Result r = verify(List.of(a), List.of(hist(a, T)), List.of(socket("u1", 0)), Map.of(), 0);
        String detail = r.findings().get(0).detail();
        assertTrue(detail.contains("no delivery time in its claim"), detail);
        assertFalse(detail.contains("never claimed"), detail);
    }
}
