package simulator;

import model.GameEvent;
import util.GameEventParser;
import util.SimpleHttp;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs multiple waves of concurrent games.
 * Each wave starts after the previous one finishes.
 * Later waves introduce teams not pre-seeded in the frontend,
 * testing live preference-discovery.
 */
public final class EventSimulatorMain {

    private static final String EPS_URL = System.getenv().getOrDefault("EPS_URL", "http://127.0.0.1:8083");

    // format: gameId, home, away, league
    private static final String[][][] WAVES = {
        // Wave 1 — all teams known to the frontend
        {
            {"game-LAL-BOS", "LAL", "BOS", "NBA"},
            {"game-GSW-NYK", "GSW", "NYK", "NBA"},
            {"game-CHI-MIA", "CHI", "MIA", "NBA"},
            {"game-NYY-LAD", "NYY", "LAD", "MLB"},
            {"game-BOS-HOU", "BOS", "HOU", "MLB"},
            {"game-KC-SF",   "KC",  "SF",  "NFL"},
        },
        // Wave 2 — mix of known + NEW teams (OKC, MIL, PHI, DAL + NHL debut)
        {
            {"game-OKC-MIL", "OKC", "MIL", "NBA"},   // NEW NBA teams
            {"game-PHX-BKN", "PHX", "BKN", "NBA"},   // NEW NBA teams
            {"game-PHI-DAL", "PHI", "DAL", "NFL"},   // NEW NFL teams
            {"game-TOR-MTL", "TOR", "MTL", "NHL"},   // NEW league + NEW teams
            {"game-BOS-NYR", "BOS", "NYR", "NHL"},   // NEW NHL teams
            {"game-NYY-HOU", "NYY", "HOU", "MLB"},
        },
        // Wave 3 — mostly new teams, second NHL wave
        {
            {"game-DEN-MEM", "DEN", "MEM", "NBA"},   // NEW
            {"game-ATL-BKN", "ATL", "BKN", "NBA"},   // NEW
            {"game-LAC-CLE", "LAC", "CLE", "NBA"},   // NEW
            {"game-VAN-CGY", "VAN", "CGY", "NHL"},   // NEW
            {"game-EDM-WPG", "EDM", "WPG", "NHL"},   // NEW
            {"game-SF-CHC",  "SF",  "CHC", "MLB"},   // NEW
        },
    };

    public static void main(String[] args) throws Exception {
        long interval = Long.parseLong(System.getenv().getOrDefault("SIM_INTERVAL_MS", "2000"));
        int  numWaves = Integer.parseInt(System.getenv().getOrDefault("SIM_WAVES", String.valueOf(WAVES.length)));

        for (int w = 0; w < numWaves; w++) {
            String[][] wave = WAVES[w % WAVES.length];
            System.out.println("\n[Simulator] ═══ Starting wave " + (w + 1) + " (" + wave.length + " games) ═══\n");

            CountDownLatch latch = new CountDownLatch(wave.length);
            long baseInterval = interval + (w * 200L); // slight speed-up each wave

            for (String[] g : wave) {
                final String[] game = g;
                long jitter = ThreadLocalRandom.current().nextLong(0, 600);
                Thread t = new Thread(() -> {
                    runGame(game[0], game[1], game[2], game[3], baseInterval + jitter, latch);
                }, "sim-" + game[0]);
                t.start();
            }

            latch.await(); // wait for all games in this wave to finish

            if (w < numWaves - 1) {
                System.out.println("\n[Simulator] Wave " + (w + 1) + " complete. Next wave starts in 3s...\n");
                Thread.sleep(3000);
            }
        }

        System.out.println("\n[Simulator] All waves complete.");
    }

    private static void runGame(String gameId, String home, String away, String league,
                                long intervalMs, CountDownLatch latch) {
        try {
            long t = System.currentTimeMillis();
            post(new GameEvent(gameId, uuid(), "GAME_START", home, t,
                home + " vs " + away + " has started",
                home, away, 0, 0, "IN_PROGRESS", league));

            int hs = 0, as = 0;
            int events = 15 + ThreadLocalRandom.current().nextInt(11); // 15–25 scoring events
            for (int i = 0; i < events; i++) {
                Thread.sleep(intervalMs);
                boolean homeScores = ThreadLocalRandom.current().nextBoolean();
                int pts = ThreadLocalRandom.current().nextInt(1, 4);
                if (homeScores) hs += pts; else as += pts;
                String scorer = homeScores ? home : away;
                t = System.currentTimeMillis();
                post(new GameEvent(gameId, uuid(), "SCORE_UPDATE", scorer, t,
                    scorer + " scores! " + home + " " + hs + " - " + as + " " + away,
                    home, away, hs, as, "IN_PROGRESS", league));
            }

            Thread.sleep(intervalMs);
            t = System.currentTimeMillis();
            post(new GameEvent(gameId, uuid(), "GAME_END", home, t,
                "Final: " + home + " " + hs + " - " + as + " " + away,
                home, away, hs, as, "FINAL", league));

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[Simulator " + gameId + "] error: " + e.getMessage());
        } finally {
            latch.countDown();
        }
    }

    private static void post(GameEvent ev) throws Exception {
        String body = GameEventParser.toJson(ev);
        SimpleHttp.postJson(EPS_URL + "/events", body);
        System.out.println("[Simulator] " + ev.getEventType() + " " + ev.getGameId() + " :: " + ev.getDescription());
    }

    private static String uuid() { return UUID.randomUUID().toString(); }
}
