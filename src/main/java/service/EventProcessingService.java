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
     * @return whether the event was accepted, a duplicate, stale, or invalid
     */
    ProcessResult processEvent(GameEvent event);

    /**
     * Parses a JSON game event and processes it.
     *
     * @param json the request body
     * @return {@link ProcessResult#INVALID} if the JSON cannot be parsed, else as {@link #processEvent}
     */
    ProcessResult processJson(String json);
}