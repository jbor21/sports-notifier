package checker;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The report's shape (Phase 4.1 spec, 6.5). */
class ReportTest {

    private static final Report.Header H = new Report.Header("t3k2ab", "evening", 10, 500, 52, 10, 1832, 41207);

    @Test
    void passReportShape() {
        List<Long> lat = new ArrayList<>();
        for (long i = 1; i <= 100; i++) lat.add(i * 10);
        String out = Report.render(H, new Verifier.Result(List.of(), 0, 0, 0, lat), List.of());
        assertEquals("""
            run t3k2ab  set evening  speed 10×  users 500 (52 with two tabs)  games 10
            plays 1,832   expected pushes 41,207
            history   missing 0   unexpected 0   wrong body 0
            sockets   missing 0 (allowed in disconnects: 0, silent: 0)   duplicate 0   out of order 0   unexpected 0
            follows   frozen ✓
            outside the run: 0 messages
            latency detectedAt → socket (checker): p50 500 ms  p99 990 ms  max 1000 ms
            PASS
            """, out);
    }

    @Test
    void latencyLineHasMax() {
        String out = Report.render(H, new Verifier.Result(List.of(), 0, 0, 0, List.of(10L, 20L, 900L)), List.of());
        assertTrue(out.contains("max 900 ms"), out);
    }

    @Test
    void failListsAtMost20ExamplesAndTotal() {
        List<Finding> f = new ArrayList<>();
        for (int i = 0; i < 25; i++) f.add(new Finding("socket_missing", "u" + i, "g", "g:" + i, "lost"));
        String out = Report.render(H, new Verifier.Result(f, 0, 0, 0, List.of()), List.of(
            new Finding("follow_changed", "u1", null, null, "alerts changed")));
        assertTrue(out.contains("sockets   missing 25 (allowed in disconnects: 0, silent: 0)"), out);
        assertTrue(out.contains("follows   changed 1"), out);
        assertTrue(out.contains("socket_missing: 25 (first 20)"), out);
        assertEquals(20, out.lines().filter(l -> l.startsWith("  socket_missing ")).count());
        assertTrue(out.contains("latency detectedAt → socket (checker): p50 -  p99 -  max -"), out);
        assertTrue(out.endsWith("FAIL\n"), out);
    }

    @Test
    void latencyLine() {
        String out = Report.render(H, new Verifier.Result(List.of(), 0, 0, 0, List.of(10L, 20L, 900L)), List.of());
        assertTrue(out.contains("latency detectedAt → socket (checker): p50 "), out);
        assertTrue(out.contains("max 900 ms"), out);
        assertFalse(out.contains("preview"), out);
    }
    @Test
    void socketsLineCountsSilentExcuses() {
        String out = Report.render(H, new Verifier.Result(List.of(), 0, 3, 2, List.of()), List.of());
        assertTrue(out.contains("sockets   missing 0 (allowed in disconnects: 3, silent: 2)"), out);
    }
}
