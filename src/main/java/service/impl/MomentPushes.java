package service.impl;

import alerts.Alert;
import alerts.Detectors;

import java.util.ArrayList;
import java.util.List;

/**
 * Which pushes one play makes, given who is in each alert's audience: the pure part of
 * {@link MomentFanout}. The Phase 4.1 checker uses the same functions to compute what each user
 * should have received, so the two cannot disagree about combining (Phase 3.3 spec, 5.2–5.4).
 */
public final class MomentPushes {

    private MomentPushes() {}

    /**
     * The alert groups of one play, in send order: the moment's alerts as one push, then the final as
     * its own. Sent in that order and keyed by user, the final lands after the moment push in the same
     * lane, so Stage 2 delivers the goal before "Final (OT)".
     */
    public static List<List<Alert>> groups(Detectors.Result r) {
        List<List<Alert>> out = new ArrayList<>();
        if (!r.moment().isEmpty()) out.add(r.moment());
        if (r.finalAlert() != null) out.add(List.of(r.finalAlert()));
        return out;
    }

    /** The alerts of {@code group} a user gets, by whether they are in each alert's audience; empty means no push. */
    public static List<Alert> pick(List<Alert> group, List<Boolean> in) {
        List<Alert> theirs = new ArrayList<>();
        for (int a = 0; a < group.size(); a++) {
            if (in.get(a)) theirs.add(group.get(a));
        }
        return theirs;
    }
}
