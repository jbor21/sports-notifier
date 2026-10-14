package feed;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.opentelemetry.api.trace.Span;
import model.GameEvent;
import telemetry.Traces;
import util.GameEventParser;
import util.SimpleHttp;

import java.net.http.HttpResponse;
import java.util.Map;

/** Sends events to Event Processing's {@code POST /events}. */
public final class HttpEventSink implements EventSink {

    private final String epsUrl;

    public HttpEventSink(String epsUrl) {
        this.epsUrl = epsUrl;
    }

    @Override
    public PostResult post(GameEvent event) throws Exception {
        // The current span (the poller's poll) goes along, so Event Processing's accept joins its trace.
        String traceparent = Traces.traceparent(Span.current());
        HttpResponse<String> r = SimpleHttp.postJson(epsUrl + "/events", GameEventParser.toJson(event),
            traceparent == null ? Map.of() : Map.of(Traces.HEADER, traceparent));
        return new PostResult(r.statusCode(), resultOf(r.body()));
    }

    /** The {@code result} field of the response body, or null. */
    static String resultOf(String body) {
        if (body == null) return null;
        try {
            JsonElement el = JsonParser.parseString(body);
            if (!el.isJsonObject()) return null;
            JsonElement result = el.getAsJsonObject().get("result");
            return result != null && result.isJsonPrimitive() ? result.getAsString() : null;
        } catch (JsonParseException e) {
            return null;
        }
    }
}
