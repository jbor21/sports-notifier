package simulator;

import feed.HttpEventSink;
import feed.Poller;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.JedisPoolHolder;
import util.RedisKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Runs waves of scripted games through the real {@link Poller}, exactly as the ESPN poller runs live
 * games: the poller reads {@code game:<id>}, compares, and POSTs plays to Event Processing. Each wave
 * starts after the previous one is final. Later waves bring teams and an NHL debut that wave 1 lacks.
 *
 * A play answered {@code pending} needs nothing here: the poller sees the same difference on its next
 * poll and sends it again (issue #7).
 */
public final class EventSimulatorMain {

    private static final String EPS_URL = System.getenv().getOrDefault("EPS_URL", "http://127.0.0.1:8083");
    private static final String[] LEAGUES = {"NFL", "NBA", "MLB", "NHL"};
    /** After the scripts end, how long the last plays get to be accepted before the run is called failed. */
    private static final long FINAL_WAIT_MILLIS = 60_000L;

    /** Home, away, league. Abbreviations are ESPN's, as in teams.json, so every team can be followed. */
    private static final String[][][] WAVES = {
        // Wave 1. The smoke test follows mlb-hou (slot 5) and nfl-kc (slot 6).
        {
            {"LAL", "BOS", "NBA"},
            {"GS",  "NY",  "NBA"},
            {"CHI", "MIA", "NBA"},
            {"NYY", "LAD", "MLB"},
            {"BOS", "HOU", "MLB"},
            {"KC",  "SF",  "NFL"},
        },
        // Wave 2: new NBA and NFL teams, and the NHL's first games.
        {
            {"OKC", "MIL", "NBA"},
            {"PHX", "BKN", "NBA"},
            {"PHI", "DAL", "NFL"},
            {"TOR", "MTL", "NHL"},
            {"BOS", "NYR", "NHL"},
            {"NYY", "HOU", "MLB"},
        },
        // Wave 3: mostly new teams, a second NHL wave.
        {
            {"DEN", "MEM", "NBA"},
            {"ATL", "BKN", "NBA"},
            {"LAC", "CLE", "NBA"},
            {"VAN", "CGY", "NHL"},
            {"EDM", "WPG", "NHL"},
            {"SF",  "CHC", "MLB"},
        },
    };

    public static void main(String[] args) throws Exception {
        long stepMillis = Long.parseLong(System.getenv().getOrDefault("SIM_INTERVAL_MS", "2000"));
        long pollMillis = Long.parseLong(System.getenv().getOrDefault("SIM_POLL_MS", "1000"));
        int numWaves = Integer.parseInt(System.getenv().getOrDefault("SIM_WAVES", String.valueOf(WAVES.length)));

        JedisPoolHolder.awaitRedisOrExit("simulator");
        long runStart = System.currentTimeMillis() / 1000;
        boolean allFinal = true;
        try (JedisPool pool = JedisPoolHolder.create()) {
            HttpEventSink sink = new HttpEventSink(EPS_URL);
            List<List<GameScript>> waves = waves(numWaves);
            for (int w = 0; w < waves.size(); w++) {
                List<GameScript> wave = waves.get(w);
                SimulatedScoreFeed feed = new SimulatedScoreFeed(runStart, wave, System::currentTimeMillis, stepMillis);
                Poller poller = new Poller(feed, pool, sink, System::currentTimeMillis);
                System.out.println("\n[Simulator] === Wave " + (w + 1) + ": " + feed.gameIds() + " ===\n");
                allFinal &= runWave(feed, poller, pool, pollMillis);
                if (w < waves.size() - 1) {
                    System.out.println("\n[Simulator] Wave " + (w + 1) + " complete. Next wave starts in 3s...\n");
                    Thread.sleep(3000);
                }
            }
        }
        if (!allFinal) {
            System.err.println("[Simulator] Done, but some games never reached FINAL (see above).");
            System.exit(1);
        }
        System.out.println("\n[Simulator] All waves complete.");
    }

    /** The default waves, slots numbered through the run. */
    static List<List<GameScript>> waves() {
        return waves(WAVES.length);
    }

    /**
     * {@code count} waves, repeating the matchups past the third. Slots count up through the whole
     * run, not per wave: a run has one start time, so a slot reused in a later wave would be a reused
     * game id.
     */
    static List<List<GameScript>> waves(int count) {
        List<List<GameScript>> out = new ArrayList<>();
        int slot = 0;
        for (int w = 0; w < count; w++) {
            List<GameScript> wave = new ArrayList<>();
            for (String[] g : WAVES[w % WAVES.length]) {
                wave.add(script(g[2]).apply(g[0], g[1]).inSlot(++slot));
            }
            out.add(wave);
        }
        return out;
    }

    private static BiFunction<String, String, GameScript> script(String league) {
        return switch (league) {
            case "NFL" -> GameScript::nfl;
            case "NBA" -> GameScript::nba;
            case "MLB" -> GameScript::mlb;
            case "NHL" -> GameScript::nhl;
            default -> throw new IllegalArgumentException("no script for " + league);
        };
    }

    /**
     * Polls every league until the scripts have ended and every game's record says FINAL.
     *
     * @return false if some game was still not final a minute after its script ended
     */
    private static boolean runWave(SimulatedScoreFeed feed, Poller poller, JedisPool pool, long pollMillis)
            throws InterruptedException {
        long giveUpAt = Long.MAX_VALUE;
        while (true) {
            for (String league : LEAGUES) {
                try {
                    poller.pollLeague(league);
                } catch (Exception e) {
                    System.err.println("[Simulator] " + league + " poll failed: " + e);
                }
            }
            if (feed.done()) {
                List<String> open = notFinal(pool, feed.gameIds());
                if (open.isEmpty()) return true;
                long now = System.currentTimeMillis();
                if (giveUpAt == Long.MAX_VALUE) {
                    giveUpAt = now + FINAL_WAIT_MILLIS;
                } else if (now > giveUpAt) {
                    System.err.println("[Simulator] still not FINAL after " + FINAL_WAIT_MILLIS / 1000 + "s: " + open);
                    return false;
                }
            }
            Thread.sleep(pollMillis);
        }
    }

    private static List<String> notFinal(JedisPool pool, List<String> gameIds) {
        List<String> open = new ArrayList<>();
        try (Jedis jedis = pool.getResource()) {
            for (String id : gameIds) {
                if (!"FINAL".equals(jedis.hget(RedisKeys.GAME_PREFIX + id, "status"))) open.add(id);
            }
        } catch (Exception e) {
            System.err.println("[Simulator] reading game records failed: " + e);
            return gameIds;
        }
        return open;
    }
}
