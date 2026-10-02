package service.impl;

import com.google.gson.Gson;
import model.User;
import org.mindrot.jbcrypt.BCrypt;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;
import util.RedisKeys;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Signup and login against the account records in Redis. */
public final class AccountService {

    /** HTTP status plus either the account or an error message. */
    public record Result(int status, String userId, String username, String error) {
        static Result error(int status, String message) { return new Result(status, null, null, message); }
    }

    private static final int MIN_PASSWORD_CHARS = 8;
    /** bcrypt silently ignores everything past 72 bytes, so a longer password is refused, not cut short. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private static final Gson GSON = new Gson();
    private final JedisPool pool;

    public AccountService(JedisPool pool) {
        this.pool = pool;
    }

    /**
     * The username is claimed with SET NX, which checks and claims in one Redis command, so two
     * signups for the same name can't both win (the course code did GET, then SET, and could).
     *
     * The user record is written first and the name claimed second. If the process dies between
     * the two, what's left is a user record nobody can reach, which is harmless. The other order
     * could leave a name claimed forever with no account behind it.
     */
    public Result signup(String rawUsername, String password) {
        String username = normalize(rawUsername);
        if (username.isEmpty()) return Result.error(400, "username is required");
        if (password == null || password.length() < MIN_PASSWORD_CHARS) {
            return Result.error(400, "password must be at least 8 characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return Result.error(400, "password must be at most 72 bytes");
        }

        String userId = UUID.randomUUID().toString();
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(10));
        try (Jedis jedis = pool.getResource()) {
            jedis.set(RedisKeys.USER_PREFIX + userId,
                GSON.toJson(new User(userId, username, hash, System.currentTimeMillis())));
            String claimed = jedis.set(RedisKeys.USERNAME_PREFIX + username, userId, SetParams.setParams().nx());
            if (!"OK".equals(claimed)) {
                jedis.del(RedisKeys.USER_PREFIX + userId);
                return Result.error(409, "username already exists");
            }
            return new Result(200, userId, username, null);
        }
    }

    public Result login(String rawUsername, String password) {
        String username = normalize(rawUsername);
        if (username.isEmpty() || password == null) return Result.error(401, "invalid credentials");
        try (Jedis jedis = pool.getResource()) {
            String userId = jedis.get(RedisKeys.USERNAME_PREFIX + username);
            String raw = userId == null ? null : jedis.get(RedisKeys.USER_PREFIX + userId);
            if (raw == null) return Result.error(401, "invalid credentials");
            User user = GSON.fromJson(raw, User.class);
            if (!BCrypt.checkpw(password, user.getPasswordHash())) return Result.error(401, "invalid credentials");
            return new Result(200, user.getUserId(), user.getUsername(), null);
        }
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
