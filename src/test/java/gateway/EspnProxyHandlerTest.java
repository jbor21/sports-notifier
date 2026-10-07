package gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EspnProxyHandlerTest {

    @Test
    void upcomingGameIdNamesTheLeague() {
        // Game follows are made from this list, so its ids must match the poller's or alerts never reach them.
        JsonObject event = JsonParser.parseString("{\"id\":\"401872948\",\"date\":\"2026-10-11T17:00Z\",\"competitions\":[{"
            + "\"status\":{\"type\":{\"name\":\"STATUS_SCHEDULED\",\"state\":\"pre\"}},"
            + "\"competitors\":[{\"homeAway\":\"home\",\"team\":{\"abbreviation\":\"GB\",\"displayName\":\"Green Bay Packers\"}},"
            + "{\"homeAway\":\"away\",\"team\":{\"abbreviation\":\"ATL\",\"displayName\":\"Atlanta Falcons\"}}]}]}").getAsJsonObject();
        Map<String, Object> game = EspnProxyHandler.parseScheduledGame(event, "NFL");
        assertNotNull(game);
        assertEquals("nfl-401872948", game.get("gameId"));
    }
}
