package feed;

import model.GameEvent;

/** Where the poller sends events. In production, POST /events on Event Processing. */
public interface EventSink {

    /**
     * @param status HTTP status of the POST
     * @param result Event Processing's verdict ({@code accepted}, {@code duplicate}, {@code stale},
     *               {@code invalid}), or null if the response had none
     */
    record PostResult(int status, String result) {}

    PostResult post(GameEvent event) throws Exception;
}
