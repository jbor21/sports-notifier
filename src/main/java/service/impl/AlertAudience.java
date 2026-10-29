package service.impl;

import alerts.Alert;
import alerts.Play;
import catalog.FollowKind;
import feed.GameIds;
import feed.TeamIds;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import util.RedisKeys;
import util.RedisPipelines;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who hears about each alert of a play, with the most specific follow winning (Phase 3.3 spec, 5.1):
 *
 * <pre>
 *   game:s  ∪  ((teamH:s ∪ teamA:s) − game:ANY)  ∪  (league:s − game:ANY − teamH:ANY − teamA:ANY)
 * </pre>
 *
 * where X:s is the union of the alert's switch sets at X. A user who follows the game is decided by
 * that follow alone, even when it has the alert off; a team follower is decided by their team follows
 * (both teams' switches combined); a league follow counts only for users who follow neither.
 *
 * Built inside Redis with set operations, in one pipeline: a handful of commands per alert, not one
 * round trip per subscriber. A replayed game's teams are real, so its audience is cut down to the
 * checker's users ({@code synthetic:users}, #47); with that set missing, a replay reaches nobody.
 * Every key name is unique per call: a stalled worker can overlap the
 * lane's new owner on the same play, and a shared name would let one's cleanup delete the other's set.
 */
public final class AlertAudience {

    private static final long TEMP_KEY_TTL_SECONDS = 60L;

    /**
     * @param keys  one audience set per alert, in the alerts' order
     * @param temps the intermediate sets; {@link #drop} deletes both, and all expire on their own
     */
    public record Built(List<String> keys, List<String> temps) {}

    public Built build(Jedis jedis, Play play, List<Alert> alerts) {
        String base = RedisKeys.FANOUT_PREFIX + play.gameId() + ":v" + play.version() + ":" + UUID.randomUUID() + ":";
        String game = FollowSets.prefix(FollowKind.GAME) + play.gameId();
        String league = FollowSets.prefix(FollowKind.LEAGUE) + play.league();
        Set<String> teams = new LinkedHashSet<>();
        for (String abbr : new String[]{play.homeTeam(), play.awayTeam()}) {
            String id = TeamIds.of(play.league(), abbr);
            if (id != null) teams.add(FollowSets.prefix(FollowKind.TEAM) + id);
        }
        String gameAny = any(game);
        boolean replay = GameIds.isReplay(play.gameId());
        List<String> teamAnys = teams.stream().map(AlertAudience::any).toList();

        List<String> keys = new ArrayList<>();
        List<String> temps = new ArrayList<>();
        Pipeline p = jedis.pipelined();
        for (int i = 0; i < alerts.size(); i++) {
            List<String> switches = alerts.get(i).switches();
            String key = base + i;
            String g = key + ":g", t = key + ":t", l = key + ":l";

            p.sunionstore(g, sets(List.of(game), switches));
            if (teams.isEmpty()) {
                p.del(t);
            } else {
                p.sunionstore(t, sets(teams, switches));
                p.sdiffstore(t, t, gameAny);
            }
            p.sunionstore(l, sets(List.of(league), switches));
            List<String> notLeague = new ArrayList<>(List.of(l, gameAny));
            notLeague.addAll(teamAnys);
            p.sdiffstore(l, notLeague.toArray(new String[0]));
            p.sunionstore(key, g, t, l);
            if (replay) p.sinterstore(key, key, RedisKeys.SYNTHETIC_USERS);
            for (String k : List.of(key, g, t, l)) p.expire(k, TEMP_KEY_TTL_SECONDS);

            keys.add(key);
            temps.addAll(List.of(g, t, l));
        }
        RedisPipelines.syncChecked(p);
        return new Built(List.copyOf(keys), List.copyOf(temps));
    }

    public void drop(Jedis jedis, Built b) {
        List<String> all = new ArrayList<>(b.keys());
        all.addAll(b.temps());
        if (!all.isEmpty()) jedis.del(all.toArray(new String[0]));
    }

    private static String any(String target) {
        return target + ":" + RedisKeys.SUB_ANY;
    }

    /** Every target's set for every switch: {@code sub:team:nfl-chi:TOUCHDOWN}, … */
    private static String[] sets(Iterable<String> targets, List<String> switches) {
        List<String> out = new ArrayList<>();
        for (String target : targets) {
            for (String s : switches) out.add(target + ":" + s);
        }
        return out.toArray(new String[0]);
    }
}
