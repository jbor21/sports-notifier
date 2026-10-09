package checker;

import alerts.Detectors;
import alerts.GameLabels;
import catalog.AlertCatalog;
import catalog.AlertSetting;
import catalog.AlertType;
import catalog.FollowKind;
import catalog.Teams;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import model.GameEvent;
import org.junit.jupiter.api.Test;
import service.impl.Follow;
import tools.ReplayDetectors;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Expected pushes recomputed from accepted plays and follows (Phase 4.1 spec, 6.1). */
class OracleTest {

    private static final GameLabels LABELS = new GameLabels(Teams.load());
    private static final Oracle ORACLE = new Oracle(Detectors.standard(LABELS), LABELS);
    private static final List<String> GOLDEN = List.of("nfl-401872971", "nhl-401891803", "nhl-401891831", "nhl-401892443");

    private static Path golden(String game) throws URISyntaxException {
        return Path.of(OracleTest.class.getClassLoader().getResource("espn/replay/" + game).toURI());
    }

    /** The golden game's accepted plays, versioned as the accept script would. */
    private static List<GameEvent> plays(String game) throws Exception {
        List<String> boards = new ArrayList<>();
        try (Stream<Path> files = Files.list(golden(game))) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("\\d{4}\\.json")).sorted().toList()) {
                boards.add(Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        return ReplayDetectors.plays(game.substring(0, 3).toUpperCase(), boards, game);
    }

    /** A follow of the game with every switch the catalog offers now turned on. */
    private static Follow everything(String user, String game) {
        String league = game.substring(0, 3).toUpperCase();
        Map<String, AlertSetting> alerts = new LinkedHashMap<>();
        for (AlertType t : AlertCatalog.load().forLeague(league)) {
            if ("available".equals(t.availability())) alerts.put(t.type(), new AlertSetting(true, Map.of()));
        }
        return new Follow("f-" + user, user, FollowKind.GAME, game, league, alerts, null, Instant.EPOCH);
    }

    /** Every alert text in a user's pushes, headline then extras, in order. */
    private static List<String> texts(List<ExpectedPush> pushes) {
        List<String> out = new ArrayList<>();
        for (ExpectedPush p : pushes) {
            out.add(p.body().getAsJsonObject("headline").get("text").getAsString());
            for (JsonElement e : p.body().getAsJsonArray("extras")) out.add(e.getAsJsonObject().get("text").getAsString());
        }
        return out;
    }

    @Test
    void goldenGamesMatchGoldenAlerts() throws Exception {
        for (String game : GOLDEN) {
            List<String> want = new ArrayList<>();
            for (String line : Files.readAllLines(golden(game).resolve("expected.txt"), StandardCharsets.UTF_8)) {
                // "<label> <clock> <alert id> <text>"; a label can be two words ("End 1st"), so find the id.
                if (!line.isBlank()) want.add(line.substring(line.indexOf(' ', line.indexOf(game + ":")) + 1));
            }
            List<ExpectedPush> got = ORACLE.expected(Map.of(game, plays(game)), List.of(everything("fan", game)));
            assertEquals(want, texts(got), game);
            assertTrue(got.stream().allMatch(p -> p.userId().equals("fan") && p.gameId().equals(game)));
            assertTrue(got.stream().allMatch(p -> p.messageId().equals(p.body().get("id").getAsString())));
        }
    }

    @Test
    void duplicateVersionOnTopicSkipped() throws Exception {
        String game = "nhl-401891831";
        List<GameEvent> plays = plays(game);
        List<GameEvent> resent = new ArrayList<>(plays);
        resent.add(20, plays.get(19));                     // the outbox re-sent version 20 after a lost confirmation
        List<ExpectedPush> once = ORACLE.expected(Map.of(game, plays), List.of(everything("fan", game)));
        List<ExpectedPush> twice = ORACLE.expected(Map.of(game, resent), List.of(everything("fan", game)));
        assertEquals(once.stream().map(ExpectedPush::messageId).toList(), twice.stream().map(ExpectedPush::messageId).toList());
    }

    @Test
    void finalIsItsOwnPushAfterTheMoment() throws Exception {
        String game = "nhl-401891831";                     // won in overtime
        List<ExpectedPush> got = ORACLE.expected(Map.of(game, plays(game)), List.of(everything("fan", game)));
        ExpectedPush last = got.get(got.size() - 1);
        ExpectedPush before = got.get(got.size() - 2);
        assertEquals(game + ":FINAL", last.messageId());
        assertEquals(0, last.body().getAsJsonArray("extras").size());
        assertEquals(game + ":SCORE:away:4", before.messageId());
        assertTrue(last.version() >= before.version());
    }

    @Test
    void noFollowersNoPushes() throws Exception {
        String game = "nfl-401872971";
        assertEquals(List.of(), ORACLE.expected(Map.of(game, plays(game)), List.of()));
    }

    @Test
    void ordersByGameThenPlayThenUser() throws Exception {
        String game = "nhl-401891803";
        List<ExpectedPush> got = ORACLE.expected(Map.of(game, plays(game)),
            List.of(everything("zed", game), everything("amy", game)));
        assertEquals("amy", got.get(0).userId());
        assertEquals("zed", got.get(1).userId());
        assertEquals(got.get(0).messageId(), got.get(1).messageId());
    }
}
