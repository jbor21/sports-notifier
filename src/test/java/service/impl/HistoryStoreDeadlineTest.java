package service.impl;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import testsupport.PostgresTestSupport;
import util.Db;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Issue #29: a batch insert against a frozen Postgres fails at its deadline instead of hanging. */
class HistoryStoreDeadlineTest {

    private HikariDataSource db;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
    }

    @AfterEach
    void tearDown() {
        PostgresTestSupport.unpauseIfPaused();
        if (db != null) db.close();
    }

    private static HistoryStore.HistoryRow row() {
        return new HistoryStore.HistoryRow(UUID.randomUUID().toString(), "mlb-1:v1", Instant.now(), "MLB", "mlb-1",
                "mlb-bos", "mlb-nyy", "{\"headline\":{\"type\":\"GAME_START\",\"text\":\"x\"}}");
    }

    @Test
    @Timeout(90)
    void frozenDatabaseFailsTheBatchAtTheDeadline() throws Exception {
        // A socket timeout far beyond the deadline, so only the deadline can end the insert.
        db = Db.create(PostgresTestSupport.jdbcUrl(), PostgresTestSupport.user(), PostgresTestSupport.password(), 2, 60);
        HistoryStore store = new HistoryStore(db, Duration.ofSeconds(2));
        store.insertBatch(List.of(row()));   // warm: the pool has an open connection before the freeze
        PostgresTestSupport.pause();
        long start = System.nanoTime();
        assertThrows(SQLException.class, () -> store.insertBatch(List.of(row())));
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertTrue(took.compareTo(Duration.ofSeconds(8)) < 0, "failed after " + took + ", deadline 2 s");
        PostgresTestSupport.unpause();
        assertEquals(1, store.insertBatch(List.of(row())), "the store works again once Postgres is back");
    }

    @Test
    void aFastInsertIsUnaffected() throws Exception {
        db = Db.create(PostgresTestSupport.jdbcUrl(), PostgresTestSupport.user(), PostgresTestSupport.password(), 2, 60);
        HistoryStore store = new HistoryStore(db, Duration.ofSeconds(2));
        assertEquals(2, store.insertBatch(List.of(row(), row())));
    }
}
