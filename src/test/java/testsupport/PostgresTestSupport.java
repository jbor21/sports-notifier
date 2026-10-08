package testsupport;

import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import util.Db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * One Postgres for the whole test JVM, started and migrated on first use. Testcontainers removes
 * it when the JVM exits. Tests share it, so each test starts with {@link #truncateAll()}.
 */
public final class PostgresTestSupport {

    /** Same major version as the chart's postgres.image. */
    private static final String IMAGE = "postgres:17";

    private static PostgreSQLContainer container;
    private static HikariDataSource dataSource;

    private PostgresTestSupport() {}

    private static synchronized PostgreSQLContainer container() {
        if (container == null) {
            PostgreSQLContainer started = new PostgreSQLContainer(IMAGE);
            started.start();
            container = started;
        }
        return container;
    }

    /** A pool on the shared database, migrated once. */
    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            PostgreSQLContainer c = container();
            HikariDataSource ds = Db.create(c.getJdbcUrl(), c.getUsername(), c.getPassword(), 5);
            Db.migrate(ds);
            dataSource = ds;
        }
        return dataSource;
    }

    /** Connection settings for tests that build their own pools. */
    public static String jdbcUrl() { return container().getJdbcUrl(); }
    public static String user() { return container().getUsername(); }
    public static String password() { return container().getPassword(); }

    /** Empties every table in the public schema except Flyway's history. */
    public static void truncateAll() {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
                    + " AND tablename <> 'flyway_schema_history'")) {
                while (rs.next()) tables.add(rs.getString(1));
            }
            if (!tables.isEmpty()) st.execute("TRUNCATE " + String.join(", ", tables) + " CASCADE");
        } catch (Exception e) {
            throw new IllegalStateException("could not truncate", e);
        }
    }

    /** Freezes the database's process (docker pause): connections stay open but nothing answers. */
    public static void pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(container().getContainerId()).exec();
    }

    public static void unpause() {
        DockerClientFactory.instance().client().unpauseContainerCmd(container().getContainerId()).exec();
    }

    /** Safety net for test cleanup: unpause only if a failed test left the database paused. */
    public static void unpauseIfPaused() {
        Boolean paused = DockerClientFactory.instance().client()
                .inspectContainerCmd(container().getContainerId()).exec().getState().getPaused();
        if (Boolean.TRUE.equals(paused)) unpause();
    }
}
