package checker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The checker's printed report (Phase 4.1 spec, 6.5). */
public final class Report {

    private static final int EXAMPLES = 20;

    public record Header(String runId, String set, double speed, int users, int twoTabUsers, int games,
                         int plays, int expectedPushes) {}

    private Report() {}

    /** @param other findings outside the verifier: the freeze rule, a timeout, a failed setup */
    public static String render(Header h, Verifier.Result r, List<Finding> other) {
        List<Finding> all = new ArrayList<>(r.findings());
        all.addAll(other);
        Map<String, List<Finding>> byKind = new LinkedHashMap<>();
        for (Finding f : all) byKind.computeIfAbsent(f.kind(), k -> new ArrayList<>()).add(f);

        StringBuilder out = new StringBuilder();
        out.append(String.format("run %s  set %s  speed %s×  users %d (%d with two tabs)  games %d%n",
                h.runId(), h.set(), speed(h.speed()), h.users(), h.twoTabUsers(), h.games()));
        out.append(String.format("plays %,d   expected pushes %,d%n", h.plays(), h.expectedPushes()));
        out.append(String.format("history   missing %d   unexpected %d   wrong body %d%n",
                n(byKind, "history_missing"), n(byKind, "history_unexpected"), n(byKind, "history_wrong_body")));
        out.append(String.format("sockets   missing %d (allowed in disconnects: %d, silent: %d)   duplicate %d   out of order %d   unexpected %d%n",
                n(byKind, "socket_missing"), r.allowedInGaps(), r.allowedInSilentGaps(), n(byKind, "socket_duplicate"),
                n(byKind, "socket_out_of_order"), n(byKind, "socket_unexpected")));
        int changed = n(byKind, "follow_changed");
        out.append(changed == 0 ? "follows   frozen ✓\n" : "follows   changed " + changed + "\n");
        out.append(String.format("outside the run: %d messages%n", r.outsideRun()));
        out.append(String.format("latency detectedAt → socket (checker): p50 %s  p99 %s  max %s%n",
                percentile(r.latenciesMillis(), 50), percentile(r.latenciesMillis(), 99), percentile(r.latenciesMillis(), 100)));
        for (Map.Entry<String, List<Finding>> e : byKind.entrySet()) {
            List<Finding> fs = e.getValue();
            out.append(e.getKey()).append(": ").append(fs.size())
               .append(fs.size() > EXAMPLES ? " (first " + EXAMPLES + ")" : "").append('\n');
            for (Finding f : fs.subList(0, Math.min(EXAMPLES, fs.size()))) {
                out.append("  ").append(f.kind()).append(' ').append(f.userId()).append(' ')
                   .append(f.gameId() == null ? "-" : f.gameId()).append(' ')
                   .append(f.messageId() == null ? "-" : f.messageId()).append(' ').append(f.detail()).append('\n');
            }
        }
        out.append(all.isEmpty() ? "PASS\n" : "FAIL\n");
        return out.toString();
    }

    private static int n(Map<String, List<Finding>> byKind, String kind) {
        return byKind.getOrDefault(kind, List.of()).size();
    }

    /** 60, or 2.5: how the report and the run-started line print a speed. */
    public static String speed(double s) {
        return s == Math.rint(s) ? String.valueOf((long) s) : String.valueOf(s);
    }

    /** Nearest rank; "-" when there is nothing to rank. */
    static String percentile(List<Long> values, int p) {
        if (values.isEmpty()) return "-";
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1)) + " ms";
    }
}
