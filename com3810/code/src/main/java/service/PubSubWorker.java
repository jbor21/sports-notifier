package service;

import model.GameEvent;

/**
 * Consumes published game events and performs fan-out by determining
 * which users should receive notifications using cached subscription data.
 */
public interface PubSubWorker {

    /**
     * Handles a game event consumed from the pub/sub broker.
     *
     * @param event published game event
     */
    void handleEvent(GameEvent event);

    /**
     * Performs fan-out by determining matching users and triggering
     * notification work for those users.
     *
     * @param event published game event
     */
    void fanOutNotifications(GameEvent event);
}