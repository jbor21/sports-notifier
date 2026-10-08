package alerts;

import java.util.List;
import java.util.Set;

/**
 * Delayed, postponed, suspended, canceled; and in MLB, play resuming (spec 3.3). All under the
 * DELAYED switch. A change from one delay status to another is news, so it alerts again.
 */
public final class DelayDetector implements Detector {

    private static final Set<String> DELAYS = Set.of("DELAYED", "POSTPONED", "SUSPENDED", "CANCELED");

    private final GameLabels labels;

    public DelayDetector(GameLabels labels) {
        this.labels = labels;
    }

    @Override
    public void detect(GameMemory before, Play now, GameMemory after, List<Alert> out) {
        String status = now.status();
        if (DELAYS.contains(status) && !status.equals(before.status)) {
            int n = ++after.delays;
            String position = GameLabels.position(now);
            out.add(Alert.of(AlertIds.delayed(now.gameId(), n), "DELAYED", Alert.P_FLOW, null,
                word(status) + ": " + labels.matchup(now) + (position == null ? "" : " · " + position)));
            return;
        }
        // Only MLB alerts on a resume: rain stops baseball for an hour; elsewhere a delay is short.
        if ("MLB".equals(now.league()) && "LIVE".equals(before.phase) && now.live() && "IN_PROGRESS".equals(status)
                && ("DELAYED".equals(before.status) || "SUSPENDED".equals(before.status))) {
            int n = ++after.resumes;
            out.add(new Alert(AlertIds.resumed(now.gameId(), n), "RESUMED", List.of("DELAYED"), Alert.P_FLOW, null,
                "Play resumes: " + labels.scoreLine(now)));
        }
    }

    private static String word(String status) {
        return switch (status) {
            case "POSTPONED" -> "Postponed";
            case "SUSPENDED" -> "Suspended";
            case "CANCELED" -> "Canceled";
            default -> "Delayed";
        };
    }
}
