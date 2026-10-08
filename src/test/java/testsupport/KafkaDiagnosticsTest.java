package testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** What gets saved when a Kafka test fails: the broker's own log and the containers' resource use. */
class KafkaDiagnosticsTest {

    @Test
    void captureWritesBrokerLogAndDockerStats(@TempDir Path dir) throws Exception {
        KafkaTestSupport.bootstrap();   // the broker is running
        Optional<Path> file = KafkaDiagnostics.capture("SomeTest.someMethod", dir);
        assertTrue(file.isPresent());
        String text = Files.readString(file.get());
        assertTrue(file.get().getFileName().toString().startsWith("SomeTest.someMethod-"));
        assertTrue(text.contains("== broker log (last "), text);
        assertTrue(text.contains("Kafka Server started") || text.contains("KafkaRaftServer") || text.contains("INFO"),
                "the broker's own log lines are there");
        assertTrue(text.contains("== docker stats =="), text);
        assertTrue(text.contains("MEM USAGE") || text.contains("CPU"), "docker stats' header is there");
    }

    @Test
    void nothingWhenNoBrokerWasStarted(@TempDir Path dir) {
        assertEquals(Optional.empty(), KafkaDiagnostics.captureIfStarted(false, "X.y", dir));
    }
}
