package service.impl;

import org.mindrot.jbcrypt.BCrypt;
import util.ApiError;
import util.ApiResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Pattern;

/** Signup, login, and settings against the {@code users} table. */
public final class AccountService {

    /** The API's {@code User}. Never carries the password hash. */
    public record UserView(String id, String username, String timezone, Instant createdAt) {}

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,20}$");
    private static final int MIN_PASSWORD_CHARS = 8;
    /** bcrypt silently ignores everything past 72 bytes, so a longer password is refused, not cut short. */
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final String UNIQUE_VIOLATION = "23505";
    private static final ApiError INVALID_CREDENTIALS =
            ApiError.of(401, "invalid_credentials", "Wrong username or password.");

    private final DataSource db;

    public AccountService(DataSource db) {
        this.db = db;
    }

    /**
     * The UNIQUE constraint on username is the race guard: of many simultaneous signups for one name,
     * one insert wins and the rest fail with a unique violation. No check-then-insert in Java.
     */
    public ApiResult<UserView> signup(String rawUsername, String password, String rawTimezone) {
        String username = normalize(rawUsername);
        if (!USERNAME.matcher(username).matches()) {
            return ApiResult.fail(ApiError.validation("username", "invalid",
                    "Username must be 3 to 20 characters: a-z, 0-9, or _."));
        }
        if (password == null || password.length() < MIN_PASSWORD_CHARS) {
            return ApiResult.fail(ApiError.validation("password", "too_short", "Password must be at least 8 characters."));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return ApiResult.fail(ApiError.validation("password", "too_long", "Password must be at most 72 bytes."));
        }
        String timezone = rawTimezone == null ? "UTC" : rawTimezone;
        if (!validTimezone(timezone)) return ApiResult.fail(badTimezone());

        String hash = BCrypt.hashpw(password, BCrypt.gensalt(10));
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users (id, username, password_hash, timezone) VALUES (?, ?, ?, ?)"
                             + " RETURNING id, username, timezone, created_at")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, username);
            ps.setString(3, hash);
            ps.setString(4, timezone);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return ApiResult.ok(201, view(rs));
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return ApiResult.fail(ApiError.of(409, "username_taken", "That username is taken."));
            }
            throw new IllegalStateException("signup failed", e);
        }
    }

    /** Wrong username and wrong password get the same answer, so the response doesn't say which names exist. */
    public ApiResult<UserView> login(String rawUsername, String password) {
        String username = normalize(rawUsername);
        if (username.isEmpty() || password == null) return ApiResult.fail(INVALID_CREDENTIALS);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, username, timezone, created_at, password_hash FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !BCrypt.checkpw(password, rs.getString("password_hash"))) {
                    return ApiResult.fail(INVALID_CREDENTIALS);
                }
                return ApiResult.ok(200, view(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("login failed", e);
        }
    }

    public ApiResult<UserView> get(String userId) {
        UUID id = uuidOrNull(userId);
        if (id == null) return ApiResult.fail(notFound());
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, username, timezone, created_at FROM users WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? ApiResult.ok(200, view(rs)) : ApiResult.fail(notFound());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading a user failed", e);
        }
    }

    public ApiResult<UserView> updateTimezone(String userId, String timezone) {
        if (timezone == null || !validTimezone(timezone)) return ApiResult.fail(badTimezone());
        UUID id = uuidOrNull(userId);
        if (id == null) return ApiResult.fail(notFound());
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET timezone = ? WHERE id = ? RETURNING id, username, timezone, created_at")) {
            ps.setString(1, timezone);
            ps.setObject(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? ApiResult.ok(200, view(rs)) : ApiResult.fail(notFound());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("updating a timezone failed", e);
        }
    }

    /** IANA names only. ZoneId.of would also accept offsets like "+05:00". */
    private static boolean validTimezone(String tz) {
        return ZoneId.getAvailableZoneIds().contains(tz);
    }

    private static ApiError badTimezone() {
        return ApiError.validation("timezone", "invalid", "Timezone must be an IANA name, like America/New_York.");
    }

    private static ApiError notFound() {
        return ApiError.of(404, "not_found", "Not found.");
    }

    private static UserView view(ResultSet rs) throws SQLException {
        return new UserView(rs.getObject("id").toString(), rs.getString("username"), rs.getString("timezone"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static UUID uuidOrNull(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
