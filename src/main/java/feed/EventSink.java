package feed;

import model.GameEvent;

/** Where the poller sends events. In production, POST /events on Event Processing. */
public interface EventSink {

    /** @return the HTTP status of the POST */
    int post(GameEvent event) throws Exception;
}
