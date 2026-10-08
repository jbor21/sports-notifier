package tools;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recorded games through the detectors (spec 7.3). Each folder under {@code espn/replay/} is one game,
 * {@code <league>-<espn event id>}: its scoreboards {@code 0001.json, 0002.json, …} in time order, and
 * {@code expected.txt}, the timeline written by hand from the snapshots and reviewed.
 */
class ReplayGoldenTest {

    @TestFactory
    Stream<DynamicTest> recordedGamesMatchTheirGoldenTimelines() throws IOException, URISyntaxException {
        URL root = getClass().getClassLoader().getResource("espn/replay");
        assertTrue(root != null, "no espn/replay fixtures on the test classpath");
        List<Path> games;
        try (Stream<Path> dirs = Files.list(Path.of(root.toURI()))) {
            games = dirs.filter(Files::isDirectory).sorted().toList();
        }
        assertTrue(games.size() >= 2, "expected at least two replay fixtures, found " + games);
        return games.stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(), () -> check(dir)));
    }

    private static void check(Path dir) throws IOException {
        String name = dir.getFileName().toString();
        int dash = name.indexOf('-');
        String league = name.substring(0, dash).toUpperCase();
        List<String> boards = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("\\d{4}\\.json")).sorted().toList()) {
                boards.add(Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        assertTrue(boards.size() > 1, name + ": no boards");
        List<String> expected = Files.readAllLines(dir.resolve("expected.txt"), StandardCharsets.UTF_8).stream()
            .filter(l -> !l.isBlank()).toList();
        assertEquals(expected, ReplayDetectors.timeline(league, boards, name));
    }
}
