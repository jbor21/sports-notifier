package service;

import model.GameEvent;
import model.GameState;

/**
 * Manages current game state and serves game information to clients.
 */
public interface GameStateService {

    /**
     * Updates the stored state of a game based on an incoming event.
     *
     * @param event the event that changes the game state
     */
    void updateGameState(GameEvent event);

    /**
     * Retrieves the current state of a game.
     *
     * @param gameId unique identifier of the game
     * @return current game state
     */
    GameState getGameState(String gameId);
}