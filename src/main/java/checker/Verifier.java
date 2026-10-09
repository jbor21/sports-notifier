package checker;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares what the oracle expected with what history holds and what every socket saw (Phase 4.1
 * spec, 6.2–6.3). Only the run's games count; anything else a socket hears is "outside the run".
 */
public final class Verifier {

    /**
     * @param outsideRun     socket messages about other games (live ESPN, other runs)
     * @param allowedInGaps  pushes missing from a socket while it was down, and present in history
     * @param latenciesMillis socket arrival − the play's {@code detectedAt}, one per first arrival
     */
    public record Result(List<Finding> findings, int outsideRun, int allowedInGaps, List<Long> latenciesMillis) {}

    private Verifier() {}

    private static String key(String user, String messageId) {
        return user + " " + messageId;
    }

    public static Result verify(String runId, List<ExpectedPush> expected, List<HistoryRow> history, List<SocketLog> sockets) {
        String marker = RunId.gameMarker(runId);
        List<Finding> findings = new ArrayList<>();
        int outside = 0;

        Map<String, ExpectedPush> want = new LinkedHashMap<>();
        Map<String, List<ExpectedPush>> wantByUserGame = new HashMap<>();
        Map<String, List<ExpectedPush>> wantByUser = new HashMap<>();
        for (ExpectedPush e : expected) {
            want.put(key(e.userId(), e.messageId()), e);
            wantByUserGame.computeIfAbsent(e.userId() + " " + e.gameId(), k -> new ArrayList<>()).add(e);
            wantByUser.computeIfAbsent(e.userId(), k -> new ArrayList<>()).add(e);
        }

        // History: exactly the expected (user, push) pairs, with the expected bodies.
        Map<String, HistoryRow> have = new HashMap<>();
        for (HistoryRow h : history) {
            if (h.gameId() == null || !h.gameId().contains(marker)) continue;
            have.put(key(h.userId(), h.messageId()), h);
            ExpectedPush e = want.get(key(h.userId(), h.messageId()));
            if (e == null) {
                findings.add(new Finding("history_unexpected", h.userId(), h.gameId(), h.messageId(), "in history, not expected"));
            } else if (!withoutCreatedAt(h.body()).equals(withoutCreatedAt(e.body()))) {
                findings.add(new Finding("history_wrong_body", h.userId(), h.gameId(), h.messageId(),
                        "history " + h.body() + " expected " + e.body()));
            }
        }
        for (ExpectedPush e : expected) {
            if (!have.containsKey(key(e.userId(), e.messageId()))) {
                findings.add(new Finding("history_missing", e.userId(), e.gameId(), e.messageId(), "not in history"));
            }
        }

        int allowed = 0;
        List<Long> latencies = new ArrayList<>();
        for (SocketLog s : sockets) {
            String user = s.userId();
            Map<String, SocketLog.Arrival> first = new LinkedHashMap<>();
            for (SocketLog.Arrival a : s.arrivals()) {
                if (a.gameId() == null || !a.gameId().contains(marker)) { outside++; continue; }
                if (first.containsKey(a.messageId())) {
                    findings.add(new Finding("socket_duplicate", user, a.gameId(), a.messageId(), "tab " + s.tab() + " got it again"));
                    continue;
                }
                first.put(a.messageId(), a);
                ExpectedPush e = want.get(key(user, a.messageId()));
                if (e == null) {
                    findings.add(new Finding("socket_unexpected", user, a.gameId(), a.messageId(), "tab " + s.tab()));
                    continue;
                }
                latencies.add(a.atMillis() - Instant.parse(e.body().get("detectedAt").getAsString()).toEpochMilli());
            }
            findings.addAll(outOfOrder(s, first.values(), wantByUserGame));

            // Missing: allowed only while this tab was down. One user's pushes are delivered in order, so
            // if a push published later than the missing one arrived and the tab never dropped in
            // between, the missing one reached an open socket and was lost.
            for (ExpectedPush e : wantByUser.getOrDefault(user, List.of())) {
                if (first.containsKey(e.messageId())) continue;
                HistoryRow h = have.get(key(user, e.messageId()));
                if (h != null && wasDown(s, h.createdAt().toEpochMilli(), nextArrival(first.values(), have, user, h.createdAt()))) {
                    allowed++;
                } else {
                    findings.add(new Finding("socket_missing", user, e.gameId(), e.messageId(),
                            "tab " + s.tab() + (h == null ? ", not in history either" : ", socket was up")));
                }
            }
        }
        return new Result(findings, outside, allowed, latencies);
    }

    /** Within each game, the pushes that arrived must keep the expected order. */
    private static List<Finding> outOfOrder(SocketLog s, Iterable<SocketLog.Arrival> arrivals,
                                            Map<String, List<ExpectedPush>> wantByUserGame) {
        List<Finding> out = new ArrayList<>();
        Map<String, Integer> lastIndex = new HashMap<>();
        for (SocketLog.Arrival a : arrivals) {
            List<ExpectedPush> order = wantByUserGame.get(s.userId() + " " + a.gameId());
            if (order == null) continue;
            int i = indexOf(order, a.messageId());
            if (i < 0) continue;
            Integer prev = lastIndex.get(a.gameId());
            if (prev != null && i < prev) {
                out.add(new Finding("socket_out_of_order", s.userId(), a.gameId(), a.messageId(),
                        "tab " + s.tab() + ": arrived after " + order.get(prev).messageId()));
            } else {
                lastIndex.put(a.gameId(), i);
            }
        }
        return out;
    }

    private static int indexOf(List<ExpectedPush> order, String messageId) {
        for (int i = 0; i < order.size(); i++) if (order.get(i).messageId().equals(messageId)) return i;
        return -1;
    }

    /** When this tab got its first push published after {@code after}; never, as Long.MAX_VALUE. */
    private static long nextArrival(Iterable<SocketLog.Arrival> arrivals, Map<String, HistoryRow> have, String user, Instant after) {
        long next = Long.MAX_VALUE;
        for (SocketLog.Arrival a : arrivals) {
            HistoryRow h = have.get(key(user, a.messageId()));
            if (h != null && h.createdAt().isAfter(after)) next = Math.min(next, a.atMillis());
        }
        return next;
    }

    private static boolean wasDown(SocketLog s, long from, long to) {
        for (SocketLog.Gap g : s.gaps()) {
            if (g.fromMillis() <= to && g.toMillis() >= from) return true;
        }
        return false;
    }

    private static JsonObject withoutCreatedAt(JsonObject body) {
        JsonObject copy = body.deepCopy();
        copy.remove("createdAt");
        return copy;
    }
}
