package alerts;

import java.util.List;

/**
 * The team in front changes (spec 3.5), with or without a tie in between. Taking the first lead is not
 * a change. NBA alerts only in Q4 and overtime, but tracks the leader all game. Only a team that scored
 * takes the lead: a correction that hands it over sends nothing, like any score going down.
 */
public final class LeadChangeDetector implements Detector {

    private final GameLabels labels;

    public LeadChangeDetector(GameLabels labels) {
        this.labels = labels;
    }

    @Override
    public void detect(GameMemory before, Play now, GameMemory after, List<Alert> out) {
        Side leader = leader(now);
        if (leader == null) return;
        boolean canAlert = now.live() || (now.isFinal() && "LIVE".equals(before.phase));
        boolean changed = before.lastLeader != null && leader != before.lastLeader;
        boolean scored = now.score(leader) > before.score(leader);
        boolean inWindow = !"NBA".equals(now.league()) || now.period() >= 4;
        if (canAlert && changed && scored && inWindow) {
            int n = ++after.leadChanges;
            out.add(Alert.of(AlertIds.leadChange(now.gameId(), n), "LEAD_CHANGE", Alert.P_LEAD, leader,
                labels.name(now, leader) + " take the lead. " + labels.scoreLine(now)));
        }
        after.lastLeader = leader;
    }

    @Override
    public void baseline(Play now, GameMemory after) {
        Side leader = leader(now);
        if (leader != null) after.lastLeader = leader;
    }

    private static Side leader(Play p) {
        if (p.homeScore() == p.awayScore()) return null;
        return p.homeScore() > p.awayScore() ? Side.HOME : Side.AWAY;
    }
}
