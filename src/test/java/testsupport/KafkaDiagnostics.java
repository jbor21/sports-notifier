package testsupport;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * When a test fails while the test Kafka broker is running, saves the broker's last log lines and a
 * {@code docker stats} snapshot under {@code target/kafka-diagnostics/}. The broker container is
 * deleted when the test JVM exits, so without this a failure like "no response from the broker for
 * 120 s" leaves nothing to look at. Registered for every test through JUnit's extension
 * auto-detection (src/test/resources); it does nothing for tests that never started the broker.
 */
public final class KafkaDiagnostics implements TestWatcher {

    private static final Path DIR = Path.of("target", "kafka-diagnostics");
    private static final int LOG_LINES = 300;

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        String name = context.getRequiredTestClass().getSimpleName() + "." + context.getDisplayName().replaceAll("[^A-Za-z0-9_.-]", "");
        captureIfStarted(KafkaTestSupport.started(), name, DIR)
                .ifPresent(f -> System.err.println("[KafkaDiagnostics] broker log and docker stats saved to " + f));
    }

    static Optional<Path> captureIfStarted(boolean brokerStarted, String name, Path dir) {
        return brokerStarted ? capture(name, dir) : Optional.empty();
    }

    /** Writes {@code <dir>/<name>-<time>.log}; empty if it could not be written. Never throws. */
    static Optional<Path> capture(String name, Path dir) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(name + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log");
            List<String> lines = Arrays.asList(KafkaTestSupport.brokerLogs().split("\n"));
            String tail = String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size()));
            String text = "== broker log (last " + LOG_LINES + " lines) ==\n" + tail
                    + "\n\n== docker stats ==\n" + run("docker", "stats", "--no-stream") + "\n";
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return Optional.of(file);
        } catch (Exception e) {
            System.err.println("[KafkaDiagnostics] could not save diagnostics: " + e);
            return Optional.empty();
        }
    }

    private static String run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "(" + String.join(" ", command) + " gave no answer in 20 s: Docker itself may be stuck)";
            }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(" + String.join(" ", command) + " failed: " + e + ")";
        }
    }
}
