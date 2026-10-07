package service.impl;

import catalog.AlertCatalog;
import catalog.AlertSetting;
import catalog.FollowKind;
import catalog.Teams;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import redis.clients.jedis.JedisPool;
import util.ApiError;
import util.ApiResult;

import javax.sql.DataSource;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Follows: Postgres is the truth, the sub:* sets are a copy (spec 5.3). A save commits to Postgres
 * first and then writes the follow's state to Redis. If the Redis write fails, the "built" marker is
 * deleted so the sets get rebuilt from Postgres; the other order could leave Redis holding a follow
 * whose commit failed.
 */
public final class FollowService {

    private static final Gson GSON = new Gson();
    private static final Type OPTIONS = new TypeToken<Map<String, Object>>() {}.getType();
    private static final Map<String, String> LEAGUE_NAMES = Map.of(
            "NFL", "National Football League", "NBA", "National Basketball Association",
            "MLB", "Major League Baseball", "NHL", "National Hockey League");
    private static final ApiError NOT_FOUND = ApiError.of(404, "not_found", "Not found.");

    private final DataSource db;
    private final FollowSets sets;
    private final AlertCatalog catalog;
    private final Teams teams;
    private final Function<String, Optional<Map<String, String>>> gameRecord;
    /** Set when even the marker delete failed (Redis unreachable); the rebuild check retries it. */
    private final AtomicBoolean rebuildOwed = new AtomicBoolean();

    public FollowService(DataSource db, JedisPool pool, AlertCatalog catalog, Teams teams,
                         Function<String, Optional<Map<String, String>>> gameRecord) {
        this(db, new FollowSets(pool), catalog, teams, gameRecord);
    }

    public FollowService(DataSource db, FollowSets sets, AlertCatalog catalog, Teams teams,
                         Function<String, Optional<Map<String, String>>> gameRecord) {
        this.db = db;
        this.sets = sets;
        this.catalog = catalog;
        this.teams = teams;
        this.gameRecord = gameRecord;
    }

    public ApiResult<Follow> create(String userId, String kindRaw, String targetId) {
        Optional<FollowKind> kind = FollowKind.parse(kindRaw);
        if (kind.isEmpty()) {
            return ApiResult.fail(ApiError.validation("target.kind", "invalid", "Follow a league, team, or game."));
        }
        if (targetId == null || targetId.isBlank()) {
            return ApiResult.fail(ApiError.validation("target.id", "required", "Say what to follow."));
        }
        String league = leagueOf(kind.get(), targetId);
        if (league == null || FollowStore.uuidOrNull(userId) == null) return ApiResult.fail(NOT_FOUND);

        Follow fresh = new Follow(UUID.randomUUID().toString(), userId, kind.get(), targetId, league,
                catalog.defaults(league, kind.get()), null, null);
        return save(userId, c -> {
            FollowStore.Saved saved = FollowStore.insertIfAbsent(c, fresh);
            if (!saved.created()) return new Outcome<>(ApiResult.ok(200, saved.follow()), null);
            return new Outcome<>(ApiResult.ok(201, saved.follow()), () -> sets.writeState(saved.follow()));
        });
    }

    public ApiResult<List<Follow>> list(String userId) {
        if (FollowStore.uuidOrNull(userId) == null) return ApiResult.ok(200, List.of());
        return read(c -> ApiResult.ok(200, FollowStore.list(c, userId)));
    }

    public ApiResult<Follow> get(String userId, String id) {
        if (FollowStore.uuidOrNull(userId) == null) return ApiResult.fail(NOT_FOUND);
        return read(c -> FollowStore.get(c, userId, id).map(f -> ApiResult.ok(200, f)).orElse(ApiResult.fail(NOT_FOUND)));
    }

    /** Only the alert types in the body change; each one replaces that alert's setting. */
    public ApiResult<Follow> patch(String userId, String id, JsonObject body) {
        JsonElement alertsEl = body == null ? null : body.get("alerts");
        if (alertsEl == null || !alertsEl.isJsonObject()) {
            return ApiResult.fail(ApiError.validation("alerts", "required", "Send the alerts to change."));
        }
        Map<String, AlertSetting> patch = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : alertsEl.getAsJsonObject().entrySet()) {
            AlertSetting s = parseSetting(e.getValue());
            if (s == null) {
                return ApiResult.fail(ApiError.validation("alerts." + e.getKey(), "invalid",
                        "Each alert needs enabled: true or false."));
            }
            patch.put(e.getKey(), s);
        }
        if (FollowStore.uuidOrNull(userId) == null) return ApiResult.fail(NOT_FOUND);

        return save(userId, c -> {
            Optional<Follow> current = FollowStore.get(c, userId, id);
            if (current.isEmpty()) return new Outcome<>(ApiResult.fail(NOT_FOUND), null);
            Follow f = current.get();
            for (Map.Entry<String, AlertSetting> e : patch.entrySet()) {
                Optional<String> problem = catalog.problem(f.league(), f.kind(), e.getKey(), e.getValue());
                if (problem.isPresent()) {
                    return new Outcome<>(ApiResult.fail(ApiError.validation("alerts." + e.getKey(), problem.get(),
                            "This follow can't have that alert setting.")), null);
                }
            }
            if (patch.isEmpty()) return new Outcome<>(ApiResult.ok(200, f), null);
            Follow merged = FollowStore.mergeAlerts(c, userId, id, patch).orElseThrow();
            return new Outcome<>(ApiResult.ok(200, merged), () -> sets.writeState(merged));
        });
    }

    public ApiResult<Void> delete(String userId, String id) {
        if (FollowStore.uuidOrNull(userId) == null) return ApiResult.fail(NOT_FOUND);
        return save(userId, c -> {
            Optional<Follow> gone = FollowStore.delete(c, userId, id);
            if (gone.isEmpty()) return new Outcome<>(ApiResult.fail(NOT_FOUND), null);
            return new Outcome<Void>(ApiResult.ok(204, null), () -> sets.removeAll(gone.get()));
        });
    }

    public boolean rebuildOwed() {
        return rebuildOwed.get();
    }

    public void clearRebuildOwed() {
        rebuildOwed.set(false);
    }

    /** The API's {@code Follow}: the row plus the target's display name. */
    public JsonObject toApi(Follow f) {
        JsonObject target = new JsonObject();
        target.addProperty("kind", f.kind().api());
        target.addProperty("id", f.targetId());
        target.addProperty("displayName", displayName(f));
        JsonObject alerts = new JsonObject();
        f.alerts().forEach((type, s) -> {
            JsonObject v = new JsonObject();
            v.addProperty("enabled", s.enabled());
            if (!s.options().isEmpty()) v.add("options", GSON.toJsonTree(s.options()));
            alerts.add(type, v);
        });
        JsonObject out = new JsonObject();
        out.addProperty("id", f.id());
        out.add("target", target);
        out.addProperty("leagueId", f.league());
        out.add("alerts", alerts);
        out.addProperty("expiresAt", f.expiresAt() == null ? null : f.expiresAt().toString());
        out.addProperty("createdAt", f.createdAt().toString());
        return out;
    }

    // --- the save path ---

    /** What a save answers, and the Redis write to run after its commit (null: nothing to write). */
    private record Outcome<T>(ApiResult<T> result, Runnable redis) {}

    @FunctionalInterface
    private interface TxWork<T> {
        Outcome<T> run(Connection c) throws Exception;
    }

    /**
     * Spec 5.3: locks (rebuild lock shared, then the user's), the Postgres transaction and commit, then
     * Redis, then unlock. The user lock is held until Redis is written, so one user's saves reach Redis
     * in commit order.
     */
    private <T> ApiResult<T> save(String userId, TxWork<T> work) {
        try (Connection c = db.getConnection()) {
            return SubLocks.withSaveLocks(c, userId, conn -> {
                Outcome<T> out;
                conn.setAutoCommit(false);
                try {
                    out = work.run(conn);
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
                if (out.redis() != null) writeRedis(out.redis());
                return out.result();
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("saving a follow failed", e);
        }
    }

    /** The follow is committed whatever happens here; a failure only means the sets must be rebuilt. */
    private void writeRedis(Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            System.err.println("[Follows] Redis update failed, asking for a rebuild: " + e.getMessage());
            try {
                sets.deleteMarker();
            } catch (RuntimeException e2) {
                System.err.println("[Follows] marker delete failed too, rebuild owed: " + e2.getMessage());
                rebuildOwed.set(true);
            }
        }
    }

    @FunctionalInterface
    private interface ReadWork<T> {
        ApiResult<T> run(Connection c) throws Exception;
    }

    private <T> ApiResult<T> read(ReadWork<T> work) {
        try (Connection c = db.getConnection()) {
            return work.run(c);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("reading follows failed", e);
        }
    }

    // --- targets ---

    /** The target's league, or null if there is no such league, team, or game. */
    private String leagueOf(FollowKind kind, String targetId) {
        return switch (kind) {
            case LEAGUE -> catalog.forLeague(targetId).isEmpty() ? null : targetId;
            case TEAM -> teams.find(targetId).map(Teams.Team::league).orElse(null);
            case GAME -> gameRecord.apply(targetId).map(r -> r.get("league")).filter(l -> !l.isBlank()).orElse(null);
        };
    }

    private String displayName(Follow f) {
        return switch (f.kind()) {
            case LEAGUE -> LEAGUE_NAMES.getOrDefault(f.targetId(), f.targetId());
            case TEAM -> teams.find(f.targetId()).map(Teams.Team::displayName).orElse(f.targetId());
            case GAME -> gameRecord.apply(f.targetId())
                    .filter(r -> r.get("awayTeam") != null && r.get("homeTeam") != null)
                    .map(r -> r.get("awayTeam") + " @ " + r.get("homeTeam"))
                    .orElse(f.targetId());
        };
    }

    /** {"enabled": bool, "options"?: object}, or null if the shape is wrong. */
    private static AlertSetting parseSetting(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        JsonElement enabled = o.get("enabled");
        if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean()) return null;
        JsonElement options = o.get("options");
        if (options != null && !options.isJsonNull() && !options.isJsonObject()) return null;
        Map<String, Object> opts = options == null || options.isJsonNull() ? Map.of() : GSON.fromJson(options, OPTIONS);
        return new AlertSetting(enabled.getAsBoolean(), opts);
    }
}
