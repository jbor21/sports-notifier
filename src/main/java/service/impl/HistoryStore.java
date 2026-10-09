package service.impl;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Inserts into {@code notifications}, many rows per statement. */
public final class HistoryStore {

    /** One history row. {@code createdAt} is the Kafka timestamp of the notifications record. */
    public record HistoryRow(String userId, String messageId, Instant createdAt, String league, String gameId,
                             String homeTeam, String awayTeam, String bodyJson) {}

    private static final int COLUMNS = 8;

    /** Aborts inserts that pass their deadline; one daemon thread for every store. */
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "history-insert-deadline");
        t.setDaemon(true);
        return t;
    });

    private final DataSource db;
    private final Duration deadline;

    public HistoryStore(DataSource db) {
        this(db, Duration.ofSeconds(10));
    }

    /** {@code deadline}: an insert still running after this is aborted and the batch fails (issue #29). */
    public HistoryStore(DataSource db, Duration deadline) {
        this.db = db;
        this.deadline = deadline;
    }

    /**
     * One statement for the whole batch. The primary key (user_id, message_id) makes a re-read batch,
     * or a message Stage 1 sent twice, insert nothing new: the first copy, and its time, stays.
     *
     * @return rows actually inserted
     */
    public int insertBatch(List<HistoryRow> rows) throws SQLException {
        if (rows.isEmpty()) return 0;
        StringBuilder sql = new StringBuilder("INSERT INTO notifications"
                + " (user_id, message_id, created_at, league, game_id, home_team, away_team, body) VALUES ");
        for (int i = 0; i < rows.size(); i++) sql.append(i == 0 ? "" : ", ").append("(?, ?, ?, ?, ?, ?, ?, ?::jsonb)");
        sql.append(" ON CONFLICT (user_id, message_id) DO NOTHING");
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 0;
            for (HistoryRow r : rows) {
                ps.setObject(++i, UUID.fromString(r.userId()));
                ps.setString(++i, r.messageId());
                ps.setTimestamp(++i, Timestamp.from(r.createdAt()));
                ps.setString(++i, r.league());
                ps.setString(++i, r.gameId());
                ps.setString(++i, r.homeTeam());
                ps.setString(++i, r.awayTeam());
                ps.setString(++i, r.bodyJson());
            }
            assert i == rows.size() * COLUMNS;
            // The socket timeout bounds each read, but not a write blocked on a frozen server or replies
            // that trickle in. Past the deadline, abort closes the socket, so the blocked call throws,
            // the writer rewinds the batch, and the pool drops the connection.
            AtomicBoolean aborted = new AtomicBoolean();
            ScheduledFuture<?> watchdog = DEADLINES.schedule(() -> {
                aborted.set(true);
                try {
                    c.abort(Runnable::run);
                } catch (SQLException e) {
                    System.err.println("[History] could not abort a late insert: " + e.getMessage());
                }
            }, deadline.toMillis(), TimeUnit.MILLISECONDS);
            try {
                return ps.executeUpdate();
            } catch (SQLException e) {
                if (aborted.get()) throw new SQLException("history insert passed its " + deadline.toMillis() + " ms deadline", e);
                throw e;
            } finally {
                watchdog.cancel(false);
            }
        }
    }
}
