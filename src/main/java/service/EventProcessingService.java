package service;

import model.GameEvent;

/**
 * Processes incoming sports events and publishes valid events
 * for downstream notification handling.
 */
public interface EventProcessingService {

    /**
     * Processes an incoming game event and determines whether
     * it should trigger a notification workflow.
     *
     * @param event normalized game event
     */
    void processEvent(GameEvent event);

    /**
     * Publishes a validated event to the pub/sub broker so
     * downstream workers can consume it asynchronously.
     *
     * @param event validated game event
     */
    void publishEvent(GameEvent event);
}