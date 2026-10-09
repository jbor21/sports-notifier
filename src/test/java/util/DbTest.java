package util;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import testsupport.PostgresTestSupport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbTest {

    private static Set<String> tables(HikariDataSource ds) throws Exception {
        Set<String> out = new HashSet<>();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /** A new empty database on the shared server, so migrations start from nothing. */
    private static String freshDatabase() throws Exception {
        String name = "db_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = PostgresTestSupport.dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        return PostgresTestSupport.jdbcUrl().replaceAll("/[^/?]+(\\?|$)", "/" + name + "$1");
    }

    private static HikariDataSource pool(String url) {
        return Db.create(url, PostgresTestSupport.user(), PostgresTestSupport.password(), 2);
    }

    /**
     * Issue #29: the socket timeout was passed as a number, and the driver reads its settings as
     * strings, so it was dropped: every connection had no read timeout and a frozen Postgres hung
     * the caller forever.
     */
    @Test
    void socketTimeoutReachesTheDriver() throws Exception {
        try (HikariDataSource ds = Db.create(PostgresTestSupport.jdbcUrl(), PostgresTestSupport.user(),
                PostgresTestSupport.password(), 2, 3);
             Connection c = ds.getConnection()) {
            assertEquals(3000, c.getNetworkTimeout());
        }
    }

    @Test
    void migrateCreatesTables() throws Exception {
        try (HikariDataSource ds = pool(freshDatabase())) {
            Db.migrate(ds);
            assertTrue(tables(ds).containsAll(Set.of("users", "follows", "notifications", "notification_reads")));
        }
    }

    @Test
    void migrateTwiceIsANoop() throws Exception {
        try (HikariDataSource ds = pool(freshDatabase())) {
            Db.migrate(ds);
            Db.migrate(ds);
            assertTrue(tables(ds).contains("users"));
        }
    }

    @Test
    void twoMigrationsAtOnceBothSucceed() throws Exception {
        String url = freshDatabase();
        try (HikariDataSource a = pool(url); HikariDataSource b = pool(url)) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService ex = Executors.newFixedThreadPool(2);
            try {
                Future<?> fa = ex.submit(() -> { await(barrier); Db.migrate(a); });
                Future<?> fb = ex.submit(() -> { await(barrier); Db.migrate(b); });
                fa.get();
                fb.get();
            } finally {
                ex.shutdownNow();
            }
            assertTrue(tables(a).contains("notification_reads"));
        }
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
