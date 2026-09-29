package util;

import model.GameEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameEventParserTest {

    @Test
    void parse_deserializesJsonToGameEvent() {
        String json = "{\"gameId\":\"g1\",\"eventId\":\"e1\",\"eventType\":\"GAME_START\"," +
            "\"team\":\"Lakers\",\"timestamp\":1000,\"description\":\"Start\"," +
            "\"homeTeam\":\"Lakers\",\"awayTeam\":\"Celtics\"," +
            "\"homeScore\":0,\"awayScore\":0,\"gameStatus\":\"IN_PROGRESS\",\"league\":\"NBA\"}";

        GameEvent event = GameEventParser.parse(json);

        assertEquals("g1", event.getGameId());
        assertEquals("GAME_START", event.getEventType());
        assertEquals("Lakers", event.getHomeTeam());
        assertEquals("Celtics", event.getAwayTeam());
        assertEquals(1000L, event.getTimestamp());
    }

    @Test
    void toJson_serializesGameEventToJson() {
        GameEvent event = new GameEvent("g1", "e1", "SCORE_UPDATE", "Lakers",
            2000L, "Score!", null, null, 3, 1, "IN_PROGRESS", "NBA");

        String json = GameEventParser.toJson(event);

        assertTrue(json.contains("\"gameId\":\"g1\""));
        assertTrue(json.contains("\"eventType\":\"SCORE_UPDATE\""));
        assertTrue(json.contains("\"homeScore\":3"));
    }

    @Test
    void parse_handlesNullOptionalFields() {
        String json = "{\"gameId\":\"g1\",\"eventId\":\"e1\",\"eventType\":\"SCORE_UPDATE\"," +
            "\"team\":\"Lakers\",\"timestamp\":2000,\"description\":\"Score\"," +
            "\"homeScore\":3,\"awayScore\":1}";

        GameEvent event = GameEventParser.parse(json);

        assertEquals("g1", event.getGameId());
        assertNull(event.getHomeTeam());
        assertNull(event.getLeague());
        assertEquals(3, event.getHomeScore());
    }
}
