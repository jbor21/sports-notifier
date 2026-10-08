package alerts;

import java.util.List;

/** An NHL shootout starts (spec 3.9). Once per game. */
public final class ShootoutDetector implements Detector {

    static final String FLAG = "SHOOTOUT";

    private final GameLabels labels;

    public ShootoutDetector(GameLabels labels) {
        this.labels = labels;
    }

    @Override
    public void detect(GameMemory before, Play now, GameMemory after, List<Alert> out) {
        if (!now.live() || !SportRules.isShootout(now) || after.fired(FLAG)) return;
        after.fire(FLAG);
        out.add(Alert.of(AlertIds.once(now.gameId(), FLAG), FLAG, Alert.P_OVERTIME, null, "Shootout: " + labels.score(now)));
    }

    @Override
    public void baseline(Play now, GameMemory after) {
        if (SportRules.isShootout(now)) after.fire(FLAG);
    }
}
