package checker;

import service.impl.Follow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The freeze rule (Phase 4.1 spec, 3.2): the expected pushes are computed from the follows, so a follow
 * that changed during the run makes the answer ambiguous and could hide a real loss. The only change
 * allowed is the expiry job marking an ended game's follow to expire after the run.
 */
public final class FollowFreeze {

    private FollowFreeze() {}

    public static List<Finding> compare(List<FollowSnapshot> start, List<FollowSnapshot> end, Instant t0, Instant runEnd) {
        Map<String, FollowSnapshot> before = byId(start);
        Map<String, FollowSnapshot> after = byId(end);
        List<Finding> out = new ArrayList<>();
        for (FollowSnapshot s : start) {
            FollowSnapshot e = after.get(s.follow().id());
            String why = e == null ? "deleted during the run" : difference(s, e, t0, runEnd);
            if (why != null) out.add(finding(s, why));
        }
        for (FollowSnapshot e : end) {
            if (!before.containsKey(e.follow().id())) out.add(finding(e, "added during the run"));
        }
        return out;
    }

    private static String difference(FollowSnapshot s, FollowSnapshot e, Instant t0, Instant runEnd) {
        Follow a = s.follow(), b = e.follow();
        if (!(a.userId().equals(b.userId()) && a.kind() == b.kind() && a.targetId().equals(b.targetId())
                && a.league().equals(b.league()) && a.alerts().equals(b.alerts()) && a.createdAt().equals(b.createdAt()))) {
            return "changed during the run";
        }
        if (a.createdAt().isAfter(t0) || e.updatedAt().isAfter(t0) || s.updatedAt().isAfter(t0)) {
            return "switches changed after T0";
        }
        if (!Objects.equals(a.expiresAt(), b.expiresAt())) {
            if (a.expiresAt() != null || b.expiresAt().isBefore(runEnd)) return "expiry changed within the run";
        }
        return null;
    }

    private static Map<String, FollowSnapshot> byId(List<FollowSnapshot> snaps) {
        Map<String, FollowSnapshot> m = new LinkedHashMap<>();
        for (FollowSnapshot s : snaps) m.put(s.follow().id(), s);
        return m;
    }

    private static Finding finding(FollowSnapshot s, String why) {
        Follow f = s.follow();
        return new Finding("follow_changed", f.userId(), null, null,
                f.kind().name().toLowerCase() + " " + f.targetId() + " (" + f.id() + "): " + why);
    }
}
