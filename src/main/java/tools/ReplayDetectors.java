package tools;

import alerts.Alert;
import alerts.Detectors;
import alerts.GameLabels;
import alerts.GameMemory;
import alerts.Play;
import catalog.Teams;
import feed.EspnScoreFeed;
import feed.PollDecision;
import feed.Seq;
import model.GameEvent;
import model.GameSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Replays recorded ESPN scoreboards through the poller's decision and the detector chain, and prints
 * each game's alert timeline (spec 7.3). A test tool: no Redis, Kafka, or cluster. The accept script is
 * emulated by an in-memory record per game; a snapshot is accepted when {@link PollDecision} picks an
 * event and its seq is above the record's. Version = accept count.
 *
 * Usage: {@code make replay-detectors}, or {@code RECORDINGS_DIR=... java -cp target/sports-notifier.jar tools.ReplayDetectors}.
 */
public final class ReplayDetectors {

    private static final List<String> LEAGUES = List.of("NFL", "NBA", "MLB", "NHL");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final long BOARD_SPACING_MILLIS = 30_000;

    private static final GameLabels LABELS = new GameLabels(Teams.load());
    private static final Detectors DETECTORS = Detectors.standard(LABELS);

    private ReplayDetectors() {}

    /** One game's alerts, one line each, from scoreboards in time order. */
    public static List<String> timeline(String league, List<String> boardsJson, String gameId) {
        GameReplay game = new GameReplay();
        long now = 0;
        for (String board : boardsJson) {
            now += BOARD_SPACING_MILLIS;
            for (GameSnapshot snap : EspnScoreFeed.parse(board, league)) {
                if (snap.gameId().equals(gameId)) game.offer(snap, now);
            }
        }
        return game.lines;
    }

    /**
     * One game's accepted plays, versioned as the accept script would number them: what Stage 1 would
     * read from game-events for this game. The checker's oracle tests use the golden games through it.
     */
    public static List<GameEvent> plays(String league, List<String> boardsJson, String gameId) {
        GameReplay game = new GameReplay();
        long now = 0;
        for (String board : boardsJson) {
            now += BOARD_SPACING_MILLIS;
            for (GameSnapshot snap : EspnScoreFeed.parse(board, league)) {
                if (snap.gameId().equals(gameId)) game.offer(snap, now);
            }
        }
        return game.plays;
    }

    /** One game's state: the emulated {@code game:<id>} record, the version counter, the detectors' memory. */
    private static final class GameReplay {
        final Map<String, String> record = new HashMap<>();
        final List<String> lines = new ArrayList<>();
        final List<GameEvent> plays = new ArrayList<>();
        long version;
        GameMemory memory;

        // The accept script's two heals (an older-looking snapshot with more points, or one stuck for
        // 30 s) are not emulated: here a snapshot whose seq is not above the record's is just dropped.
        void offer(GameSnapshot snap, long now) {
            Optional<GameEvent> decided = PollDecision.decide(record, snap, now);
            if (decided.isEmpty()) return;
            GameEvent event = decided.get();
            if (!record.isEmpty() && event.getSeq() <= Long.parseLong(record.get("lastSeq"))) return;
            version++;
            record.put("status", snap.status());
            record.put("homeScore", String.valueOf(snap.homeScore()));
            record.put("awayScore", String.valueOf(snap.awayScore()));
            record.put("lastSeq", String.valueOf(Seq.of(snap)));

            GameEvent accepted = event.withVersion(version);
            plays.add(accepted);
            Play play = Play.of(accepted);
            Detectors.Result result = DETECTORS.run(memory, play);
            memory = result.after();
            for (Alert a : result.moment()) lines.add(line(play, a));
            if (result.finalAlert() != null) lines.add(line(play, result.finalAlert()));
        }
    }

    private static String line(Play play, Alert a) {
        String label = GameLabels.periodLabel(play);
        String clock = GameLabels.clock(play);
        return (label == null ? "-" : label) + " " + (clock == null ? "-" : clock) + " " + a.id() + " " + a.text();
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(System.getenv().getOrDefault("RECORDINGS_DIR",
            System.getProperty("user.home") + "/sportsnotifier-recordings"));
        int files = 0, failed = 0, games = 0;
        for (String league : LEAGUES) {
            Path leagueDir = dir.resolve(league.toLowerCase());
            if (!Files.isDirectory(leagueDir)) {
                System.err.println("[replay] no folder " + leagueDir + ", skipped");
                continue;
            }
            List<Path> boards;
            try (Stream<Path> s = Files.list(leagueDir)) {
                boards = s.filter(p -> p.getFileName().toString().endsWith(".json.gz")).sorted().toList();
            }
            Map<String, GameReplay> byGame = new LinkedHashMap<>();
            for (Path file : boards) {
                files++;
                try {
                    long now = fileTime(file);
                    for (GameSnapshot snap : EspnScoreFeed.parse(readGzip(file), league)) {
                        byGame.computeIfAbsent(snap.gameId(), id -> new GameReplay()).offer(snap, now);
                    }
                } catch (Exception e) {
                    failed++;
                    System.err.println("[replay] " + league + " " + file.getFileName() + ": " + e);
                }
            }
            for (Map.Entry<String, GameReplay> g : byGame.entrySet()) {
                games++;
                System.out.println("== " + g.getKey() + " ==");
                g.getValue().lines.forEach(System.out::println);
            }
        }
        System.err.println("[replay] " + files + " files, " + failed + " failed, " + games + " games");
    }

    private static String readGzip(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** {@code 20261002T192750Z.json.gz} → its epoch millis; 0 if the name is not a timestamp. */
    private static long fileTime(Path file) {
        String name = file.getFileName().toString();
        try {
            return LocalDateTime.parse(name.substring(0, name.indexOf('.')), FILE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
