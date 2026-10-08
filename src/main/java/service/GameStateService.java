package service;

import com.google.gson.JsonObject;
import games.GameQuery;

import java.util.List;
import java.util.Optional;

/**
 * Serves games to clients, as the API's GameSummary and GameDetail. Reads only: the Lua script in
 * EventProcessingServiceImpl writes game:<id>, the poller's schedule loop writes sched:<id>.
 */
public interface GameStateService {

    /** The games starting in the query's range, by start time then id, filtered as asked. */
    List<JsonObject> listDay(GameQuery query);

    /** One game with its line score; empty if neither a record nor a schedule entry exists. */
    Optional<JsonObject> detail(String gameId);
}
