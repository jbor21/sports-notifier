# Phase 4.1: Replay and Checker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replay recorded ESPN games through the real pipeline to synthetic users on real sockets, and have a checker prove each user got exactly the pushes they should have, once, in game order, in history and on every socket; plus the versioned `sub:built` marker (#16, #18).

**Architecture:** `RecordedScoreFeed` plays per-game recorded timelines through the real `Poller`. `tools.Checker` (one Job per run) signs up seeded synthetic users through `/api/v1`, primes the replay, creates and freezes follows, runs the replay in-process, holds a socket per user, waits for a steady end, then recomputes the expected pushes from `game-events` + the frozen Postgres follows (detectors and the combining step shared with Stage 1; audience computed independently in Java) and compares them with Postgres history and each socket's log. Everything is scoped to the run's game ids (`-rp<runId>-`).

**Tech Stack:** Java 17, Jedis 4.4.7, Gson, kafka-clients 4.3.1 (consumer + `Admin`), PostgreSQL 17 (JDBC, Flyway), `java.net.http` (HTTP + WebSocket client), JUnit 5, Testcontainers (Kafka, Postgres), Python 3 (packing script), Helm, k3d.

**Spec:** `docs/superpowers/specs/2026-10-09-phase-4-1-replay-checker-design.md`

## Global Constraints

- Run id: the run's start time in epoch seconds, base 36, 6 characters (`Long.toString(sec, 36)`).
- Replayed game id: `<league lowercase>-rp<runId>-<espnId>`; scoping test: game id contains `-rp<runId>-`.
- Synthetic username: `ck_<runId>_<n>`, n from 0; password `CHECKER_PASSWORD`, default `checker-pass`.
- Defaults: `USERS=500`, `SEED=1`, `SPEED=10`, `SET=ci`; second tab for 10% of users; a randomized switch is on with probability 0.7; MLB `END_OF_INNING` picks 1–3 innings.
- Follow mix shares: 40% one team · 15% both teams of one game · 15% team + that team's game · 10% league + a team in it · 10% league only · 10% team + its game with every switch off.
- Only catalog types with `availability: "available"` are ever switched by the checker.
- Poll interval during a replay: `max(500, 30_000 / SPEED)` ms.
- End condition: feed done, every run game's `game:<id>` status `FINAL`, and groups `Topics.STAGE1_GROUP`, `STAGE2_GROUP`, `HISTORY_GROUP` at zero lag on every partition, sampled every 1 s, continuously for 10 s. `RUN_TIMEOUT` default 5 minutes after the feed is done.
- Socket client: `ping` every 25 s; reconnect after any close with backoff 1 s doubling to 30 s, full jitter.
- Report: at most 20 examples per finding.
- `FollowSets.SHAPE_VERSION = 2`; `sub:built` is a hash with fields `version`, `runId`.
- Migration file: `src/main/resources/db/migration/V2__follows_updated_at.sql`.
- **Spec refinement (Task 4 updates spec section 2.1–2.2 to match):** a replay set stores **per-game timelines**, not whole boards: each game's time starts at 0 at its first kept snapshot, gaps longer than 60 s are cut to 60 s (a 3-hour pre-game or an intermission must not stall a run), and each game has a start offset in minutes from the pack command (`nfl:401872971@20`), so games recorded on different days overlap in a run.
- Redis keys only via `util/RedisKeys.java`; every Jedis handle in try-with-resources; config by env var with defaults.
- Comment density as in the repo: explain *why* at the oracle's audience formula, the end condition, the socket "missing" rule, and the marker; quiet elsewhere.
- `make test` green at the end of every task.

## Review Focus

1. **A 3.x `sub:built` (a plain string) still in Redis after `helm upgrade`** → user-pref rebuilds and Stage 1 waits; nothing throws `WRONGTYPE` (Task 1 tests `legacyStringMarkerRebuilds`, `legacyStringMarkerIsNotBuilt`).
2. **A recorded game with a long gap** (3-hour pre-game, recorder outage, NHL intermission) → the replay moves on after 60 s of compressed time, not hours (Task 4 test `longGapsCompressedTo60s`).
3. **A 504 or connection reset during setup** (a gateway rollout, #31) → signup/follow/patch retried up to 3 times with 1 s between; still failing is reported as `SETUP FAILED`, never as a pipeline finding (Task 8 test `setupRetriesTransientErrors`).
4. **One push on two tabs of one user** → one copy per tab is correct, not a duplicate (Task 7 test `twoTabsEachGetOneCopy`).
5. **The same play version twice on `game-events`** (the outbox re-sent it after a lost confirmation) → the oracle skips it as Stage 1 does, so no phantom expected push (Task 5 test `duplicateVersionOnTopicSkipped`).

---

### Task 1: `sub:built` says what was built (#16, #18)

**Files:**
- Modify: `src/main/java/service/impl/FollowSets.java` (constant + two static helpers), `src/main/java/service/impl/SubRebuilder.java` (marker check; drop `lastRunId`), `src/main/java/service/impl/Stage1WorkerMain.java:36-40` (sets-ready lambda), `src/main/java/util/RedisKeys.java:77-80` (Javadoc: hash `{version, runId}`)
- Modify tests that set the marker as a string: `simulator/SimulatorRunsEndToEndTest.java:95,130`, `service/impl/Stage1WaitsForSetsTest.java:92,153,172`, `service/impl/SubRebuilderTest.java:76,81,95` → use the helpers
- Test: `src/test/java/service/impl/SubRebuilderTest.java`, `src/test/java/service/impl/FollowSetsTest.java`

**Interfaces:**
- Produces: `FollowSets.SHAPE_VERSION` (`public static final int`, value `2`; Javadoc: bump whenever set names or shapes change); `public static boolean FollowSets.isBuilt(Jedis jedis)` (true iff `sub:built` is a hash whose `version` equals `SHAPE_VERSION`; a string or other type is false, checked with `TYPE` first); `public static void FollowSets.markBuilt(Jedis jedis, String runId)` (`HSET sub:built version <SHAPE_VERSION> runId <runId>`, after a `DEL` so a legacy string is replaced).
- `SubRebuilder.checkOnce()`: rebuild when not `isBuilt`, or when the stored `runId` ≠ `runId.apply(jedis)` (both non-null). `rebuildNow` writes the marker with `markBuilt(jedis, runId.apply(jedis))`.

- [ ] **Step 1: Write failing tests.** In `SubRebuilderTest` replace `runIdChangeDeletesMarker` and `firstRunIdReadDoesNot` with: `markerOldVersionRebuilds` (`HSET sub:built version 1 runId R`, live run id `R` → `checkOnce()` true, then `HGET version` = `"2"`); `markerOtherRunIdRebuilds` (version 2, runId `old`, live `new` → true, stored runId `new`); `restartedTogetherStillRebuilds` (a **new** `SubRebuilder` instance, marker runId `old`, live `new` → true: no in-memory state needed); `currentMarkerNoRebuild` (version 2, runId = live → false); `legacyStringMarkerRebuilds` (`SET sub:built "2026-10-08T…"` → true, no exception, marker now a hash). Keep `defaultRunIdReaderReadsRedis`. In `FollowSetsTest`: `isBuiltNeedsCurrentVersion` (missing → false; version 1 → false; version 2 → true) and `legacyStringMarkerIsNotBuilt`.
- [ ] **Step 2: Run** `mvn -q test -Dtest='SubRebuilderTest,FollowSetsTest'` → FAIL.
- [ ] **Step 3: Implement** the Interfaces block. `Stage1WorkerMain`'s supplier becomes `FollowSets.isBuilt(jedis)`; Stage 1's wait log says "sub:built is missing or from another set shape". Update the listed tests to call `markBuilt` instead of `jedis.set(SUB_BUILT, …)` and `isBuilt` instead of `exists`.
- [ ] **Step 4: Run** `make test` → PASS.
- [ ] **Step 5: Commit** `git commit -m "sub:built records the set-shape version and Redis's run id (#16, #18)"`.

### Task 2: `follows.updated_at`

**Files:**
- Create: `src/main/resources/db/migration/V2__follows_updated_at.sql` — `ALTER TABLE follows ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();`
- Modify: `src/main/java/service/impl/FollowStore.java:87` — the `mergeAlerts` UPDATE also sets `updated_at = now()`
- Test: `src/test/java/service/impl/FollowServiceTest.java`

- [ ] **Step 1: Failing tests:** `newFollowHasUpdatedAtEqualToCreatedAt` (read both columns by SQL after `create`); `switchChangeSetsUpdatedAt` (sleep 20 ms, `PATCH`-equivalent service call, `updated_at > created_at`); `deleteAndRecreateGetsNewIdAndTimestamps`.
- [ ] **Step 2: Run** `mvn -q test -Dtest=FollowServiceTest` → FAIL.
- [ ] **Step 3: Implement** as in Files. `Follow` record and `COLUMNS` unchanged (only the checker reads `updated_at`).
- [ ] **Step 4: Run** `make test` → PASS.
- [ ] **Step 5: Commit** `"follows.updated_at, set on every switch change"`.

### Task 3: The per-user combining step as a pure function

**Files:**
- Create: `src/main/java/service/impl/MomentPushes.java`
- Modify: `src/main/java/service/impl/MomentFanout.java` (`send` iterates `MomentPushes.groups(result)`; the inner loop uses `MomentPushes.pick`)
- Test: `src/test/java/service/impl/MomentPushesTest.java`; `MomentFanoutTest` unchanged

**Interfaces:**
- Produces: `public static List<List<Alert>> MomentPushes.groups(Detectors.Result r)` — `[moment]` if non-empty, then `[finalAlert]` if non-null; the send order (Javadoc keeps MomentFanout's "final after the moment" reason). `public static List<Alert> MomentPushes.pick(List<Alert> group, List<Boolean> in)` — the alerts whose flag is true, order kept; empty means no push. Bodies stay `NotificationBody.of(play, picked, labels)`; message id = body `id`.

- [ ] **Step 1: Failing tests:** `groupsMomentThenFinal`, `groupsSkipEmptyMoment`, `groupsNoFinal`, `pickKeepsHeadlineOrder` (flags `[true,false,true]` → alerts 0 and 2), `pickNoneIsEmpty`.
- [ ] **Step 2: Run** `mvn -q test -Dtest=MomentPushesTest` → FAIL.
- [ ] **Step 3: Implement**, then switch `MomentFanout` to it (no behavior change; keep its shape cache keyed by the flag string).
- [ ] **Step 4: Run** `mvn -q test -Dtest='MomentPushesTest,MomentFanoutTest,Stage1WorkerTest,ReplayGoldenTest'` and `make replay-detectors` (output unchanged against main: `diff` the two runs) → PASS.
- [ ] **Step 5: Commit** `"The per-user combining step is a pure function Stage 1 and the checker share"`.

### Task 4: Replay sets and `RecordedScoreFeed`

**Files:**
- Create: `scripts/espn_trim.py` (move `trim-espn-fixture.py`'s event trimming into `def trim_event(event: dict) -> dict`; `trim-espn-fixture.py` imports it, output unchanged)
- Create: `scripts/pack-replay.py`
- Create: `src/main/resources/replay/ci.jsonl.gz`, `src/main/resources/replay/evening.jsonl.gz`
- Create: `src/main/java/feed/RecordedScoreFeed.java`
- Modify: `src/main/java/model/GameSnapshot.java` (add `GameSnapshot rebased(String gameId, long shiftMillis)`: new id, `startTimeMillis + shiftMillis` when > 0)
- Modify: spec sections 2.1–2.2 for the per-game format (Global Constraints)
- Test: `src/test/java/feed/RecordedScoreFeedTest.java`, with `src/test/resources/replay/tiny.jsonl` (plain JSONL: one NFL and one NHL game, about 15 lines each, cut from the golden fixtures in `pack-replay.py`'s line format, the NHL game at a 20-minute offset), `scripts/test_pack_replay.py` (run with `python3 -m unittest scripts/test_pack_replay.py`)

**Interfaces:**
- `pack-replay.py --list [--recordings DIR]` prints one line per recorded game: `league espnId firstStatus lastStatus firstSeen lastSeen boards`. `pack-replay.py <set> <league>:<espnId>[@<offsetMinutes>] ...` writes `src/main/resources/replay/<set>.jsonl.gz`, one JSON line per kept snapshot in time order: `{"game":"<league>-<espnId>","league":"NFL","t":<ms on that game's compressed timeline + offset>,"event":<trim_event output>}`. A snapshot is kept when the trimmed event differs from that game's previous one. Exits 1 naming the game if its first kept status is not scheduled (`state == "pre"`) or its last is not final (`completed == true`).
- `public RecordedScoreFeed(List<Line> lines, String runId, long runStartMillis, LongSupplier clock, double speed)`; `public record Line(String game, String league, long t, String eventJson)`; `public static List<Line> read(InputStream jsonl)`; `public static List<Line> load(String set)` (gunzips classpath `replay/<set>.jsonl.gz`, then `read`); `public void startClock()`; `List<GameSnapshot> fetch(String league)`; `public boolean done()`; `public List<String> gameIds()`; `public Map<String, GameSnapshot> firstSnapshots()` (by run game id; for the follow mix's teams); `public static String gameId(String league, String runId, String espnId)`.
- Before `startClock()`: `fetch` returns each game's first snapshot (priming). After: virtual elapsed `e = (clock − origin) × speed`; each game's latest snapshot with `t ≤ e`; a game with none yet is omitted. Snapshots are parsed once at construction via `EspnScoreFeed.parse("{\"events\":[" + eventJson + "]}", league)` then `rebased(gameId(...), runStartMillis − firstRecordedStart)` where the shift puts the set's earliest start time at `runStartMillis`. `done()`: `e` past every game's last `t`.

- [ ] **Step 1: Failing tests.** Java: `primeReturnsFirstSnapshotPerGame`, `picksLatestAtOrBeforeVirtualTime` (boundaries `t`, `t−1`), `speedScalesTime` (10×: 3 s wall = 30 s), `gameNotStartedIsOmitted` (offset game), `idsRewritten` (`nfl-rpabc123-401872971`), `startTimesShifted`, `doneAfterLastLine`, `gameIdsListsEveryGame`. Python: `dropsUnchangedSnapshots`, `longGapsCompressedTo60s` (snapshots at 0, 30 s, 3 h → `t` 0, 30 s, 90 s), `offsetAddedToTimeline`, `refusesGameNotStartingScheduled`, `refusesGameNotEndingFinal`.
- [ ] **Step 2: Run** `mvn -q test -Dtest=RecordedScoreFeedTest` and `python3 -m unittest scripts/test_pack_replay.py` → FAIL.
- [ ] **Step 3: Implement** as in Interfaces.
- [ ] **Step 4: Choose the sets.** Run `scripts/pack-replay.py --list`. `ci`: `nfl:401872971` and `nhl:401891803` (golden games; if either fails the scheduled/final check, take another NFL/NHL game that passes and say so in the commit). `evening`: about 10 games from all four leagues, including the 4 golden games (`nfl:401872971`, `nhl:401891803`, `nhl:401891831`, `nhl:401892443`) and at least one overtime, one late lead change, one delay (use `make replay-detectors` output to find them), offsets spread over 0–40 minutes. Pack both; each file under 3 MB.
- [ ] **Step 5: Run** both test commands → PASS; `make test` → PASS.
- [ ] **Step 6: Commit** `"Replay sets: per-game recorded timelines, gaps cut to 60 s; RecordedScoreFeed"`.

### Task 5: `Replay`, `ReplayMain`, `make replay`

**Files:**
- Create: `src/main/java/simulator/Replay.java`, `src/main/java/simulator/ReplayMain.java`
- Create: `deploy/helm/sports-notifier/templates/replay-cronjob.yaml` (copy of `simulator-cronjob.yaml`: name `replay`, `SERVICE=replay`, env `REPLAY_SET`, `REPLAY_SPEED` from values `replay.set: evening`, `replay.speed: 10`), `scripts/replay.sh` (as `simulate.sh`, cronjob `replay`)
- Modify: `src/main/java/app/Launcher.java` (`replay` → `ReplayMain`), `deploy/helm/sports-notifier/values.yaml`, `templates/network-policies.yaml:46` (`event-processing` callers add `replay`, `checker`), `Makefile` (`replay:` → `@scripts/replay.sh REPLAY_SET=$(SET) REPLAY_SPEED=$(SPEED)`, defaults `SET=evening SPEED=10`)
- Test: `src/test/java/simulator/ReplayTest.java`, `src/test/java/app/LauncherTest.java`

**Interfaces:**
- Consumes: `RecordedScoreFeed` (Task 4), `feed.Poller(ScoreFeed, JedisPool, EventSink, LongSupplier)`.
- Produces: `public Replay(RecordedScoreFeed feed, Poller poller, JedisPool pool, long pollMillis)`; `public void prime(Duration timeout)` — polls every league until every run game has a `game:<id>` record, else throws `IllegalStateException` naming the missing ids; `public void start()` — `feed.startClock()` and a daemon thread polling every league every `pollMillis`, logging and continuing on a poll error, until `stop()`; `public boolean feedDone()`; `public List<String> notFinal()` — run game ids whose `game:` status is not `FINAL`; `public void stop()`. `static long pollMillis(double speed)` = `max(500, 30_000 / speed)`.
- `ReplayMain`: env `REPLAY_SET` (default `evening`), `REPLAY_SPEED` (default `10`), `EPS_URL`; run id from now; `prime(30 s)`, `start()`, exits 0 when `feedDone()` and `notFinal()` is empty, 1 if still not final 5 minutes after `feedDone()`.

- [ ] **Step 1: Failing tests** (Redis DB 1, a fake `EventSink` that applies plays through the real `EventProcessingServiceImpl` as `SimulatorRunsEndToEndTest` does): `primeCreatesEveryGameRecord`, `primeTimesOutNamingMissingGames` (a sink that drops one game), `runReachesFinalForEveryGame` (`tiny.jsonl` at speed 600), `pollMillisFloorIs500`. `LauncherTest.replayIsAService`.
- [ ] **Step 2: Run** `mvn -q test -Dtest='ReplayTest,LauncherTest'` → FAIL.
- [ ] **Step 3: Implement.** `notFinal` reuses the check in `EventSimulatorMain.notFinal` (move it to `Replay` as `static List<String> notFinal(JedisPool, List<String>)` and call it from both).
- [ ] **Step 4: Run** `make test` → PASS.
- [ ] **Step 5: Cluster check.** `make down && make cluster up`, then `make replay SET=ci SPEED=60`: the Job exits 0. Sign in to `http://localhost:8080`, follow a team of a replayed game, run `make replay SET=ci SPEED=10` and watch the card and pushes; note what you saw in the commit body.
- [ ] **Step 6: Commit** `"Replay: recorded games through the real poller; make replay"`.

### Task 6: The oracle

**Files:**
- Create: `src/main/java/checker/FollowSnapshot.java`, `src/main/java/checker/Audience.java`, `src/main/java/checker/ExpectedPush.java`, `src/main/java/checker/Oracle.java`, `src/main/java/checker/PlayReader.java`
- Test: `src/test/java/checker/AudienceTest.java`, `src/test/java/checker/OracleTest.java`, `src/test/java/checker/PlayReaderTest.java` (Testcontainers Kafka via `KafkaTestSupport`)

**Interfaces:**
- `public record FollowSnapshot(Follow follow, Instant updatedAt)`.
- `public static Set<String> Audience.of(Play play, Alert alert, Collection<Follow> follows)` — the formula `game:s ∪ ((teamH:s ∪ teamA:s) − game:ANY) ∪ (league:s − game:ANY − teamH:ANY − teamA:ANY)`, where a follow "has switch s" iff `s ∈ FollowSets.memberships(follow)` and `X:ANY` means any follow of target X; team ids via `TeamIds.of(play.league(), abbr)`. Expired follows (`expiresAt` before the play's `detectedAt`) are excluded. Class Javadoc: why it is computed here and not read from Redis.
- `public record ExpectedPush(String userId, String gameId, String messageId, JsonObject body, long version)`.
- `public Oracle(Detectors detectors, GameLabels labels)`; `public List<ExpectedPush> expected(Map<String, List<GameEvent>> playsByGame, Collection<Follow> follows)` — per game, in list order: skip a play whose version ≤ the last handled version (as Stage 1 does), `Play.of`, `detectors.run(memory, play)` from null memory, then for each `MomentPushes.groups(result)` group: for every user in the union of the group's audiences, flags = membership per alert, `MomentPushes.pick`, non-empty → `NotificationBody.of(play, picked, labels)`, message id = body `id`. Output order: game, then play order, then group order, then userId (sorted, for stable reports).
- `public static Map<String, List<GameEvent>> PlayReader.read(String bootstrap, String topic, long sinceMillis, String runId)` — assigns every partition, seeks with `offsetsForTimes(sinceMillis)`, reads until the end offsets taken at call time, keeps records whose key contains `-rp<runId>-`, parsed with `GameEventParser.parse`, unreadable skipped; lists in partition order.

- [ ] **Step 1: Failing tests.**
  - `AudienceTest`: one test per row of the spec's 3.1 table (`teamFollowerWithSwitchOn`, `bothTeamsSwitchesCombined`, `gameFollowWinsOverTeam`, `teamWinsOverLeague`, `leagueOnly`, `gameFollowAllOffHearsNothing`), `inningsOption` (END_OF_INNING alert for inning 7: a follow with innings [7] in, [9] out, none chosen in), `otherLeagueIgnored`, and the cross-check `matchesRedisSetsOnRandomFollows`: 300 seeded random follows written with `FollowSets.writeState` to Redis DB 1, for each alert type of three plays `Audience.of` equals the members of `new AlertAudience().build(jedis, play, List.of(alert)).keys().get(0)`.
  - `OracleTest`: `goldenGamesMatchGoldenAlerts` (for each of the 4 golden replays: feed the fixture boards through `PollDecision` as `ReplayGoldenTest` does to get plays, one **game** follower with every available switch on; the alert ids across their pushes (headline + extras) equal every alert id in the golden timeline, in order; reuse `ReplayGoldenTest`'s play building, moved to a test-support helper if it is private); `duplicateVersionOnTopicSkipped`; `finalIsItsOwnPushAfterTheMoment`; `noFollowersNoPushes`.
  - `PlayReaderTest`: `readsOnlyThisRunsGames` (records for `-rpaaa111-` and `-rpbbb222-` and a live id), `startsAtSinceMillis`, `keepsPartitionOrder`.
- [ ] **Step 2: Run** `mvn -q test -Dtest='checker.*Test'` → FAIL.
- [ ] **Step 3: Implement** as in Interfaces.
- [ ] **Step 4: Run** `make test` → PASS.
- [ ] **Step 5: Commit** `"Checker oracle: expected pushes from game-events and Postgres follows, audience computed in Java"`.

### Task 7: Socket client, verification, report

**Files:**
- Create: `src/main/java/checker/SocketLog.java`, `src/main/java/checker/SocketClient.java`, `src/main/java/checker/HistoryRow.java`, `src/main/java/checker/Finding.java`, `src/main/java/checker/Verifier.java`, `src/main/java/checker/FollowFreeze.java`, `src/main/java/checker/Report.java`
- Test: `src/test/java/checker/SocketClientTest.java` (a Javalin ws endpoint in the test), `src/test/java/checker/VerifierTest.java`, `src/test/java/checker/FollowFreezeTest.java`, `src/test/java/checker/ReportTest.java`

**Interfaces:**
- `SocketLog` (thread-safe): `record Arrival(String messageId, String gameId, JsonObject body, long atMillis)`, `record Gap(long fromMillis, long toMillis)` (open gap: `toMillis = Long.MAX_VALUE`); `userId()`, `tab()` (0 or 1), `List<Arrival> arrivals()`, `List<Gap> gaps()`.
- `public SocketClient(URI ws, String sid, SocketLog log, LongSupplier clock, Duration pingEvery)`; `start()`, `close()`. Sends `Cookie: sid=<sid>`. Logs `notification` messages (`notification.id`, `notification.game.id`), ignores other types and `hello` order (#24). A gap opens on close/error and closes on the next successful open. Reconnect per Global Constraints.
- `public record HistoryRow(String userId, String messageId, String gameId, Instant createdAt, JsonObject body)`.
- `public record Finding(String kind, String userId, String gameId, String messageId, String detail)`; kinds: `history_missing`, `history_unexpected`, `history_wrong_body`, `socket_missing`, `socket_duplicate`, `socket_out_of_order`, `socket_unexpected`, `follow_changed`, `setup_failed`, `timeout`.
- `public static Verifier.Result Verifier.verify(String runId, List<ExpectedPush> expected, List<HistoryRow> history, List<SocketLog> sockets)`; `Result(List<Finding> findings, int outsideRun, int allowedInGaps, List<Long> latenciesMillis)`. Rules exactly as spec 6.2–6.3; body comparison ignores `createdAt`; "missing" allowed iff history has it and a gap of that socket overlaps `[createdAt, arrival of the first push on this socket with a later createdAt, or +∞]`; out of order is judged per game against expected order; latency = arrival − body `detectedAt`.
- `public static List<Finding> FollowFreeze.compare(List<FollowSnapshot> start, List<FollowSnapshot> end, Instant t0, Instant runEnd)` — spec 3.2.
- `public static String Report.render(Report.Header h, Verifier.Result r, List<Finding> freeze)`; `Header(runId, set, speed, users, twoTabUsers, games, plays, expectedPushes)`; layout as spec 6.5; ends with `PASS` or `FAIL`; p50/p99 from the latencies.

- [ ] **Step 1: Failing tests.**
  - `SocketClientTest`: `logsNotificationsWithArrivalTime`, `ignoresOtherTypesAndLateHello`, `reconnectsAfterServerCloseAndRecordsGap`, `sendsPingOnSchedule` (`pingEvery` 100 ms), `sendsSessionCookie`.
  - `VerifierTest`: one test per finding kind (only that finding reported); `missingInsideGapWithHistoryAllowed`, `missingInsideGapWithoutHistoryNotAllowed`, `missingOutsideGapNotAllowed` (a later push arrived, no gap between), `missingAfterLastArrivalWithOpenGapAllowed`, `twoTabsEachGetOneCopy`, `orderOnlyWithinAGame` (two games interleaved differently is fine), `otherGamesCountedOutsideRun`, `bodyDiffIgnoresCreatedAt`.
  - `FollowFreezeTest`: `unchangedPasses`, `changedAlertsFails`, `deletedFollowFails`, `addedFollowFails`, `updatedAfterT0Fails`, `expiresAtSetToFutureAllowed`, `expiresAtSetBeforeRunEndFails`.
  - `ReportTest`: `passReportShape` (the spec 6.5 lines, given fixed numbers), `failListsAtMost20ExamplesAndTotal`.
- [ ] **Step 2: Run** `mvn -q test -Dtest='checker.*Test'` → FAIL.
- [ ] **Step 3: Implement** as in Interfaces.
- [ ] **Step 4: Run** `make test` → PASS.
- [ ] **Step 5: Commit** `"Checker: socket client, verification rules, follow freeze, report"`.

### Task 8: `tools.Checker`: run, end condition, cleanup, faults

**Files:**
- Create: `src/main/java/checker/RunId.java`, `src/main/java/checker/Api.java`, `src/main/java/checker/FollowMix.java`, `src/main/java/checker/EndCondition.java`, `src/main/java/checker/Lag.java`, `src/main/java/checker/Snapshots.java`, `src/main/java/checker/Cleanup.java`, `src/main/java/checker/Faults.java`, `src/main/java/tools/Checker.java`
- Modify: `src/main/java/app/Launcher.java` (`checker` → `tools.Checker`)
- Test: `src/test/java/checker/RunIdTest.java`, `FollowMixTest.java`, `EndConditionTest.java`, `ApiTest.java` (Javalin fake), `CleanupTest.java` (Postgres + Redis), `SnapshotsTest.java` (Postgres)

**Interfaces:**
- `RunId.of(long epochSeconds) -> String`; `RunId.username(String runId, int n) -> String`; `RunId.gameMarker(String runId) -> "-rp" + runId + "-"`.
- `public record Session(String userId, String sid)`. `Api(URI gateway, int tries = 3, Duration between = 1 s)`: `Session signup(String username, String password)` (returns `sid` and userId), `JsonObject follow(Session, String kind, String id)`, `JsonObject patchAlerts(Session, String followId, JsonObject alerts)`, `void deleteFollow(Session, String followId)`, `void logout(Session)`. Retries on 5xx and I/O errors; after the last try throws `SetupException`.
- `FollowMix.plan(long seed, int users, List<GameRef> games, AlertCatalog catalog) -> List<UserPlan>`; `record GameRef(String gameId, String league, String homeTeamId, String awayTeamId)`; `record UserPlan(int n, boolean twoTabs, List<FollowPlan> follows)`; `record FollowPlan(String kind, String targetId, JsonObject alerts)` (`alerts` = the PATCH body's `alerts`, null for "keep defaults"). Shares, probabilities, innings, available-only per Global Constraints; the "every switch off" row sets every available type for that league and kind to `{enabled:false}`.
- `EndCondition(LongSupplier clock, BooleanSupplier feedDone, Supplier<List<String>> notFinal, Supplier<Map<String, Long>> lag, Duration steady, Duration timeout)`; `State poll()` with `enum Kind { WAITING, DONE, TIMED_OUT }` and `String reason` (on timeout: the not-final games and the lagging `group/partition=lag` entries). Steady clock resets whenever any lag > 0 or a precondition is false; timeout counts from the first poll where `feedDone` is true.
- `Lag.read(Admin admin, List<String> groups) -> Map<String, Long>` keyed `group/topic-partition`, end offset − committed (missing committed = end offset).
- `Snapshots.follows(DataSource, List<String> userIds) -> List<FollowSnapshot>`; `Snapshots.history(DataSource, List<String> userIds, String gameMarker) -> List<HistoryRow>`.
- `Cleanup.run(Api api, List<Session> sessions, Map<String, List<String>> followIdsByUser, DataSource db, JedisPool pool, String runId)` — spec 7 steps 1–3; `Cleanup.cleanAll(DataSource db, JedisPool pool) -> int` — deletes every `ck\_%` user's history, reads, users (one transaction), their `unread:` keys, then `DEL sub:built`.
- `Faults.apply(String fault, …)`: `srem` (after `T0`: `SREM` one user from one of their `sub:*` sets, chosen so the first replayed alert they'd get is affected; expects `history_missing`), `dup` (after the first arrival: `PUBLISH gw:<gatewayId from conn:<userId>>` the same notification payload again; expects `socket_duplicate`), `follow` (after `T0`: PATCH one switch and back; expects `follow_changed`). A fault run passes iff its expected kind is present and every finding is that kind.
- `tools.Checker` env: `GATEWAY_URL` (default `http://gateway:8080`), `EPS_URL`, `KAFKA_BOOTSTRAP` (via `KafkaSettings.bootstrap()`), `Db.fromEnv("checker")`, `CHECK_SET`, `CHECK_SPEED`, `CHECK_USERS`, `CHECK_SEED`, `CHECK_KEEP`, `CHECK_FAULT`, `CHECK_MODE` (`run` default, `clean`), `RUN_TIMEOUT_SECONDS` (300), `CHECKER_PASSWORD`. Order: run id → load set → `Replay.prime` → users and follows (`FollowMix`) → sockets open (wait until every socket has received `hello`, up to 60 s) → `T0` and start snapshot → fault (if any) → `Replay.start` → `EndCondition` until DONE/TIMED_OUT → end snapshot → `PlayReader.read(…, runStartMillis, runId)` → `Oracle` → `Snapshots.history` → `Verifier` + `FollowFreeze` → `Report` printed → cleanup unless keep (on KEEP prints one username and the password) → exit 0 on PASS (or matching fault), 1 otherwise. Any `SetupException` prints `SETUP FAILED` with the cause and exits 2 after cleanup.

- [ ] **Step 1: Failing tests.** `RunIdTest`: `sixBase36Chars` (for now and 2030-01-01), `usernameFitsTwentyChars` (n = 99,999), `gameMarker`. `FollowMixTest`: `sameSeedSamePlan`, `sharesWithinTwoPercent` (500 users), `twoTabsAboutTenPercent`, `onlyTargetsFromTheSet`, `onlyAvailableTypesSwitched`, `allOffRowHasEveryAvailableTypeOff`, `inningsOneToThree`. `EndConditionTest` (fake clock and suppliers): `zeroLagForNineSecondsIsNotDone`, `tenSecondsSteadyIsDone`, `lagBlipResetsTheClock`, `notFinalHoldsIt`, `feedNotDoneHoldsIt`, `timeoutNamesGamesAndPartitions`. `ApiTest`: `setupRetriesTransientErrors` (504, reset, then 201 → ok; 3× 504 → `SetupException`), `noRetryOn4xx`. `SnapshotsTest`: `followsIncludeUpdatedAtAndExpiresAt`, `historyOnlyThisRunsGames`. `CleanupTest`: `removesOnlyThisRunsUsers` (another run's `ck_` user and a normal user survive), `removesHistoryReadsAndUnreadKeys`, `cleanAllRemovesEveryCheckerUserAndDeletesMarker`.
- [ ] **Step 2: Run** `mvn -q test -Dtest='checker.*Test'` → FAIL.
- [ ] **Step 3: Implement** as in Interfaces; add `LauncherTest.checkerIsAService`.
- [ ] **Step 4: Run** `make test` → PASS.
- [ ] **Step 5: Commit** `"tools.Checker: seeded users, frozen follows, steady end, verification, cleanup, self-test faults"`.

### Task 9: In the cluster: Job, policies, make targets, CI

**Files:**
- Create: `deploy/helm/sports-notifier/templates/checker-cronjob.yaml` (as `simulator-cronjob.yaml`: name `checker`, `SERVICE=checker`, `baseEnv`, `GATEWAY_URL`, `EPS_URL`, the Postgres env/secret the services use, `CHECK_*` from `values.checker`, resources `values.checker.resources` = requests `{cpu: 200m, memory: 512Mi}`, limits `{memory: 1Gi}`, `backoffLimit: 0`)
- Create: `scripts/check.sh` (stamp a Job from `cronjob/checker` with `KEY=VALUE` args as `simulate.sh`, wait for the pod, `kubectl logs -f`, exit with the Job's result: 0 succeeded, 1 failed)
- Modify: `values.yaml` (`checker:` defaults), `templates/network-policies.yaml` (gateway ingress also from pods named `checker`; Postgres `values` list adds `checker`; `event-processing` callers already have `checker` from Task 5), `Makefile` (`check:` → `@scripts/check.sh CHECK_SET=$(SET) CHECK_SPEED=$(SPEED) …` passing `USERS SEED KEEP FAULT` when set; `check-clean:` → `CHECK_MODE=clean`; `check-selftest:` → `ci` at 60× with `FAULT=srem`, then `dup`, then `follow`, each must exit 0; `.PHONY`), `.github/workflows/ci.yml:132` → `make cluster up smoke check e2e` with `SET=ci SPEED=60`
- Test: `scripts/smoke-test.sh`'s stranger-pod check unchanged (still refused)

- [ ] **Step 1: Kafka reachability.** `kubectl get networkpolicy -n default` on the laptop cluster: confirm Strimzi's own policy admits any pod on the client port (Stage 1 relies on it). If it does not, add `checker` to it in the chart; note which in the commit.
- [ ] **Step 2: Implement** the files above.
- [ ] **Step 3: Run** `helm lint deploy/helm/sports-notifier` and `make up` → OK; `make smoke` → PASS (stranger still refused).
- [ ] **Step 4: Run** `make check SET=ci SPEED=60` → report ends `PASS`, exit 0; `make check SET=ci SPEED=60 KEEP=1`, sign in as the printed user and see the run's pushes; `make check-clean` → removes it.
- [ ] **Step 5: Run** `make check-selftest` → three fault runs each exit 0 (each printed report shows its one expected finding).
- [ ] **Step 6: Run** `make check SET=evening SPEED=10` → `PASS`; save the report for the PR body.
- [ ] **Step 7: Run** `scripts/persistence-check.sh` → PASS.
- [ ] **Step 8: Commit** `"make check, check-clean, check-selftest; checker Job and policies; CI runs the ci set"`, push, and confirm CI's `deploy-check` passes.

### Task 10: Docs and quiz

**Files:**
- Modify: `CLAUDE.md` and `AGENTS.md` (identical edits): "New in Phase 4.1" bullet (replay sets and `make replay`, `make check` / `check-clean` / `check-selftest`, `SERVICE=checker|replay`, the oracle's shared vs independent parts, run-id scoping, the versioned marker, `follows.updated_at`); "Next step" → Phase 4.2; Stack/size line (Java test count from `make test`); Must-fix list drops #16.
- Modify: `DESIGN.md` build order 4.1 → **Merged** (PR number, date) after merge.
- Create: `docs/study/phase-4-quiz.md` with a `## 4.1` section (8–10 questions with answers, in the style of `phase-3-quiz.md`: why the oracle doesn't read Stage 1's output; why follows freeze; why 10 s of zero lag; the socket "missing" rule; why the self-test exists; what `sub:built`'s run id fixes).
- [ ] **Step 1: Write** the docs.
- [ ] **Step 2: Commit** `"Docs and quiz for Phase 4.1"`; open the PR (body: what changed, the evening report, the self-test output, closes #16 and #18); request the final review; fix findings; merge when CI is green.
