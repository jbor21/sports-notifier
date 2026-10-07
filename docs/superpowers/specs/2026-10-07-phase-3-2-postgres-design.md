# Phase 3.2: Postgres — design

Status: draft for review, 2026-10-07. Parent document: `DESIGN.md` (build order step 3; decisions 3, 8, 13, 15). If this spec and `DESIGN.md` disagree, `DESIGN.md` wins and this spec gets fixed. `DESIGN.md` was updated with this spec for the two places it refines: history writers are their own consumer group (decision 3, "How one accepted play moves"), and a follow save commits to Postgres before Redis (decision 8).

Phase 3 is four sub-phases (see the 3.1 spec): 3.1 Kafka pipeline (merged, PR #6), **3.2 Postgres (this spec)**, 3.3 alerts wave 1, 3.4 live API. The API contract is `docs/api/`; this phase implements its `/auth`, `/me`, `/follows`, and `/notifications` slice.

## Goal

Make Postgres the source of truth for accounts, follows, and notification history, so nothing a user owns lives only in Redis. Follows take the shape of decision 15 (per-follow alert switches with catalog defaults) now, so 3.3 adds detectors without another table, API, or Redis change. Redis keeps sessions, the live score, the `sub:*` sets (rebuilt from Postgres), and pub/sub. Team ids become league-qualified, which ends `sub:team:BOS` meaning three teams.

The course UI (`index.html`) stops working in this phase. No adapter endpoints. The new UI is 3b.

### Done when

1. `make test` passes, including every test in this spec.
2. `make cluster up smoke` passes on the laptop and in CI, with the smoke test on `/api/v1` (section 9).
3. `scripts/persistence-check.sh` passes on the laptop: an account and its follows survive `make down && make cluster up`, Postgres starts on the mounted folder without a permission error, and the account logs in afterwards.
4. Stopping Postgres while `make simulate` runs: pushes keep arriving; after Postgres is back, each user's history has every notification exactly once, in the order Stage 1 sent them. Checked by hand (Phase 4's checker automates it).
5. Docs updated (section 11) and `docs/study/phase-3-quiz.md` has a 3.2 section. The user is quizzed only on request.
6. The PR from `phase-3-2-postgres` is merged.

### Branch

`phase-3-2-postgres`, worktree `.worktrees/phase-3-2-postgres`, from main at `f770bde`. The laptop cluster must be recreated once (`make down && make cluster up`), because the Postgres folder is mounted when the cluster is created.

---

## 1. Postgres in the cluster

**What it is here.** One Postgres pod, official `postgres:17` image, a StatefulSet in our chart next to Redis. No operator: an operator's main features are replicas and failover, which `DESIGN.md` decision 6 rules out. If the pod dies, Kubernetes restarts it on the same disk.

**Storage.**

| | Laptop (k3d) | AWS (Phase 5) |
|---|---|---|
| Data | `~/.sportsnotifier/postgres` on the Mac, mounted into the k3d node by `make cluster` (`k3d cluster create --volume`), used by the pod as a `hostPath` volume | A normal volume claim (EBS) |
| Survives | `make down`, a Docker or OrbStack freeze, a laptop restart | `helm upgrade`; `terraform destroy` only through the S3 dump (decision 9) |
| Password | Fixed, in `values-local.yaml` | Generated once by the chart into a Secret, kept across upgrades (`lookup`) |

**Why the laptop password is fixed.** Postgres reads its password only when it first creates its data folder. The folder outlives the cluster, but a generated Secret would not: after `make down && make cluster up` the chart would make a new password and every service would fail to log in. Postgres is reachable only inside the cluster, behind a network policy, so a known laptop password costs nothing.

`PGDATA` is a subfolder (`.../pgdata`), because `initdb` wants an empty folder it owns. `make cluster` creates `~/.sportsnotifier/postgres` if it is missing. The persistence check (section 9) proves the image can own and write the mounted folder after a fresh `make cluster`; if it cannot on some Docker runtime, the fix is a `securityContext` that runs Postgres as the folder's owner, and the check catches it either way.

**Who connects.** A network policy lets only user-pref, notification, and history reach Postgres on 5432. The smoke test's stranger pod is refused, like Redis.

**Connections from Java.** Plain JDBC through a HikariCP pool, no ORM: every query is visible SQL. Pool size 5 per pod (env `PG_POOL_SIZE`), so max replicas × 5 stays under Postgres's default 100 connections. Config through env vars with defaults (`PG_URL`, `PG_USER`, `PG_PASSWORD`). A `Db` helper in `util/` builds the pool and waits for Postgres at startup, the way services wait for Redis today.

**Schema changes.** Flyway, with numbered SQL files in `src/main/resources/db/migration/`. Each of the three services runs `migrate` at startup; Flyway holds a Postgres lock while it migrates, so pods starting together are safe. Rule: migrations only add (columns, tables, indexes), so an old pod keeps working during a rolling update. A removal waits for a later release.

## 2. Schema

```sql
CREATE TABLE users (
  id            uuid PRIMARY KEY,
  username      text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  timezone      text NOT NULL DEFAULT 'UTC',
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE follows (
  id          uuid PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind        text NOT NULL CHECK (kind IN ('league', 'team', 'game')),
  target_id   text NOT NULL,          -- NFL, mlb-chw, mlb-401907992
  league      text NOT NULL,          -- NFL, NBA, MLB, NHL
  alerts      jsonb NOT NULL,         -- {"GAME_START": {"enabled": true}, "END_OF_INNING": {"enabled": true, "options": {"innings": [9]}}}
  expires_at  timestamptz,            -- game follows only
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, kind, target_id)
);
CREATE INDEX follows_target ON follows (kind, target_id);

CREATE TABLE notifications (
  user_id     uuid NOT NULL,
  message_id  text NOT NULL,          -- <gameId>:v<version> from Stage 1
  created_at  timestamptz NOT NULL,   -- Kafka timestamp of the notifications record (section 7.2)
  league      text NOT NULL,
  game_id     text,
  home_team   text,                   -- league-qualified, for the team filter
  away_team   text,
  body        jsonb NOT NULL,         -- the API's Notification object
  PRIMARY KEY (user_id, message_id)
);
CREATE INDEX notifications_by_time ON notifications (user_id, created_at DESC, message_id DESC);
CREATE INDEX notifications_by_game ON notifications (user_id, game_id, created_at DESC);

CREATE TABLE notification_reads (
  user_id      uuid PRIMARY KEY,
  last_seen_at timestamptz NOT NULL DEFAULT 'epoch',
  cleared_at   timestamptz NOT NULL DEFAULT 'epoch'
);
```

Notes:

- **Ownership.** user-pref owns `users` and `follows`. The notification service owns `notification_reads` and reads `notifications`; the history writer inserts into `notifications`. `last_seen_at` is not on `users`, so the two services never write the same table.
- **`notifications.user_id` has no foreign key.** A history insert must not fail or wait on the `users` table. Deleting a user (not in this phase) deletes their rows explicitly.
- **The team filter** uses `home_team = $t OR away_team = $t`; the per-user time index keeps it to one user's rows. The league filter is a column on the same rows.
- **Switches as JSON**, one column, not a row per switch: a follow is always read and written whole, the API returns it whole, and the rebuild reads it once. 3.3's detectors never read Postgres; they read the Redis sets.
- **Existing Redis accounts are not migrated.** They are test accounts on a laptop cluster that is recreated for this phase.

## 3. Catalogs and ids

**The alert catalog** is a resource file, `src/main/resources/catalog/alerts.json`, transcribed from decision 15's tables: per league, each alert type with `category`, `appliesTo`, `defaults` per follow kind, `options`, `precision`, and `availability`. It is the one place defaults live. 3.2 uses it for new-follow defaults and `PATCH` validation; 3.4 serves it as `GET /leagues/{id}/alerts`. Until wave 2, `DAILY_SUMMARY` is `coming_soon` and `FINAL` defaults on for league follows (the API spec says so). A unit test checks the file against decision 15's tables row by row for the four leagues.

**The team list** is `src/main/resources/catalog/teams.json`: per league, id (`mlb-chw`), abbreviation (`CHW`), display name (`Chicago White Sox`). Generated once by `scripts/fetch-teams.py` from ESPN's public teams endpoint and checked in. It gives a team follow its `displayName` and makes an unknown team a 404. 3.4 adds logos and colors.

**Ids.**

- League ids: `NFL`, `NBA`, `MLB`, `NHL` (the API's `LeagueIdValue`).
- Team ids: `<league lower>-<abbreviation lower>` (`mlb-chw`). `feed/TeamIds.of(league, abbreviation)` builds them; it is the only place that does.
- Game ids: unchanged from 3.1 (`<league lower>-<espnId>`; simulator games keep their `game-…` ids). A game follow needs a known game: `game:<id>` exists in Redis. Display name for a game follow is `CLE @ CHW` from that record.
- Users and follows: UUIDs. Notification ids in the API are `message_id`.

## 4. Accounts and sessions

The gateway keeps its role: it serves `/api/v1/*`, owns sessions in Redis, applies login throttling, and calls user-pref over HTTP. user-pref's account code moves from Redis to Postgres:

- **Signup** validates username (3–20, `a-z 0-9 _`), password (8–72 bytes), and timezone (an IANA name, checked with `ZoneId.of`; missing means `UTC`), hashes with bcrypt, and inserts. The `UNIQUE` constraint is the race guard: of 20 simultaneous signups for one name, one insert wins and the rest get `409 username_taken`. This replaces the `SET NX` claim; the Phase 2 race test is kept and pointed at the new code.
- **Login** reads by username and checks bcrypt. Wrong user and wrong password give the same `401 invalid_credentials`. Throttling is unchanged (counted before the check).
- **`GET /me`, `PATCH /me`** (timezone only). **`POST /auth/logout`** deletes the session.
- Errors use the API's `Error` shape everywhere in `/api/v1`, with `fields` on `422`.

## 5. Follows and the Redis sets

### 5.1 Endpoints (user-pref behind the gateway)

- `GET /follows`: the user's follows, each with every switch it offers.
- `POST /follows {target}`: validates the target (league known; team in `teams.json`; game in Redis), fills switches from the catalog defaults for that kind, inserts with `ON CONFLICT (user_id, kind, target_id) DO NOTHING`, returns `201` with the new follow or `200` with the existing one.
- `GET /follows/{id}`, `PATCH /follows/{id}`, `DELETE /follows/{id}`: a follow that belongs to another user is a `404`, never a `403`, so ids reveal nothing. `PATCH` replaces only the alert types in the body; a type the league does not offer, one not in `appliesTo` for this kind, or a bad option (innings outside 1–9) is a `422`.

### 5.2 The sets

One Redis set per follow target and alert type, holding user ids: `sub:league:NFL:CLOSE_LATE`, `sub:team:mlb-chw:GAME_START`, `sub:game:mlb-401907992:FINAL`. A user is in a set when they follow that target and that switch is on. This is decision 15's "resolved when saved." The 3.1 sets (`sub:team:<ABBR>`, `sub:notif:<TYPE>`) are deleted by the first rebuild. Key prefixes go in `RedisKeys`.

### 5.3 Saving a follow (create, patch, delete)

1. Take the save lock: `pg_advisory_lock_shared(SUB_LOCK)` on the connection. Many saves hold it at once; only a rebuild excludes them.
2. Write Postgres in a transaction and commit.
3. Update that follow's sets in Redis: `SADD` for switches now on, `SREM` for switches now off (all of them on delete), as one checked pipeline.
4. If step 3 fails: delete the marker `sub:built`. If that also fails (Redis unreachable), set an in-memory "rebuild owed" flag; the 30-second check retries the delete.
5. Release the lock (in `finally`; a dead pod's connection closes and Postgres releases it).
6. Answer from the committed Postgres state. A Redis failure is logged, not returned to the user: the follow is saved, and the sets catch up at the next rebuild.

**Why Postgres first, then Redis.** Postgres is the truth and Redis is a copy. If Redis were written first and the commit then failed, Redis would hold a follow that does not exist, and undoing a Redis write after a failed commit can fail too. With Postgres first, the only bad case is "Postgres has it, Redis does not," and deleting the marker turns that into a rebuild.

### 5.4 Rebuilding the sets

A background thread in user-pref checks every 30 seconds (env `SUB_CHECK_MS`) whether `sub:built` exists, and once a day (env `SUB_FULL_REBUILD_HOURS=24`) rebuilds regardless. A rebuild:

1. Takes `pg_advisory_lock(SUB_LOCK)` (exclusive): waits for saves in flight, holds off new ones. Saves wait for the few seconds a rebuild takes at this scale.
2. Checks `sub:built` again. If another user-pref replica just rebuilt, it stops (unless this is the daily rebuild).
3. Streams unexpired follows ordered by `(kind, target_id)`, so one target's sets are built at a time and memory stays small.
4. Writes each set under a temporary name (`sub:tmp:<buildId>:…`) and `RENAME`s it over the live name. Stage 1 never reads a half-built set.
5. Deletes live `sub:*` sets that the build did not produce (unfollowed targets, the 3.1 sets), found by `SCAN`.
6. Sets `sub:built` to the build time, then releases the lock.

**Why the lock.** Without it: the rebuild reads Bob's follow, Bob unfollows (Postgres commit, `SREM`), then the rebuild's `RENAME` puts Bob back. With it, Bob's unfollow either finishes before the rebuild reads or waits until it is done.

**When the marker goes missing.** Redis restarted without its data (the `sub:*` sets are gone with it), a save's Redis update failed (step 4), or someone flushed Redis. All three lead to the same rebuild.

### 5.5 Game follows expire

A game follow expires a day after its game ends (API: `expiresAt`). user-pref's hourly job: for game follows with no `expires_at`, read `game:<id>`; if its status is final, canceled, or postponed, set `expires_at = now + 1 day`. Follows past `expires_at`, or whose game record is gone and which are more than 8 days old (game records expire after 8 idle days), are deleted with the normal save path (lock, commit, `SREM`). The rebuild skips expired follows.

## 6. Stage 1 in 3.2

Stage 1 still receives the three 3.1 types. It maps each to catalog switches and builds the audience from the new sets:

| Play type | Switches | Headline type in history |
|---|---|---|
| `GAME_START` | `GAME_START` | `GAME_START` |
| `SCORE_UPDATE` | NFL: any of `TOUCHDOWN`, `TRY_RESULT`, `FIELD_GOAL`, `SAFETY`. MLB: `RUN_SCORED`. NHL: `GOAL`. NBA: none | MLB `RUN_SCORED`, NHL `GOAL`, NFL `SCORE` (interim) |
| `GAME_END` | `FINAL` | `FINAL` |

Audience = union over the play's targets (`sub:team:<home>`, `sub:team:<away>`, `sub:game:<id>`, `sub:league:<league>`) × the mapped switches. The union is built inside Redis as today (`SUNIONSTORE` into a temporary key, `SSCAN` pages). The team ids come from `TeamIds.of(league, abbreviation)`.

**Interim limits, all closed by 3.3:**

- A play carries only the score after it, so Stage 1 cannot tell a touchdown from a field goal. An NFL score reaches anyone with any scoring switch on, and its headline type is `SCORE`, which the UI ignores as unknown and shows by its text.
- NBA follows get only start and final. That matches the catalog (no alert per basket).
- No "most specific follow wins": a user who turned `GAME_START` off on a game follow but on for the team still gets it through the team. 3.3 adds the set math.

Stage 1 writes the API's `Notification` shape (headline, game with both teams' ids and scores, period label, clock, `detectedAt`) into the message, so the history writer stores it as is. Stage 2 pushes the same message.

## 7. History

### 7.1 Stage 2

Its delivery script keeps the dedup claim (`notif:<messageId>:<userId>`) and the push to `gw:<gatewayId>`, and drops the Redis history list (`notifications:<userId>`). Postgres is not on Stage 2's path at all.

### 7.2 The history writer

A new main, `service.impl.HistoryWriterMain` (`SERVICE=history`, port 8088), consumer group `history` on `notifications`. Same loop shape as Stage 2 (poll, handle the batch, commit, rewind on failure, `LoopHealth` on `/health`).

- **One statement per batch.** A multi-row `INSERT … ON CONFLICT (user_id, message_id) DO NOTHING` in one transaction, then `commitSync`. A crash after the insert and before the commit re-reads the batch, and the primary key makes the re-insert a no-op.
- **`created_at` is the Kafka timestamp of the `notifications` record**, which Kafka stamps when Stage 1 sends it. Not the insert time. A catch-up after a Postgres outage then does not reorder history or show old alerts as unread. If Stage 1 is redelivered and sends a message again, the copy has a later timestamp, but the original comes first in its lane, so `ON CONFLICT DO NOTHING` keeps the original's time.
- **Postgres down.** The insert fails, the batch is rewound and retried after a second, and the lag grows. Pushes are unaffected (Stage 2 is a separate group). When Postgres returns, the writer catches up in order.
- **Retention bounds the gap.** The `notifications` topic keeps 1 day (3.1), so a Postgres outage longer than a day loses the history older than that. Pushes are not affected. Accepted; raising `retentionMs` is the knob, and the Stage 2 dedup TTL must stay longer than it.

### 7.3 The notification service

Reads only. Behind the gateway:

- `GET /notifications?league&team&game&cursor&limit`: newest first, `created_at > cleared_at`, keyset paging on `(created_at, message_id)` so new rows never shift a page. The cursor is that pair, base64-encoded.
- `GET /notifications/unread-count`: rows with `created_at > max(last_seen_at, cleared_at)`, counted up to 1000 (the badge shows "999+").
- `POST /notifications/seen`: `last_seen_at = now()`.
- `DELETE /notifications`: deletes the user's rows and sets `cleared_at = now()`. The `cleared_at` filter matters because the history writer may still insert older rows after a clear (a catch-up); without it, cleared alerts would reappear.
- Every hour, deletes rows older than 30 days, in batches of 10,000, so one delete never holds a big lock.

## 8. Gateway routes

Added under `/api/v1`: `auth/signup`, `auth/login`, `auth/logout`, `me` (GET, PATCH), `follows` (GET, POST), `follows/{id}` (GET, PATCH, DELETE), `notifications` (GET, DELETE), `notifications/unread-count`, `notifications/seen`.

Removed: `/api/signup`, `/api/login`, `/api/logout`, `/api/me`, `/api/preferences`, `/api/history`, and user-pref's `/prefs/*`. Kept until 3.4 replaces them: `/api/games`, `/api/games/{id}`, `/api/upcoming`. The WebSocket stays `/ws` with the 3.1 notification message; `/api/v1/ws` (hello, unread count, game updates) is 3.4.

`index.html` stays in the repo, broken, until 3b replaces it.

## 9. Tests

Unit and integration tests run against real Redis (as today) and a real Postgres started by Testcontainers (Docker is already required for the Kafka tests).

**Accounts.** Signup validation (each `422` field code); 20 simultaneous signups for one name → exactly one `201`; login with wrong user and wrong password give identical responses; throttle still counts before the check; timezone validation.

**Follows.** Defaults match the catalog for each kind and league; follow twice → `200` with the same id; `PATCH` validation (unknown type, wrong kind, innings out of range); another user's follow → `404`; the catalog file matches decision 15's tables.

**Sets and rebuild.**

- A save puts the user in exactly the sets of its enabled switches; patch and delete move them out.
- Redis fails during a save (stop Redis between commit and update): the follow is in Postgres, `sub:built` is gone (or the owed flag is set when Redis is unreachable), and the next check rebuilds the sets to match Postgres.
- Unfollow during a rebuild (rebuild paused after reading, unfollow started, rebuild resumed): the unfollow waits, and the user is not in the set afterwards.
- Two replicas notice the missing marker together: one rebuilds, the other skips.
- A rebuild deletes the 3.1 sets and sets for unfollowed targets, and Stage 1 reading during a rebuild never sees an empty set for a target that has followers.
- Expired game follows leave their sets and are deleted.

**Stage 1.** The mapping table, per league, including "NBA score reaches no one" and "a field-goal-only NFL follower gets a score"; a user following both teams gets one message; `sub:team:BOS` no longer collides (an `nba-bos` follower gets nothing from an `mlb-bos` game).

**History writer.** A batch re-read inserts nothing new; `created_at` equals the record timestamp; Postgres stopped mid-run (Testcontainers pause) → batches retry, Stage 2 pushes continue, and after unpause every message is in history once, in lane order.

**Notification service.** Paging across a concurrent insert (no duplicates, no gaps); each filter; unread count with `seen` and `clear`; a catch-up row older than `cleared_at` stays hidden; the 30-day cleanup.

**Smoke test (`scripts/smoke-test.sh`), moved to `/api/v1`.** Sign up two users; Bob follows the simulator game's home team (`nba-lal`) with defaults; run the simulator; Bob's socket receives the start, and `GET /notifications` has it; Alice gets nothing and cannot read Bob's history or follows (`404`); network policies refuse a stranger pod on Postgres as on Redis.

**Persistence check (`scripts/persistence-check.sh`, laptop only, not CI).** Signs up a user with a follow, runs `make down && make cluster up`, then checks: the Postgres pod logs no permission error and reaches ready on the mounted folder; the user logs in with the same password; `GET /follows` returns the follow; a simulated play reaches them (the sets were rebuilt from Postgres into the new, empty Redis).

## 10. Order of work

Each task keeps CI green, keeping the old path until its replacement lands, as in 3.1.

1. **Postgres in the chart.** StatefulSet, laptop mount and fixed password, AWS-style generated Secret path in `values.yaml`, network policy, `make cluster` creates the folder and the mount, `Db` helper, Flyway with the schema, `persistence-check.sh` (first version: Postgres survives a rebuild and has its tables).
2. **Catalogs.** `alerts.json` and its decision-15 test, `teams.json` and `fetch-teams.py`, `TeamIds`.
3. **Accounts in Postgres.** user-pref account code, gateway `/api/v1/auth/*` and `/api/v1/me`, smoke test signup and login moved.
4. **Follows, sets, and Stage 1.** Endpoints, save path, rebuild, marker and daily rebuild, game follow expiry, Stage 1 mapping onto the new sets. These land together: Stage 1 reading the new sets needs follows to fill them. Smoke test follows moved.
5. **History writer.** New main, chart Deployment, Stage 2 drops its Redis history.
6. **Notification service on Postgres.** The four endpoints, retention job, gateway routes. Smoke test history moved.
7. **Cleanup and docs.** Old routes and keys removed, final smoke and persistence check, the Postgres-outage hand check (Done when 4), docs (section 11), quiz section.

## 11. Docs to update in task 7

- `CLAUDE.md` and `AGENTS.md`: architecture table (history service, Postgres), "Open problems" 1 and 2 closed, current state, `make down` no longer deletes accounts.
- `DESIGN.md`: build order 3.2 → built; "Security and correctness still to fix": accounts and team ids move to fixed.
- `docs/api/README.md`: 3.2's endpoints are served by the backend.

## Known gaps accepted in 3.2

- **Interim alert mapping** (section 6): NFL scores are not told apart, no most-specific-follow rule. 3.3.
- **A Postgres outage longer than the `notifications` topic's retention (1 day)** loses the history older than that.
- **A missed Redis update can last up to a day** if Redis is unreachable during a save and user-pref then dies before the marker delete succeeds. The daily rebuild bounds it.
- **Saves wait during a rebuild** (seconds at this scale). At millions of follows the rebuild would need to run per target instead of under one lock.
- **No account deletion endpoint.** Not in the API.
