package checker;

import alerts.Alert;
import alerts.Detectors;
import alerts.GameLabels;
import alerts.GameMemory;
import alerts.Play;
import com.google.gson.JsonObject;
import model.GameEvent;
import model.NotificationBody;
import service.impl.Follow;
import service.impl.MomentPushes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What every user should have received, recomputed from the two sources of truth: the accepted plays
 * on {@code game-events} and the follows in Postgres (Phase 4.1 spec, 6.1). It replays Stage 1 with no
 * failures: one game at a time from empty memory, the same version dedup, the same detectors and the
 * same combining step ({@link MomentPushes}), but its own audience ({@link Audience}).
 */
public final class Oracle {

    private final Detectors detectors;
    private final GameLabels labels;

    public Oracle(Detectors detectors, GameLabels labels) {
        this.detectors = detectors;
        this.labels = labels;
    }

    /** Ordered by game, then play, then push (moment before final), then user. */
    public List<ExpectedPush> expected(Map<String, List<GameEvent>> playsByGame, Collection<Follow> follows) {
        List<ExpectedPush> out = new ArrayList<>();
        for (Map.Entry<String, List<GameEvent>> game : new TreeMap<>(playsByGame).entrySet()) {
            GameMemory memory = null;
            long last = 0;
            for (GameEvent e : game.getValue()) {
                // A play re-sent by the outbox carries a version already handled; Stage 1 skips it too.
                if (e.getVersion() <= last) continue;
                last = e.getVersion();
                Play play = Play.of(e);
                Detectors.Result result = detectors.run(memory, play);
                memory = result.after();
                for (List<Alert> group : MomentPushes.groups(result)) {
                    List<Set<String>> audiences = new ArrayList<>();
                    Set<String> users = new TreeSet<>();
                    for (Alert a : group) {
                        Set<String> s = Audience.of(play, a, follows);
                        audiences.add(s);
                        users.addAll(s);
                    }
                    for (String user : users) {
                        List<Boolean> in = audiences.stream().map(s -> s.contains(user)).toList();
                        List<Alert> theirs = MomentPushes.pick(group, in);
                        if (theirs.isEmpty()) continue;
                        JsonObject body = NotificationBody.of(play, theirs, labels);
                        out.add(new ExpectedPush(user, game.getKey(), body.get("id").getAsString(), body, e.getVersion()));
                    }
                }
            }
        }
        return out;
    }
}
