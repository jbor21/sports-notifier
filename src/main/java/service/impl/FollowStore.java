package service.impl;

import catalog.AlertSetting;
import catalog.FollowKind;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** SQL for the {@code follows} table. Every method runs on the caller's connection, inside its locks. */
public final class FollowStore {

    /** A follow after an insert: {@code created} is false when the user already followed this target. */
    public record Saved(Follow follow, boolean created) {}

    private static final Gson GSON = new Gson();
    private static final Type ALERTS = new TypeToken<Map<String, Map<String, Object>>>() {}.getType();
    private static final String COLUMNS = "id, user_id, kind, target_id, league, alerts, expires_at, created_at";

    private FollowStore() {}

    public static Saved insertIfAbsent(Connection c, Follow f) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO follows (id, user_id, kind, target_id, league, alerts) VALUES (?, ?, ?, ?, ?, ?::jsonb)"
                        + " ON CONFLICT (user_id, kind, target_id) DO NOTHING RETURNING " + COLUMNS)) {
            ps.setObject(1, UUID.fromString(f.id()));
            ps.setObject(2, UUID.fromString(f.userId()));
            ps.setString(3, f.kind().api());
            ps.setString(4, f.targetId());
            ps.setString(5, f.league());
            ps.setString(6, alertsJson(f.alerts()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Saved(read(rs), true);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM follows WHERE user_id = ? AND kind = ? AND target_id = ?")) {
            ps.setObject(1, UUID.fromString(f.userId()));
            ps.setString(2, f.kind().api());
            ps.setString(3, f.targetId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Saved(read(rs), false);
            }
        }
    }

    public static Optional<Follow> get(Connection c, String userId, String id) throws SQLException {
        UUID followId = uuidOrNull(id);
        if (followId == null) return Optional.empty();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM follows WHERE id = ? AND user_id = ?")) {
            ps.setObject(1, followId);
            ps.setObject(2, UUID.fromString(userId));
            return one(ps);
        }
    }

    public static List<Follow> list(Connection c, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM follows WHERE user_id = ? ORDER BY created_at, id")) {
            ps.setObject(1, UUID.fromString(userId));
            List<Follow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
            return out;
        }
    }

    /** jsonb {@code ||} replaces each top-level key in the patch and keeps the rest: no read-modify-write in Java. */
    public static Optional<Follow> mergeAlerts(Connection c, String userId, String id, Map<String, AlertSetting> patch)
            throws SQLException {
        UUID followId = uuidOrNull(id);
        if (followId == null) return Optional.empty();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE follows SET alerts = alerts || ?::jsonb WHERE id = ? AND user_id = ? RETURNING " + COLUMNS)) {
            ps.setString(1, alertsJson(patch));
            ps.setObject(2, followId);
            ps.setObject(3, UUID.fromString(userId));
            return one(ps);
        }
    }

    public static Optional<Follow> delete(Connection c, String userId, String id) throws SQLException {
        UUID followId = uuidOrNull(id);
        if (followId == null) return Optional.empty();
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM follows WHERE id = ? AND user_id = ? RETURNING " + COLUMNS)) {
            ps.setObject(1, followId);
            ps.setObject(2, UUID.fromString(userId));
            return one(ps);
        }
    }

    static String alertsJson(Map<String, AlertSetting> alerts) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        alerts.forEach((type, s) -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("enabled", s.enabled());
            if (!s.options().isEmpty()) v.put("options", s.options());
            out.put(type, v);
        });
        return GSON.toJson(out);
    }

    static Follow read(ResultSet rs) throws SQLException {
        Map<String, Map<String, Object>> raw = GSON.fromJson(rs.getString("alerts"), ALERTS);
        Map<String, AlertSetting> alerts = new LinkedHashMap<>();
        raw.forEach((type, v) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> options = (Map<String, Object>) v.getOrDefault("options", Map.of());
            alerts.put(type, new AlertSetting(Boolean.TRUE.equals(v.get("enabled")), options));
        });
        Timestamp expires = rs.getTimestamp("expires_at");
        return new Follow(rs.getObject("id").toString(), rs.getObject("user_id").toString(),
                FollowKind.parse(rs.getString("kind")).orElseThrow(), rs.getString("target_id"), rs.getString("league"),
                alerts, expires == null ? null : expires.toInstant(), rs.getTimestamp("created_at").toInstant());
    }

    private static Optional<Follow> one(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(read(rs)) : Optional.empty();
        }
    }

    static UUID uuidOrNull(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
