package checker;

import com.google.gson.JsonParser;
import service.impl.FollowStore;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** What the checker reads from Postgres: the run users' follows and their history for the run's games. */
public final class Snapshots {

    private Snapshots() {}

    public static List<FollowSnapshot> follows(DataSource db, List<String> userIds) {
        List<FollowSnapshot> out = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, user_id, kind, target_id, league, alerts, expires_at,"
                     + " created_at, updated_at FROM follows WHERE user_id = ANY(?) ORDER BY id")) {
            ps.setArray(1, uuids(c, userIds));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new FollowSnapshot(FollowStore.read(rs), rs.getTimestamp("updated_at").toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading follows failed", e);
        }
        return out;
    }

    public static List<HistoryRow> history(DataSource db, List<String> userIds, String gameMarker) {
        List<HistoryRow> out = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT user_id, message_id, game_id, created_at, body"
                     + " FROM notifications WHERE user_id = ANY(?) AND strpos(game_id, ?) > 0")) {
            ps.setArray(1, uuids(c, userIds));
            ps.setString(2, gameMarker);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HistoryRow(rs.getString("user_id"), rs.getString("message_id"), rs.getString("game_id"),
                            rs.getTimestamp("created_at").toInstant(), JsonParser.parseString(rs.getString("body")).getAsJsonObject()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading history failed", e);
        }
        return out;
    }

    /** Postgres's clock: follows' timestamps are its {@code now()}, so the freeze rule compares against it. */
    public static Instant dbNow(DataSource db) {
        try (Connection c = db.getConnection(); ResultSet rs = c.createStatement().executeQuery("SELECT now()")) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        } catch (SQLException e) {
            throw new IllegalStateException("reading Postgres time failed", e);
        }
    }

    static Array uuids(Connection c, List<String> ids) throws SQLException {
        return c.createArrayOf("uuid", ids.toArray());
    }
}
