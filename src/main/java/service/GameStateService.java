package service;

import model.GameState;

/**
 * Serves current game state to clients. The Lua script in EventProcessingServiceImpl is the only writer.
 */
public interface GameStateService {

    /**
     * Retrieves the current state of a game.
     *
     * @param gameId unique identifier of the game
     * @return current game state
     */
    GameState getGameState(String gameId);
}