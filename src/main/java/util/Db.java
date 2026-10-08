package util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;

/** Postgres connection pool and schema migration, the way {@link JedisPoolHolder} serves Redis. */
public final class Db {

    private Db() {}

    public static HikariDataSource create(String url, String user, String password, int poolSize) {
        return create(url, user, password, poolSize,
                Integer.parseInt(System.getenv().getOrDefault("PG_SOCKET_TIMEOUT_SECONDS", "30")));
    }

    /**
     * {@code socketTimeoutSeconds}: a query that gets no answer for this long fails. Without it, a
     * frozen Postgres (connection open, nothing answering) would hang the caller forever; a worker
     * loop would stop instead of failing the batch and retrying.
     */
    public static HikariDataSource create(String url, String user, String password, int poolSize, int socketTimeoutSeconds) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(poolSize);
        cfg.setConnectionTimeout(5000);
        cfg.addDataSourceProperty("socketTimeout", socketTimeoutSeconds);
        return new HikariDataSource(cfg);
    }

    /**
     * Pool from PG_URL / PG_USER / PG_PASSWORD / PG_POOL_SIZE. Waits up to 2 minutes for Postgres
     * (a first start also waits for initdb), then exits so Kubernetes restarts the pod, like
     * {@link JedisPoolHolder#awaitRedisOrExit}.
     */
    public static HikariDataSource fromEnv(String serviceName) {
        var env = System.getenv();
        String url = env.getOrDefault("PG_URL", "jdbc:postgresql://postgres:5432/sportsnotifier");
        String user = env.getOrDefault("PG_USER", "sportsnotifier");
        String password = env.getOrDefault("PG_PASSWORD", "");
        int poolSize = Integer.parseInt(env.getOrDefault("PG_POOL_SIZE", "5"));
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (true) {
            HikariDataSource ds = null;
            try {
                ds = create(url, user, password, poolSize);
                try (Connection c = ds.getConnection()) {
                    if (c.isValid(2)) return ds;
                }
            } catch (Exception e) {
                System.out.println("[" + serviceName + "] waiting for Postgres at " + url);
                if (ds != null) ds.close();
            }
            if (System.nanoTime() + Duration.ofSeconds(2).toNanos() > deadline) {
                System.err.println("[" + serviceName + "] Postgres at " + url + " not reachable after 2 minutes, exiting");
                System.exit(1);
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                System.exit(1);
            }
        }
    }

    /** Applies pending migrations. Flyway holds a Postgres lock, so pods starting together are safe. */
    public static void migrate(DataSource ds) {
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
