package gateway;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Serves the built web UI (web/dist, copied to WEB_DIR in the image) on the gateway's own origin,
 * so the session cookie and /api/v1/ws need no CORS (Phase 3b spec, 4.2).
 */
public final class WebFiles {

    private static final String IMMUTABLE = "public, max-age=31536000, immutable";
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$");
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("json", "application/json"),
            Map.entry("webmanifest", "application/manifest+json"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("txt", "text/plain; charset=utf-8"));

    private WebFiles() {
    }

    /** Registers file serving for {@code webDir}; false (and nothing registered) when it holds no index.html. */
    public static boolean register(Javalin app, Path webDir) {
        Path root = webDir.toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("index.html"))) {
            System.out.println("[Gateway] no web files at " + root + "; serving the API only");
            return false;
        }
        // Files are served from the 404 handler: every route, and every 404 a route sets itself, stays
        // as it is; only GETs that matched nothing, outside /api/, ever reach the files.
        app.error(404, ctx -> serve(ctx, root));
        return true;
    }

    private static void serve(Context ctx, Path root) {
        String path = ctx.path();
        if (ctx.method() != HandlerType.GET || path.startsWith("/api/") || path.equals("/health")) return;

        Path file = root.resolve(path.substring(1)).normalize();
        if (!file.startsWith(root)) return;
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!Files.isRegularFile(file)) {
            String last = path.substring(path.lastIndexOf('/') + 1);
            if (last.contains(".")) return; // a missing file stays a 404, never HTML in its place
            file = root.resolve("index.html");
            name = "index.html";
        }

        ctx.status(200);
        ctx.contentType(TYPES.getOrDefault(extension(name), "application/octet-stream"));
        ctx.header("Cache-Control", path.startsWith("/assets/") ? IMMUTABLE : "no-cache");
        ctx.header("Content-Security-Policy", contentSecurityPolicy(ctx.header("Host")));
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("Referrer-Policy", "strict-origin-when-cross-origin");
        try {
            ctx.result(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String contentSecurityPolicy(String host) {
        // Older Safari doesn't count ws:/wss: as 'self', so the socket's host is named. A bare ws: would
        // allow sockets to any host; the pattern keeps a forged Host header from injecting directives.
        String connect = host != null && HOST.matcher(host).matches()
                ? "'self' ws://" + host + " wss://" + host
                : "'self'";
        return "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https://a.espncdn.com; "
                + "font-src 'self'; connect-src " + connect + "; manifest-src 'self'; worker-src 'self'; "
                + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
