# Phase 3.2 Postgres Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Postgres becomes the source of truth for accounts, follows (decision-15 switches), and notification history; Redis keeps sessions, live score, rebuilt `sub:*` sets, and pub/sub.

**Architecture:** One Postgres pod in the chart (host folder on the laptop). user-pref owns `users` and `follows` and keeps per-(target, alert) Redis sets in step with Postgres (commit first, then Redis; marker + rebuild on failure). Stage 1 maps the three 3.1 play types onto catalog switches and waits while the sets are rebuilt. A new history writer (its own consumer group on `notifications`) batch-inserts history; the notification service serves it.

**Tech Stack:** Java 17, Javalin 6.1.6, Jedis 4.4.7, kafka-clients 4.3.1, PostgreSQL 17 (`postgres:17`), JDBC driver `org.postgresql:postgresql`, HikariCP, Flyway (`flyway-core` + `flyway-database-postgresql`), Testcontainers 2.0.5 `testcontainers-postgresql`. Pin each new dependency to its current release on Maven Central at Task 1.

**Spec:** `docs/superpowers/specs/2026-10-07-phase-3-2-postgres-design.md` (read it with this plan; section numbers below refer to it). Parent: `DESIGN.md`.

## Global Constraints

- All `/api/v1` errors use the API `Error` shape: `{"error":{"code","message","fields"?}}`. Codes used: `validation_failed`, `username_taken`, `invalid_credentials`, `unauthenticated`, `not_found`, `rate_limited`.
- Config through env vars with defaults: `PG_URL` (`jdbc:postgresql://postgres:5432/sportsnotifier`), `PG_USER` (`sportsnotifier`), `PG_PASSWORD`, `PG_POOL_SIZE` (5), `SUB_CHECK_MS` (30000), `SUB_FULL_REBUILD_HOURS` (24), `HISTORY_PORT` (8088).
- Redis key prefixes only in `util/RedisKeys.java`. New: `SUB_BUILT = "sub:built"`, `SUB_TMP_PREFIX = "sub:tmp:"`; set keys are `sub:<kind>:<targetId>:<ALERT_TYPE>`.
- Team ids only from `feed/TeamIds.of(league, abbreviation)` → `"<league lower>-<abbr lower>"`.
- Advisory locks: `SUB_LOCK = 3_200_001L` (bigint form); user lock `(USER_LOCK_NS = 32, hashtext(userId))` (two-int form). Order: `SUB_LOCK` shared, then user lock. Release with `pg_advisory_unlock_all()` in `finally`.
- Migrations only add. One file per task that needs schema; Task 1 creates all four tables (spec section 2) as `V1__core.sql`.
- Every Jedis handle in try-with-resources; every JDBC `Connection`/`Statement` too.
- Each task keeps `make test` and `make cluster up smoke` green; the old path stays until its replacement lands.
- Tests: Redis on localhost:6379 DB 1 (flushed), Postgres from `testsupport/PostgresTestSupport` (truncated per test), Kafka from `KafkaTestSupport`.

## Review Focus

1. **Usernames in mixed case** ("Jacob"): signup and login lower-case first, then validate against `^[a-z0-9_]{3,20}$`; "Jacob" logs into "jacob". Test in Task 3.
2. **Timezones that aren't IANA names** ("EST", "+05:00", ""): `422 validation_failed`, `fields.timezone = "invalid"`; `ZoneId.getAvailableZoneIds().contains(tz)`, not `ZoneId.of`. Test in Task 3.
3. **Malformed `cursor` or `limit` on `GET /notifications`** (not base64, wrong shape, limit 0 or 101 or "abc"): `422`, never `500`. Test in Task 8.
4. **Malformed `PATCH /follows` bodies** (`alerts` missing or not an object, a setting without `enabled`, `innings: ["9"]` or `[0]`): `422` with the field named; `{"alerts":{}}` is `200` and changes nothing. Test in Task 4.
5. **Deleting or patching a follow that another request just deleted**: `404`, and the user is in no set for it afterwards. Test in Task 4.

---

### Task 1: Postgres in the cluster and in tests

**Files:**
- Modify: `pom.xml` (driver, HikariCP, Flyway core + postgres module; test: `testcontainers-postgresql`)
- Create: `src/main/java/util/Db.java`, `src/main/resources/db/migration/V1__core.sql`
- Create: `src/test/java/testsupport/PostgresTestSupport.java`, `src/test/java/util/DbTest.java`
- Create: `deploy/helm/sports-notifier/templates/postgres.yaml`
- Modify: `values.yaml`, `values-local.yaml`, `templates/_helpers.tpl`, `templates/java-services.yaml`, `templates/network-policies.yaml`, `Makefile`
- Create: `scripts/persistence-check.sh`
- Modify: `src/main/java/service/impl/UserPreferenceServiceMain.java` (wait for Postgres, migrate)

**Interfaces:**
- Produces: `Db.fromEnv(String service) -> HikariDataSource` (waits up to 2 min for Postgres, then exits like `awaitRedisOrExit`); `Db.migrate(DataSource)`; `Db.create(String url, String user, String password, int poolSize) -> HikariDataSource`.
- Produces: `PostgresTestSupport.dataSource() -> DataSource` (one `postgres:17` container per JVM, migrated once), `PostgresTestSupport.truncateAll()`, `pause()`, `unpause()`, `unpauseIfPaused()` (same shape as `KafkaTestSupport`).
- Produces (chart): service flag `postgres: true` adds `PG_URL`, `PG_USER`, `PG_PASSWORD` (from the Secret) via `sports-notifier.postgresEnv`; set it on `user-pref` and `notification`.

- [ ] **Step 1: Write `DbTest`**: `migrateCreatesTables` (after `Db.migrate`, `information_schema.tables` has `users`, `follows`, `notifications`, `notification_reads`); `migrateTwiceIsANoop`; `twoMigrationsAtOnceBothSucceed` (two threads, barrier, separate pools on a fresh database).
- [ ] **Step 2: Run** `mvn -q test -Dtest=DbTest` → FAIL (no `Db`).
- [ ] **Step 3: Implement** `Db`, `V1__core.sql` (exactly spec section 2), `PostgresTestSupport`. Flyway location `classpath:db/migration`.
- [ ] **Step 4: Run** `mvn -q test -Dtest=DbTest` → PASS.
- [ ] **Step 5: Chart.** `postgres.yaml`: Service `postgres:5432`; StatefulSet (1 replica, hard-coded, comment as Redis) with `PGDATA=/var/lib/postgresql/data/pgdata`, `POSTGRES_DB=sportsnotifier`, `POSTGRES_USER=sportsnotifier`, password from Secret `postgres`, readiness/liveness `pg_isready -U sportsnotifier`; volume = `hostPath {path: .Values.postgres.hostPath, type: DirectoryOrCreate}` when `hostPath` is set, else a `volumeClaimTemplates` of `.Values.postgres.storage`. Secret `postgres`: `.Values.postgres.password` if set, else the existing Secret's value via `lookup`, else `randAlphaNum 32`. `values.yaml`: `postgres: {image: postgres:17, storage: 2Gi, hostPath: "", password: ""}`. `values-local.yaml`: `hostPath: /var/lib/sportsnotifier/postgres`, `password: local-dev-only-password` with a comment saying why it is fixed (spec section 1). Network policy `postgres-from-db-users`: ingress on 5432 only from pods labeled `app.kubernetes.io/name` in `user-pref`, `notification`, `history`.
- [ ] **Step 6: Makefile.** `PG_DIR ?= $(HOME)/.sportsnotifier/postgres`; `cluster` runs `mkdir -p $(PG_DIR)` and creates with `--volume "$(PG_DIR):/var/lib/sportsnotifier/postgres@server:0"`. When the cluster already exists, check the mount with `docker inspect k3d-$(CLUSTER)-server-0` and fail with "Recreate the cluster: make down && make cluster" if it is missing.
- [ ] **Step 7: user-pref** calls `Db.fromEnv("user-pref")` and `Db.migrate` at startup (accounts move in Task 3).
- [ ] **Step 8: `scripts/persistence-check.sh`** (laptop only): `make down && make cluster up`; assert the postgres pod is Ready, `kubectl logs statefulset/postgres` has no `Permission denied` / `Operation not permitted`, and `psql -c '\dt'` lists the four tables; write a marker row into a temp table, repeat `make down && make cluster up`, assert the row is still there. Tasks 3 and 9 extend it.
- [ ] **Step 9: Verify**: `make test` green; `make down && make cluster up smoke` green; `scripts/persistence-check.sh` passes. If Postgres cannot own the mounted folder, add `securityContext.runAsUser/fsGroup` matching the folder and re-run (spec section 1).
- [ ] **Step 10: Commit** `git commit -m "Postgres in the chart, on a host folder on the laptop; Flyway schema"`

### Task 2: Alert catalog, team list, team ids

**Files:**
- Create: `src/main/resources/catalog/alerts.json`, `src/main/resources/catalog/teams.json`, `scripts/fetch-teams.py`
- Create: `src/main/java/catalog/AlertCatalog.java`, `src/main/java/catalog/Teams.java`, `src/main/java/catalog/FollowKind.java`, `src/main/java/catalog/AlertSetting.java`, `src/main/java/feed/TeamIds.java`
- Test: `src/test/java/catalog/AlertCatalogTest.java`, `src/test/java/catalog/TeamsTest.java`, `src/test/java/feed/TeamIdsTest.java`

**Interfaces:**
- Produces: `enum FollowKind { LEAGUE, TEAM, GAME }` with `static Optional<FollowKind> parse(String)` (API values `league|team|game`).
- Produces: `record AlertSetting(boolean enabled, Map<String, Object> options)`.
- Produces: `record AlertType(String type, String name, String description, String category, Set<FollowKind> appliesTo, Map<FollowKind, Boolean> defaults, List<Map<String,String>> options, String precision, String availability)`.
- Produces: `AlertCatalog.load()`; `List<AlertType> forLeague(String league)`; `Map<String, AlertSetting> defaults(String league, FollowKind kind)` (every type in `appliesTo` for that kind, enabled per `defaults`); `Optional<String> problem(String league, FollowKind kind, String type, AlertSetting s)` → `"unknown_type"`, `"not_for_this_follow"`, `"bad_option"`, or empty; `List<String> scoringTypes(String league)` (category `scoring`).
- Produces: `Teams.load()`; `Optional<Team> find(String teamId)`; `record Team(String id, String league, String abbreviation, String displayName)`. Leagues: `NFL`, `NBA`, `MLB`, `NHL`.
- Produces: `TeamIds.of(String league, String abbreviation) -> String`.

- [ ] **Step 1: Write tests.** `AlertCatalogTest.matchesDecision15`: one expected row per (league, type) transcribed from `DESIGN.md` decision 15's tables (every-sport table + each sport's table): `appliesTo` and defaults for team/league/game, `coming_soon` for rows marked `?`, `DAILY_SUMMARY` `coming_soon`, `FINAL` league default `true`. `scoringTypesPerLeague`: NFL `[TOUCHDOWN, TRY_RESULT, FIELD_GOAL, SAFETY]`, MLB `[RUN_SCORED]`, NHL `[GOAL]`, NBA `[]`. `problemCodes`: unknown type, `DAILY_SUMMARY` on a team follow, `END_OF_INNING` innings `[0]`, `[10]`, `["9"]` → `bad_option`; `[3,6,9]` and `[]` fine. `TeamsTest`: `mlb-chw` is "Chicago White Sox"; `nba-lal`, `nba-bos`, `mlb-bos`, `nhl-bos` all exist and differ; every league has its full team count (NFL 32, NBA 30, MLB 30, NHL 32). `TeamIdsTest`: `("MLB","CHW")` → `mlb-chw`.
- [ ] **Step 2: Run** `mvn -q test -Dtest='AlertCatalogTest,TeamsTest,TeamIdsTest'` → FAIL.
- [ ] **Step 3: Implement.** `fetch-teams.py` reads `https://site.web.api.espn.com/apis/site/v2/sports/<path>/teams` with the honest user agent from `values.yaml` and writes `teams.json` (sorted by league then id); run it once and commit the output. Transcribe `alerts.json` from decision 15. Loaders read the classpath once; parse failures throw at startup.
- [ ] **Step 4: Run** the three tests → PASS.
- [ ] **Step 5: Commit** `git commit -m "Alert catalog and team list as resource files; league-qualified team ids"`

### Task 3: Accounts in Postgres and `/api/v1/auth`, `/api/v1/me`

**Files:**
- Rewrite: `src/main/java/service/impl/AccountService.java` (Postgres)
- Create: `src/main/java/util/ApiError.java`, `src/main/java/util/ApiResult.java`, `src/main/java/gateway/ApiV1Routes.java`
- Modify: `UserPreferenceServiceMain.java`, `GatewayMain.java` (register `ApiV1Routes`; remove `/api/signup`, `/api/login`, `/api/logout`, `/api/me`), `scripts/smoke-test.sh`, `scripts/persistence-check.sh`
- Test: `src/test/java/service/impl/AccountServiceTest.java` (rewrite on Postgres), `src/test/java/gateway/ApiV1AuthTest.java`

**Interfaces:**
- Consumes: `Db`, `PostgresTestSupport`.
- Produces: `record ApiError(int status, String code, String message, Map<String,String> fields)` with `String toJson()` and `static ApiError validation(String field, String problem, String message)`; `record ApiResult<T>(int status, T body, ApiError error)` in `util/ApiResult.java` with `static <T> ApiResult<T> ok(int status, T body)` and `static <T> ApiResult<T> fail(ApiError e)`. Every service method that answers HTTP returns it.
- Produces: `AccountService(DataSource)`; `ApiResult<UserView> signup(String username, String password, String timezone)`; `ApiResult<UserView> login(String username, String password)`; `ApiResult<UserView> get(String userId)`; `ApiResult<UserView> updateTimezone(String userId, String timezone)`; `record UserView(String id, String username, String timezone, Instant createdAt)` (serializes as the API `User`).
- Produces (user-pref HTTP): `POST /users` (201), `POST /users/login`, `GET /users/{id}`, `PATCH /users/{id}`; bodies and errors in API shape.
- Produces: `ApiV1Routes.register(Javalin app, JedisPool pool, LoginThrottle throttle, String userPrefUrl, String notificationUrl)` and `static String requireUser(JedisPool, Context)` (null after writing `401 unauthenticated`). Later tasks add their routes here.

- [ ] **Step 1: Write `AccountServiceTest`**: `signupReturns201WithUser`; `twentySimultaneousSignupsOneWins` (barrier; exactly one 201, nineteen 409 `username_taken`); `passwordTooShort`/`tooLongIn72Bytes` (`fields.password` `too_short`/`too_long`; 72 bytes of multibyte chars accepted, 73 refused); `usernameRules` ("ab", 21 chars, "a-b" → 422 `fields.username`); `mixedCaseUsernameIsLowerCased` (signup "Jacob", login "JACOB" works, user is "jacob"); `timezoneRules` (missing → `UTC`; "EST", "+05:00", "" → 422 `fields.timezone = invalid`; "America/Chicago" ok); `wrongUserAndWrongPasswordLookAlike` (same status and body); `updateTimezone`.
- [ ] **Step 2: Run** `mvn -q test -Dtest=AccountServiceTest` → FAIL.
- [ ] **Step 3: Implement** `AccountService` on Postgres: `INSERT … ` and map unique violation (SQLState `23505`) to 409; bcrypt cost 10 as today. user-pref routes call it.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Write `ApiV1AuthTest`** (Javalin on a random port, user-pref stubbed by a second Javalin, real Redis): signup sets `sid` and returns 201; login throttle returns `429 rate_limited` with `Retry-After` in the Error shape, counted before the check (existing `LoginThrottleTest` stays); `GET /api/v1/me` without a session → `401 unauthenticated`; logout → 204 and the session is gone; `PATCH /me` passes through 422.
- [ ] **Step 6: Implement** `ApiV1Routes` auth and me; remove the four old routes; `index.html` untouched.
- [ ] **Step 7: Run** `mvn -q test` → PASS.
- [ ] **Step 8: Smoke and persistence.** `smoke-test.sh` signs up and logs in via `/api/v1/auth/*` (the rest of the script still uses `/api/preferences` and `/api/history`, which work with the same session). `persistence-check.sh` signs up a user before the rebuild and logs in with the same password after it.
- [ ] **Step 9: Verify** `make cluster up smoke` and `scripts/persistence-check.sh` → PASS.
- [ ] **Step 10: Commit** `git commit -m "Accounts in Postgres behind /api/v1/auth and /api/v1/me"`

### Task 4: Follows API and the save path

**Files:**
- Create: `src/main/java/service/impl/Follow.java`, `FollowService.java`, `FollowStore.java`, `FollowSets.java`, `SubLocks.java`
- Modify: `UserPreferenceServiceMain.java` (routes), `gateway/ApiV1Routes.java` (`/api/v1/follows*`), `util/RedisKeys.java`
- Test: `src/test/java/service/impl/FollowServiceTest.java`, `FollowSaveConcurrencyTest.java`, `FollowSetsTest.java`

**Interfaces:**
- Consumes: `AlertCatalog`, `Teams`, `FollowKind`, `AlertSetting`, `TeamIds`, `ApiError`, `Db`.
- Produces: `record Follow(String id, String userId, FollowKind kind, String targetId, String league, Map<String, AlertSetting> alerts, Instant expiresAt, Instant createdAt)`; JSON as the API `Follow` (adds `target.displayName`).
- Produces: `FollowStore(DataSource)`: `Optional<Follow> insertIfAbsent(Connection, Follow)` (returns existing on conflict with a flag), `Optional<Follow> get(Connection, String userId, String id)`, `List<Follow> list(Connection, String userId)`, `Optional<Follow> mergeAlerts(Connection, String userId, String id, Map<String, AlertSetting> patch)` (one `UPDATE … SET alerts = alerts || ?::jsonb … RETURNING *`), `Optional<Follow> delete(Connection, String userId, String id)` (`DELETE … RETURNING *`).
- Produces: `FollowSets(JedisPool)`: `static String key(FollowKind, String targetId, String type)`; `void writeState(Follow f)` (SADD for enabled, SREM for disabled, checked pipeline); `void removeAll(Follow f)`.
- Produces: `SubLocks`: `static <T> T withSaveLocks(Connection c, String userId, SqlWork<T> work)` (takes `SUB_LOCK` shared, then user lock; `pg_advisory_unlock_all()` in `finally`); `static boolean withRebuildLock(Connection c, SqlWork<Boolean> work)`.
- Produces: `FollowService(DataSource, JedisPool, AlertCatalog, Teams, Function<String, Optional<Map<String,String>>> gameRecord)`: `ApiResult<Follow> create(String userId, String kindRaw, String targetId)`, `ApiResult<List<Follow>> list(String userId)`, `ApiResult<Follow> get(String userId, String id)`, `ApiResult<Follow> patch(String userId, String id, JsonObject body)`, `ApiResult<Void> delete(String userId, String id)`; `boolean rebuildOwed()` and `void clearRebuildOwed()` (the in-memory flag, used by Task 5).
- Produces: `RedisKeys.SUB_BUILT`, `RedisKeys.SUB_TMP_PREFIX`.

- [ ] **Step 1: Write `FollowServiceTest`**: `createFillsCatalogDefaults` (team `mlb-chw`: `GAME_START`, `RUN_SCORED`, `FINAL` on; `LEAD_CHANGE` off; displayName "Chicago White Sox"); `followTwiceReturns200SameId`; `unknownTeam404` (`mlb-xyz`); `unknownGame404` (no `game:<id>`); `gameFollowDisplayName` ("CLE @ CHW" from the record); `otherUsersFollowIs404` (get, patch, delete); `patchReplacesOnlyGivenTypes`; `patchProblems` (Review Focus 4: each malformed body → 422 naming the field; `{"alerts":{}}` → 200 unchanged); `deleteThenPatchOrDelete404` (Review Focus 5: a delete and a patch race on one follow, 100 iterations; the loser gets 404 and afterwards the user is in no `sub:*` set for that target).
- [ ] **Step 2: Write `FollowSetsTest`**: after create, the user is in exactly the sets of enabled switches (`sub:team:mlb-chw:GAME_START` …) and in none of the disabled; patch moves them; delete removes all; `writeState` twice leaves the same sets.
- [ ] **Step 3: Write `FollowSaveConcurrencyTest`**: `redisFailureAfterCommitDeletesMarker` (a `FollowSets` whose `writeState` throws once: Postgres has the follow, response is 2xx, `sub:built` is gone); `redisUnreachableSetsRebuildOwed` (marker delete also throws: `rebuildOwed()` true); `twoConcurrentPatchesBothLand` (200 iterations: two threads behind a barrier PATCH different types of one follow; afterwards Postgres has both changes and the Redis sets equal `writeState` of the Postgres row); `saveWaitsForRebuildLock` (hold `withRebuildLock` in one thread; a save blocks until it is released).
- [ ] **Step 4: Run** the three tests → FAIL.
- [ ] **Step 5: Implement** in this order: `SubLocks`, `FollowStore`, `FollowSets`, `FollowService` (save path exactly spec section 5.3, steps 1–6; answer from the committed row; log Redis failures). Routes on user-pref under `/users/{userId}/follows…`; gateway `/api/v1/follows…` with `requireUser`.
- [ ] **Step 6: Run** `mvn -q test` → PASS.
- [ ] **Step 7: Commit** `git commit -m "Follows in Postgres with per-alert Redis sets; per-user save lock"`

### Task 5: Rebuilding the sets, the marker, game-follow expiry

**Files:**
- Create: `src/main/java/service/impl/SubRebuilder.java`, `GameFollowExpiry.java`
- Modify: `UserPreferenceServiceMain.java` (schedule both)
- Test: `src/test/java/service/impl/SubRebuilderTest.java`, `GameFollowExpiryTest.java`

**Interfaces:**
- Consumes: `FollowStore`, `FollowSets.key`, `SubLocks.withRebuildLock`, `FollowService.rebuildOwed()`.
- Produces: `SubRebuilder(DataSource, JedisPool, BooleanSupplier rebuildOwed, Runnable clearOwed)` (user-pref passes `followService::rebuildOwed`, `followService::clearRebuildOwed`): `boolean checkOnce()` (retries an owed marker delete; rebuilds if `sub:built` is missing; true if it rebuilt), `void rebuildNow(boolean force)`, `void start(ScheduledExecutorService, long checkMs, long fullRebuildHours)` (first check at once, then every `checkMs`).
- Produces: `GameFollowExpiry(DataSource, JedisPool, FollowService)`: `int runOnce(Instant now)`.

- [ ] **Step 1: Write `SubRebuilderTest`**: `rebuildMatchesPostgres` (seed follows directly in Postgres; empty Redis; `checkOnce()` → sets equal what `writeState` gives for every row; `sub:built` set); `rebuildDeletesStaleNewShapeSets` (a set for an unfollowed target is gone); `markerPresentNoRebuild`; `unfollowDuringRebuildStaysUnfollowed` (hook pauses the rebuild after reading; unfollow starts and blocks; resume; user not in the set); `twoRebuildersOneRuns` (two instances, marker missing, both call `checkOnce` → exactly one rebuild); `stage1NeverSeesEmptySetForFollowedTarget` (a reader thread polls `SCARD` of a followed key throughout a forced rebuild; never 0); `owedMarkerDeleteRetried`; `expiredFollowsSkipped`.
- [ ] **Step 2: Write `GameFollowExpiryTest`**: final game → `expires_at = now + 1 day`; past `expires_at` → deleted and out of its sets; record gone and follow older than 8 days → deleted; record gone and newer → kept; live game → untouched.
- [ ] **Step 3: Run** → FAIL.
- [ ] **Step 4: Implement** per spec 5.4 and 5.5. Stream follows with `setFetchSize(1000)` in a read-only transaction ordered by `(kind, target_id)`; build under `sub:tmp:<buildId>:…` then `RENAME`; cleanup `SCAN`s `sub:league:*`, `sub:team:*`, `sub:game:*` and deletes keys with four `:`-separated parts not produced by this build (3.1 keys have three parts and are left for Task 6). Daily rebuild uses `force=true`. Schedule in user-pref main.
- [ ] **Step 5: Run** `mvn -q test` → PASS.
- [ ] **Step 6: Commit** `git commit -m "Rebuild sub sets from Postgres when the marker is missing, and daily; expire game follows"`

### Task 6: Stage 1 on the new sets, and waiting for them

**Files:**
- Create: `src/main/java/service/impl/AlertMapping.java`, `src/main/java/model/NotificationBody.java`
- Modify: `Audience.java`, `Stage1Worker.java`, `Stage1WorkerMain.java`, `model/NotificationMessage.java`, `SubRebuilder.java` (cleanup also deletes 3.1 keys `sub:team:<x>`, `sub:game:<x>`, `sub:league:<x>`, `sub:notif:*`), `util/RedisKeys.java` (remove `SUB_NOTIF_PREFIX`)
- Delete: `UserPreferenceServiceImpl.java`, `service/UserPreferenceService.java`, `model/UserPreferences.java`, user-pref `/prefs/*` routes, gateway `/api/preferences`, and their tests
- Modify: `scripts/smoke-test.sh` (follow `nba-lal` via `/api/v1/follows`)
- Test: `src/test/java/service/impl/AlertMappingTest.java`, `AudienceTest.java` (rewrite), `Stage1WorkerTest.java` (extend), `Stage1WaitsForSetsTest.java`

**Interfaces:**
- Consumes: `AlertCatalog.scoringTypes`, `TeamIds.of`, `FollowSets.key`, `RedisKeys.SUB_BUILT`.
- Produces: `AlertMapping(AlertCatalog)`: `List<String> switchesFor(String league, String eventType)`; `String headlineType(String league, String eventType)` (table in spec section 6; NFL score `SCORE`).
- Produces: `Audience.forEachPage(GameEvent play, List<String> switches, Consumer<List<String>> page)` (union of the four targets × switches; empty switches → no calls).
- Produces: `NotificationBody.of(GameEvent play, String headlineType)` → the API `Notification` object (`kind: game_moment`, headline `{type, text: description}`, `game.home/away.team.id` via `TeamIds`, scores, `detectedAt`); `NotificationMessage` gains `JsonObject notification` set by `of(...)`.
- Produces: `Stage1Worker(..., BooleanSupplier setsReady)` (main passes `EXISTS sub:built`); `boolean isWaitingForSets()`.

- [ ] **Step 1: Write `AlertMappingTest`** (spec section 6 table, each league, incl. NBA score → `[]`) and the rewritten `AudienceTest` (`fieldGoalOnlyNflFollowerGetsScore`, `bothTeamsFollowerOnce`, `nbaBosFollowerGetsNothingFromMlbBos`, `gameAndLeagueTargets`).
- [ ] **Step 2: Write `Stage1WaitsForSetsTest`** with real Kafka and Redis: follow seeded, `sub:built` absent, `max.poll.interval.ms=7000`, `session.timeout.ms=6000`, a `ConsumerRebalanceListener` counting revocations; send a play; for 15 s assert no record on `notifications`, the group's committed offset for the play's lane unchanged, `isWaitingForSets()` true, `LoopHealth.isHealthy()` true with max silence 3 s, revocations 0. Then set `sub:built`: within 10 s exactly one notification for the follower; offset committed. Second case: marker missing and a second worker joins (rebalance): new lanes are paused too and nothing is produced until the marker returns.
- [ ] **Step 3: Run** → FAIL.
- [ ] **Step 4: Implement** `AlertMapping`, `NotificationBody`, `Audience` change, and in `Stage1Worker.pollOnce`: if a batch has records and `!setsReady`, `Offsets.rewindTo(consumer, records)`, `consumer.pause(consumer.assignment())`, set waiting, return 0; while waiting, at the top of `pollOnce`, if `setsReady` → `consumer.resume(consumer.paused())` and clear waiting, else `consumer.pause(consumer.assignment())` before polling; warn every 30 s. `run()` is unchanged (it already beats every loop).
- [ ] **Step 5: Remove** the 3.1 preference path (files above), extend the rebuild cleanup, update the smoke test.
- [ ] **Step 6: Run** `mvn -q test` → PASS; `make down && make cluster up smoke` → PASS (fresh Redis: Stage 1 waits until user-pref's first rebuild, then delivers).
- [ ] **Step 7: Commit** `git commit -m "Stage 1 maps plays onto catalog switches and waits while the sets are rebuilt"`

### Task 7: The history writer

**Files:**
- Create: `src/main/java/service/impl/HistoryWriter.java`, `HistoryStore.java`, `HistoryWriterMain.java`
- Modify: `app/Launcher.java` (`history`), `values.yaml` (`history: {replicas: 1, port: 8088, portEnv: HISTORY_PORT, clusterService: false, kafka: true, postgres: true}`), `util/Topics.java` (group `history`)
- Test: `src/test/java/service/impl/HistoryWriterTest.java`

**Interfaces:**
- Consumes: `NotificationMessage` (with `notification`), `Offsets.rewindTo`, `LoopHealth`, `Db`.
- Produces: `HistoryStore(DataSource)`: `int insertBatch(List<HistoryRow> rows)` (one multi-row `INSERT … ON CONFLICT (user_id, message_id) DO NOTHING`, returns rows inserted); `record HistoryRow(String userId, String messageId, Instant createdAt, String league, String gameId, String homeTeam, String awayTeam, String bodyJson)`.
- Produces: `HistoryWriter(Consumer<String,String>, HistoryStore)`: `int pollOnce(Duration)`, `run(LoopHealth)`, `stop()`, `close()` — same shape as `Stage2Worker`. `createdAt = Instant.ofEpochMilli(record.timestamp())`.

- [ ] **Step 1: Write `HistoryWriterTest`**: `insertsOneRowPerMessage`; `createdAtIsRecordTimestamp`; `rereadBatchInsertsNothing` (rewind and poll again → 0 inserted, row count unchanged); `redeliveredDuplicateKeepsFirstTime` (same message produced twice, later timestamp → stored time is the first); `postgresPausedThenBack` (`PostgresTestSupport.pause()`, produce 50, pollOnce throws and rewinds, unpause, drain → 50 rows, each once, `created_at` in lane order); `unreadableRecordSkipped`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement**; `HistoryWriterMain` calls `Db.fromEnv("history")` and `Db.migrate` before consuming. Stage 2 is not changed in this task (it still writes Redis history for `/api/history`).
- [ ] **Step 4: Run** `mvn -q test` → PASS; `make up smoke` → PASS and `psql` shows the smoke run's rows.
- [ ] **Step 5: Commit** `git commit -m "History writer: its own consumer group, batched inserts keyed by message and user"`

### Task 8: Notification service on Postgres; Stage 2 stops writing history

**Files:**
- Create: `src/main/java/service/impl/NotificationHistory.java`
- Rewrite: `NotificationServiceImpl.java` → removed; `NotificationServiceMain.java` (routes, hourly retention), `service/NotificationService.java` removed
- Modify: `Delivery.java` (drop `LPUSH`/`LTRIM` and `KEYS[2]`), `gateway/ApiV1Routes.java`, `GatewayMain.java` (remove `/api/history`), `scripts/smoke-test.sh`
- Test: `src/test/java/service/impl/NotificationHistoryTest.java`, `DeliveryTest.java` (update), `src/test/java/gateway/ApiV1NotificationsTest.java`

**Interfaces:**
- Consumes: tables `notifications`, `notification_reads`.
- Produces: `NotificationHistory(DataSource)`: `Page list(String userId, String league, String team, String game, String cursor, int limit)` (throws `IllegalArgumentException` for a bad cursor); `record Page(List<JsonObject> items, String nextCursor)`; `int unreadCount(String userId)` (cap 1000); `void markSeen(String userId)`; `void clear(String userId)`; `int deleteOlderThan(Duration age, int batchSize)`.
- Produces (notification HTTP): `GET /users/{id}/notifications`, `DELETE /users/{id}/notifications`, `GET /users/{id}/notifications/unread-count`, `POST /users/{id}/notifications/seen`; gateway `/api/v1/notifications…`.

- [ ] **Step 1: Write `NotificationHistoryTest`**: `newestFirstWithCursor` (25 rows, limit 10 → 10/10/5, last `nextCursor` null); `insertDuringPagingNoDupNoGap`; `filters` (league, team via home or away, game); `unreadCountSeenClear`; `catchUpRowOlderThanClearStaysHidden`; `catchUpRowOlderThanSeenIsNotUnread`; `retentionDeletesOver30DaysInBatches`; `badCursorRejected` (Review Focus 3).
- [ ] **Step 2: Write `ApiV1NotificationsTest`**: `limit` 0/101/"abc" and a garbage cursor → `422`; unauthenticated → 401; responses in API shape.
- [ ] **Step 3: Run** → FAIL.
- [ ] **Step 4: Implement**; `NotificationServiceMain` calls `Db.fromEnv("notification")` and `Db.migrate` at startup; cursor = base64url of `createdAtMicros:messageId`; keyset `WHERE (created_at, message_id) < (?, ?)`. Retention every hour, `Duration.ofDays(30)`, batches of 10,000.
- [ ] **Step 5: Stage 2** drops the Redis history write; update `DeliveryTest` (claim and publish unchanged, no `notifications:<user>` key).
- [ ] **Step 6: Smoke**: history checks use `/api/v1/notifications` (Bob has the start; Alice has none; Alice can't read Bob's via any parameter).
- [ ] **Step 7: Run** `mvn -q test` → PASS; `make up smoke` → PASS.
- [ ] **Step 8: Commit** `git commit -m "Notification history served from Postgres; Stage 2 only pushes"`

### Task 9: Cleanup, checks, docs

**Files:**
- Modify: `util/RedisKeys.java` (remove `NOTIFICATIONS_PREFIX`, `USER_PREFIX`, `USERNAME_PREFIX` and any key left unused), `model/User.java` (remove if unused), `scripts/smoke-test.sh` (stranger pod refused on `postgres:5432`), `scripts/persistence-check.sh` (final version: spec section 9)
- Modify: `CLAUDE.md`, `AGENTS.md`, `DESIGN.md`, `docs/api/README.md` (spec section 11)
- Create: section "3.2" in `docs/study/phase-3-quiz.md`

- [ ] **Step 1: Remove** dead keys and classes; `grep -rn "sub:notif\|notifications:\|username:" src/` → nothing.
- [ ] **Step 2: Smoke** network-policy check for Postgres (a stranger pod's `pg_isready -h postgres` fails; a pod labeled `user-pref` succeeds).
- [ ] **Step 3: Verify** `make test`; `make down && make cluster up smoke`; `scripts/persistence-check.sh` (user, password, follow, and a delivered play after the rebuild) → all PASS.
- [ ] **Step 4: Hand check (Done when 4)**: `make simulate`; `kubectl scale statefulset/postgres --replicas=0` for 60 s while it runs; confirm sockets keep receiving; scale back; compare each user's `/api/v1/notifications` count with the number of `notifications` records for them (`kafka-console-consumer` in the broker pod, `--property print.key=true`): equal, no duplicates, order by `created_at` matches lane order. Record the numbers in the PR.
- [ ] **Step 5: Docs** per spec section 11; quiz section (8–10 questions: why commit first, the two locks, why the marker, why Stage 1 pauses not stops, why history is its own group, why the Kafka timestamp, `cleared_at`).
- [ ] **Step 6: Commit** `git commit -m "Phase 3.2 cleanup, checks, and docs"`; open the PR from `phase-3-2-postgres`.
