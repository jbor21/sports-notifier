package service.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Reads notification history from Postgres (Phase 3.2 spec, 7.3). The history writer inserts; this
 * class reads, marks seen, clears, and deletes rows past 30 days.
 */
public final class NotificationHistory {

    /** One page: API {@code Notification} objects, newest first, and the cursor for the next page (null on the last). */
    public record Page(List<JsonObject> items, String nextCursor) {}

    private static final int UNREAD_CAP = 1000;

    private final DataSource db;

    public NotificationHistory(DataSource db) {
        this.db = db;
    }

    /**
     * Newest first. Pages by (created_at, message_id): a row inserted while someone pages never shifts
     * a page. Rows from before the user's last clear stay hidden, so a history catch-up after a
     * Postgres outage can't bring cleared alerts back.
     *
     * @throws IllegalArgumentException if {@code cursor} is not one this method returned
     */
    public Page list(String userId, String league, String team, String gameId, String cursor, int limit) {
        Cursor after = cursor == null || cursor.isEmpty() ? null : Cursor.decode(cursor);
        UUID user = uuidOrNull(userId);
        if (user == null) return new Page(List.of(), null);
        StringBuilder sql = new StringBuilder(
                "SELECT n.message_id, n.created_at, n.body FROM notifications n"
                        + " WHERE n.user_id = ? AND n.created_at > " + CLEARED_AT);
        if (league != null) sql.append(" AND n.league = ?");
        if (team != null) sql.append(" AND (n.home_team = ? OR n.away_team = ?)");
        if (gameId != null) sql.append(" AND n.game_id = ?");
        if (after != null) sql.append(" AND (n.created_at, n.message_id) < (?, ?)");
        sql.append(" ORDER BY n.created_at DESC, n.message_id DESC LIMIT ?");
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 0;
            ps.setObject(++i, user);
            ps.setObject(++i, user);
            if (league != null) ps.setString(++i, league);
            if (team != null) { ps.setString(++i, team); ps.setString(++i, team); }
            if (gameId != null) ps.setString(++i, gameId);
            if (after != null) { ps.setTimestamp(++i, Timestamp.from(after.createdAt())); ps.setString(++i, after.messageId()); }
            ps.setInt(++i, limit + 1);   // one more than asked says whether there is a next page
            List<JsonObject> items = new ArrayList<>();
            Cursor last = null;
            boolean more = false;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (items.size() == limit) { more = true; break; }
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();
                    JsonObject item = JsonParser.parseString(rs.getString("body")).getAsJsonObject();
                    item.addProperty("id", rs.getString("message_id"));
                    item.addProperty("createdAt", createdAt.toString());
                    items.add(item);
                    last = new Cursor(createdAt, rs.getString("message_id"));
                }
            }
            return new Page(items, more ? last.encode() : null);
        } catch (SQLException e) {
            throw new IllegalStateException("reading history failed", e);
        }
    }

    /** Rows newer than both the last "seen" and the last clear, counted up to 1000 (the badge shows 999+). */
    public int unreadCount(String userId) {
        UUID user = uuidOrNull(userId);
        if (user == null) return 0;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM (SELECT 1 FROM notifications n WHERE n.user_id = ?"
                        + " AND n.created_at > GREATEST(" + LAST_SEEN_AT + ", " + CLEARED_AT + ") LIMIT " + UNREAD_CAP + ") x")) {
            ps.setObject(1, user);
            ps.setObject(2, user);
            ps.setObject(3, user);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("counting unread failed", e);
        }
    }

    public void markSeen(String userId) {
        UUID user = uuidOrNull(userId);
        if (user == null) return;
        update("INSERT INTO notification_reads (user_id, last_seen_at) VALUES (?, now())"
                + " ON CONFLICT (user_id) DO UPDATE SET last_seen_at = now()", user);
    }

    /** Deletes the user's rows and records the time, so rows the history writer inserts later for older alerts stay hidden. */
    public void clear(String userId) {
        UUID user = uuidOrNull(userId);
        if (user == null) return;
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO notification_reads (user_id, cleared_at) VALUES (?, now())"
                                + " ON CONFLICT (user_id) DO UPDATE SET cleared_at = now()")) {
                    ps.setObject(1, user);
                    ps.executeUpdate();
                }
                // Every row, not only those before now(): created_at is Stage 1's clock, which can run a
                // little ahead of Postgres's. Rows the writer inserts later are hidden by cleared_at.
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM notifications WHERE user_id = ?")) {
                    ps.setObject(1, user);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("clearing history failed", e);
        }
    }

    /** Deletes rows older than {@code age}, {@code batchSize} at a time, so no single delete holds a big lock. */
    public int deleteOlderThan(Duration age, int batchSize) {
        int total = 0;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM notifications WHERE ctid IN"
                        + " (SELECT ctid FROM notifications WHERE created_at < ? LIMIT ?)")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(age)));
            ps.setInt(2, batchSize);
            int n;
            do {
                n = ps.executeUpdate();
                total += n;
            } while (n == batchSize);
            return total;
        } catch (SQLException e) {
            throw new IllegalStateException("deleting old history failed", e);
        }
    }

    private static final String LAST_SEEN_AT =
            "COALESCE((SELECT r.last_seen_at FROM notification_reads r WHERE r.user_id = ?), 'epoch'::timestamptz)";
    private static final String CLEARED_AT =
            "COALESCE((SELECT r.cleared_at FROM notification_reads r WHERE r.user_id = ?), 'epoch'::timestamptz)";

    private void update(String sql, UUID user) {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, user);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("updating notification_reads failed", e);
        }
    }

    private static UUID uuidOrNull(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Where a page ended: base64url of {@code <created_at in microseconds>:<message_id>}. */
    private record Cursor(Instant createdAt, String messageId) {

        String encode() {
            long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((micros + ":" + messageId).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String s) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
                int colon = raw.indexOf(':');
                if (colon <= 0 || colon == raw.length() - 1) throw new IllegalArgumentException("not a cursor");
                long micros = Long.parseLong(raw.substring(0, colon));
                return new Cursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), raw.substring(colon + 1));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("not a cursor: " + s, e);
            }
        }
    }
}
