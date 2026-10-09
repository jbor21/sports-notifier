package checker;

import alerts.Alert;
import alerts.Play;
import catalog.FollowKind;
import feed.TeamIds;
import service.impl.Follow;
import service.impl.FollowSets;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who should hear about an alert, computed from follows in plain Java (Phase 4.1 spec, 6.1).
 *
 * Stage 1 builds the same audience from the Redis {@code sub:*} sets ({@code AlertAudience}). The
 * checker deliberately does not: the sets are a cache of Postgres, and a set that lost a write or was
 * built in an old shape (#16, #18) would make Stage 1 and a Redis-reading checker agree on the wrong
 * answer. Computing it from the follows themselves turns that disagreement into a missing push.
 *
 * <pre>
 *   game:s  ∪  ((teamH:s ∪ teamA:s) − game:ANY)  ∪  (league:s − game:ANY − teamH:ANY − teamA:ANY)
 * </pre>
 *
 * A follow "has" switch s when s is one of {@link FollowSets#memberships}, the mapping the save path
 * uses too (it is how innings options become {@code END_OF_INNING:<n>}).
 */
public final class Audience {

    private Audience() {}

    public static Set<String> of(Play play, Alert alert, Collection<Follow> follows) {
        Set<String> teams = new HashSet<>();
        for (String abbr : new String[]{play.homeTeam(), play.awayTeam()}) {
            String id = TeamIds.of(play.league(), abbr);
            if (id != null) teams.add(id);
        }
        Instant at = Instant.ofEpochMilli(play.detectedAtMillis());
        Set<String> gameS = new HashSet<>(), gameAny = new HashSet<>();
        Set<String> teamS = new HashSet<>(), teamAny = new HashSet<>();
        Set<String> leagueS = new HashSet<>();
        for (Follow f : follows) {
            if (f.expiresAt() != null && !f.expiresAt().isAfter(at)) continue;
            Set<String> names = FollowSets.memberships(f);
            boolean has = alert.switches().stream().anyMatch(names::contains);
            if (f.kind() == FollowKind.GAME && f.targetId().equals(play.gameId())) {
                gameAny.add(f.userId());
                if (has) gameS.add(f.userId());
            } else if (f.kind() == FollowKind.TEAM && teams.contains(f.targetId())) {
                teamAny.add(f.userId());
                if (has) teamS.add(f.userId());
            } else if (f.kind() == FollowKind.LEAGUE && f.targetId().equalsIgnoreCase(play.league()) && has) {
                leagueS.add(f.userId());
            }
        }
        Set<String> out = new TreeSet<>(gameS);
        teamS.removeAll(gameAny);
        out.addAll(teamS);
        leagueS.removeAll(gameAny);
        leagueS.removeAll(teamAny);
        out.addAll(leagueS);
        return out;
    }
}
