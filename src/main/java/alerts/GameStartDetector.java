package alerts;

import java.util.List;

/** The game goes from before to live (spec 3.1). A game delayed before its start still gets it. */
public final class GameStartDetector implements Detector {

    static final String FLAG = "GAME_START";

    private final GameLabels labels;

    public GameStartDetector(GameLabels labels) {
        this.labels = labels;
    }

    @Override
    public void detect(GameMemory before, Play now, GameMemory after, List<Alert> out) {
        if (!"PRE".equals(before.phase) || !now.live() || after.fired(FLAG)) return;
        after.fire(FLAG);
        out.add(Alert.of(AlertIds.once(now.gameId(), FLAG), FLAG, Alert.P_FLOW, null,
            word(now.league()) + ": " + labels.matchup(now) + "."));
    }

    @Override
    public void baseline(Play now, GameMemory after) {
        if (!"PRE".equals(now.phase())) after.fire(FLAG);
    }

    private static String word(String league) {
        return switch (league == null ? "" : league) {
            case "NBA" -> "Tip-off";
            case "MLB" -> "First pitch";
            case "NHL" -> "Puck drop";
            default -> "Kickoff";
        };
    }
}
