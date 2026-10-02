package feed;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import model.GameEvent;
import util.GameEventParser;
import util.SimpleHttp;

import java.net.http.HttpResponse;

/** Sends events to Event Processing's {@code POST /events}. */
public final class HttpEventSink implements EventSink {

    private final String epsUrl;

    public HttpEventSink(String epsUrl) {
        this.epsUrl = epsUrl;
    }

    @Override
    public PostResult post(GameEvent event) throws Exception {
        HttpResponse<String> r = SimpleHttp.postJson(epsUrl + "/events", GameEventParser.toJson(event));
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
