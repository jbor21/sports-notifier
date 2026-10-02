package feed;

import model.GameEvent;
import util.GameEventParser;
import util.SimpleHttp;

/** Sends events to Event Processing's {@code POST /events}. */
public final class HttpEventSink implements EventSink {

    private final String epsUrl;

    public HttpEventSink(String epsUrl) {
        this.epsUrl = epsUrl;
    }

    @Override
    public int post(GameEvent event) throws Exception {
        return SimpleHttp.postJson(epsUrl + "/events", GameEventParser.toJson(event)).statusCode();
    }
}
