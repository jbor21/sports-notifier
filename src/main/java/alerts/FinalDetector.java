package alerts;

import java.util.List;

/**
 * The final (spec 3.2): always its own push, never in the moment. It names the winner, not "your
 * team": the body is built once per moment, not per user.
 */
public final class FinalDetector implements Detector {

    static final String FLAG = "FINAL";

    private final GameLabels labels;

    public FinalDetector(GameLabels labels) {
        this.labels = labels;
    }

    @Override
    public void detect(GameMemory before, Play now, GameMemory after, List<Alert> out) {}

    @Override
    public Alert finalAlert(GameMemory before, Play now, GameMemory after) {
        if (!now.isFinal() || after.fired(FLAG)) return null;
        after.fire(FLAG);
        String result = now.homeScore() == now.awayScore() ? "Tie."
            : labels.name(now, now.homeScore() > now.awayScore() ? Side.HOME : Side.AWAY) + " win.";
        return Alert.of(AlertIds.once(now.gameId(), FLAG), FLAG, Alert.P_FLOW, null,
            "Final" + suffix(now, after) + ": " + labels.score(now) + ". " + result);
    }

    @Override
    public void baseline(Play now, GameMemory after) {
        if (now.isFinal()) after.fire(FLAG);
    }

    private static String suffix(Play now, GameMemory after) {
        if (after.fired(ShootoutDetector.FLAG) || SportRules.isShootout(now)) return " (SO)";
        if (now.period() <= SportRules.regulation(now.league())) return "";
        return "MLB".equals(now.league()) ? " (" + now.period() + " innings)" : " (OT)";
    }
}
