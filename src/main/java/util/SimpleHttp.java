package util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class SimpleHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    private SimpleHttp() {}

    public static HttpResponse<String> get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .GET().build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> get(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .GET();
        headers.forEach(req::header);
        return CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> postJson(String url, String json) throws Exception {
        return postJson(url, json, Map.of());
    }

    public static HttpResponse<String> postJson(String url, String json, Map<String, String> headers) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(req::header);
        return CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> putJson(String url, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json))
            .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> patchJson(String url, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
            .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> delete(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .DELETE()
            .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
