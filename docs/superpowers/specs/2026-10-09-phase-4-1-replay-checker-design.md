# Phase 4.1: Replay and the correctness checker — design

Status: draft for review, 2026-10-09. Parent document: `DESIGN.md` (decisions 8, 14, 15). If this spec and `DESIGN.md` disagree, `DESIGN.md` wins and this spec gets fixed. `DESIGN.md` is updated with this spec: Phase 4 is split into 4.1–4.3, the chaos suite is built and run on k3d in 4.2 (and run again on EKS in Phase 6), and alert waves 2 and 3 move to Phase 7.

Phase 4 is three sub-phases: **4.1 replay and checker (this spec)**, 4.2 chaos suite on k3d, 4.3 traces and metrics. Each gets its own spec, plan, and PR.

## Goal

Make decision 14's claim checkable on the laptop: replay recorded ESPN games through the real pipeline, have synthetic users receive them over real sockets, and have a checker prove that every synthetic user got every push they should have, exactly once, in game order — in Postgres history and on every open socket. 4.2 then runs the same checker while killing things.

Also in 4.1: issues #16 and #18 (the `sub:built` marker can claim the sets are built when they are not), because the checker's independent oracle is what proves that fix.

Not in 4.1: chaos (4.2); tracing, Prometheus, Grafana (4.3); alert waves 2 and 3 (Phase 7); issue #36, the live check, which needs a live game and the user, and is independent of this phase (due before Phase 5).

### Done when

1. `make test` passes, including every test in section 10.
2. `make cluster up smoke` and `make check SET=ci SPEED=60` pass on the laptop and in CI.
3. `make check SET=evening SPEED=10` passes on the laptop; its report is pasted in the PR.
4. `make check-selftest` passes on the laptop: each injected fault (section 6.4) makes the checker fail with the right finding.
5. `make replay-detectors` passes; golden timelines unchanged.
6. `scripts/persistence-check.sh` passes on the laptop.
7. Docs updated (section 12), and `docs/study/phase-4-quiz.md` has a 4.1 section. The user is quizzed only on request.
8. The PR from `phase-4-1-replay-checker` is merged.

### Branch

`phase-4-1-replay-checker`, worktree `.worktrees/phase-4-1-replay-checker`, from main at `92fb31c`. Rebuild the laptop cluster once, after the `sub:built` change (task 1) and the migration (task 2).

---

## 1. Run ids and scoping

Every run of the checker or the replay has a **run id**: the run's start time in epoch seconds, written in base 36 (6 characters, e.g. `t3k2ab`). It names everything the run creates:

| What | Name |
|---|---|
| Replayed game ids | `<league>-rp<runId>-<espnId>`, e.g. `nfl-rpt3k2ab-401872971` |
| Synthetic usernames | `ck_<runId>_<n>`, `n` from 0, e.g. `ck_t3k2ab_417` (at most 20 characters, `[a-z0-9_]`, as `AccountService` requires) |

**Every check is scoped to this run's game ids.** The oracle reads only plays whose game id contains `-rp<runId>-`; the history comparison selects only those game ids; socket messages about any other game are counted as "outside the run" in the report and otherwise ignored. A synthetic user with a league follow can receive live ESPN pushes (when the ESPN poller is on) or another run's pushes; none of those can fail or pass a check.

Fresh game ids also mean every run starts from empty state (`game:`, `det:`, `outbox:`), so runs repeat without wiping anything, the same reason the simulator uses `<league>-sim<start>-<slot>`.

## 2. Replay

### 2.1 Replay sets

**Problem.** The recordings (`~/sportsnotifier-recordings/<league>/<UTC time>.json.gz`, every 30 s, 2026-10-02 to 2026-10-05, 81 MB) are whole scoreboards, most of them unchanged from the one before, and live only on the Mac.

**Fix.** `scripts/pack-replay.py <set> <league>:<espnId>[@<offsetMinutes>] ...` trims each recording to the chosen games (the trimming `trim-espn-fixture.py` uses, now `scripts/espn_trim.py`) and writes `src/main/resources/replay/<set>.jsonl.gz`: one line per kept snapshot, `{"game": "nfl-401872971", "league": "NFL", "t": <ms>, "event": {...}}`, in time order. The file is in the JAR, so the image carries it to the cluster and to EKS.

Each game has its **own timeline** (revised in the plan, 2026-10-09): it starts at the game's offset at its first kept snapshot; a snapshot is kept when the game's trimmed event changed; and a gap longer than 60 s is cut to 60 s. A recording starts hours before kickoff and NHL intermissions are 18 minutes of nothing, so whole-board recorder time would make a run sit through them, and games recorded on different days (the four golden games span three) could never overlap. Offsets spread the games out the way a real evening does.

The script refuses a game whose recording does not start before the game (status scheduled) and run through its final: the replay primes on the pre-game board (section 2.3) and the checker waits for every game to be final (section 5).

Two sets, chosen in the plan from the recordings:

| Set | Games | Use |
|---|---|---|
| `ci` | 2 (one NFL, one NHL), at 60× about 4 minutes | CI, quick laptop runs |
| `evening` | about 10, overlapping in time, all four leagues, including the 4 golden games (`nfl-401872971`, `nhl-401891803`, `nhl-401891831`, `nhl-401892443`) and at least one each of overtime, a lead change late, a delay | The real run; 4.2's chaos runs |

### 2.2 `RecordedScoreFeed`

A `ScoreFeed` (in `feed/`) over one replay set, used through the real `Poller` exactly as `SimulatedScoreFeed` is:

- **Virtual clock.** Replay time `e = (now − clockStart) × SPEED`. `fetch(league)` returns, for each of that league's games, its latest snapshot with `t ≤ e` (a game whose timeline has not begun is absent), as `GameSnapshot`s parsed by `EspnScoreFeed.parse` (one parser, shared).
- **Ids.** Every game id becomes `<league>-rp<runId>-<espnId>`.
- **Times.** Each game's scheduled start is moved to when its own timeline begins (run start + its offset at SPEED), so replayed games show as today in `/games` and the UI can be used to watch a run.
- **`done()`** is true once `e` is past the set's last snapshot; **`gameIds()`** lists the run's game ids.

The poller polls every `30 s / SPEED` (the recorder's cadence, sped up), at least 500 ms.

### 2.3 Priming

**Problem.** A game follow needs the game's `game:` record (`FollowService` looks up its league), and that record is written only when the accept script takes the game's first play. But the follows must exist, frozen, before the replay starts (section 3).

**Fix.** A replay runs in two steps. **Prime:** before the clock starts, the feed answers each game's first snapshot, the poller sends one pass, so every game gets its pre-game play (`GAME_SYNC`, accepted, no alerts) and its `game:` record. **Run:** the clock starts. The checker creates its users and follows between the two. The primed plays are part of the run: they are on `game-events` with the run's game ids, and the oracle reads them like any other play.

### 2.4 Running a replay on its own

`simulator.ReplayMain` (`SERVICE=replay`) runs a replay without the checker, for demos: `make replay SET=evening SPEED=10`, a one-off Job from a suspended CronJob, as `make simulate` does. The checker runs the same `Replay` class in its own process (section 9), so one Job is one run and the checker knows exactly when the feed is done.

**Known difference from live.** The script's heals (the 10 s no-score heal, the goal heal) are measured in wall time, so at 10× a 90-second ESPN glitch lasts 9 s and may not heal the way it would live. This does not affect the checker: the oracle's truth is the accepted plays, whatever the script accepted.

## 3. Synthetic users and frozen follows

### 3.1 Users

The checker signs up `USERS` users (default 500) through `POST /api/v1/auth/signup`, which returns a session cookie (signup is not throttled, so no login is needed). Each user's follows are made through `POST /api/v1/follows`, so they go through the real save path: Postgres, then the `sub:*` sets.

The mix comes from a seeded random generator (`SEED`, default 1), so a run's users and follows are repeatable. It covers every branch of the audience formula (3.3 spec, section 5.1):

| Share | Follows | Exercises |
|---|---|---|
| 40% | one team in the set, switches randomized | team switches |
| 15% | both teams of one game | both teams' switches combined |
| 15% | a team and that team's game, different switches | game follow wins over team |
| 10% | a league and a team in it | team wins over league for its games |
| 10% | a league only | league follows |
| 10% | a team and its game with every switch off | a game follow with the alert off still decides: nothing for that game |

Randomized switches are each on with probability 0.7; MLB `END_OF_INNING` follows pick 1–3 innings. 10% of users open a second socket (a second tab), which exercises the `conn:` set with two entries.

### 3.2 The freeze rule

**Problem.** The expected pushes are computed from the follows. If a follow changes during the run, the expected set is ambiguous and a real loss could hide behind it.

**Fix.** Follows are created before the run step (section 2.3). Then the checker records `T0` and a **start snapshot** of every follow of the run's users: id, user, kind, target, league, alerts, `created_at`, `updated_at`, `expires_at`. After the run it takes an **end snapshot**. The check fails if:

- the snapshots differ in any field other than `expires_at`; or
- any follow has `created_at > T0` or `updated_at > T0`; or
- an `expires_at` that was null at the start is set at the end to a time before the run ended.

The one allowed change is `GameFollowExpiry` marking an ended game's follow to expire a day later: that is the system, not a user, and it changes no audience during the run.

**Migration.** `V2__follows_updated_at.sql` adds `follows.updated_at timestamptz NOT NULL DEFAULT now()`. `FollowStore`'s switch update sets it to `now()`. Without it, a switch flipped and flipped back during the run would leave both snapshots equal.

## 4. Listening

One socket per user to `/api/v1/ws` through the gateway's Service (10% of users have two), using `java.net.http.WebSocket` with the user's `sid` cookie. Each socket client behaves as `docs/api/websocket.md` asks of a client: it answers the server, sends `ping` every 25 s (the server closes idle sockets after 60 s), and reconnects after a close with backoff and jitter. A `hello` arriving after a notification (issue #24) is tolerated, not a finding.

Each socket keeps an in-memory log: every `notification` message (its body and arrival time), and every disconnect and reconnect (times, close code). Other message types are ignored.

## 5. When the run ends

The run ends only when all of these hold:

1. the replay feed is done (`done()`, section 2.2);
2. every replayed game's `game:<id>` status is final (the accept script's state, not the feed's);
3. the consumer groups `stage1`, `stage2`, and `history` (`Topics.STAGE1_GROUP`, `STAGE2_GROUP`, `HISTORY_GROUP`) have had zero lag on every partition, checked once a second through Kafka's `AdminClient`, continuously for 10 s.

Zero lag for a moment is not enough: a worker between `poll`s, or a rebalance, can show zero while records are still on their way. 10 s of zero lag also leaves time for Stage 2's last pub/sub messages to reach the sockets.

If the run has not ended `RUN_TIMEOUT` after the feed is done (default 5 minutes), the checker fails with which condition was still false (which games not final, which group and partition still lagged).

## 6. The oracle and verification

### 6.1 The oracle: expected pushes, recomputed

**Problem.** Comparing what users received with what Stage 1 wrote to `notifications` only proves Stage 2 delivered what Stage 1 sent. A Stage 1 bug — a crash that loses or repeats a moment, or a `sub:*` set that disagrees with Postgres — would be invisible.

**Fix.** The checker computes the expected pushes itself, from the two sources of truth:

1. **Plays.** Read `game-events` from the offsets at the run's start time (`offsetsForTimes(runStart)`), keeping only the run's game ids. One game is one key, so its plays are in one partition, in order.
2. **Follows.** The start snapshot (section 3.2).
3. **Per game,** from empty memory (fresh game ids, as Stage 1 sees them): `Detectors.run` on each play, in order, then each alert's audience computed **in plain Java from the follows**, with the same formula as `AlertAudience`, and then the same per-user combining step as Stage 1 (one push per user per moment, the final its own push).

The result is the expected list of `(user, push)` per game, in order, with each push's body.

**Shared and independent parts.** The oracle shares the detectors and the combining step with Stage 1, so it does not test them; the golden replays do. Everything else is independent: which users hear an alert (Postgres follows, not Redis sets), dedup, routing, delivery, history. To share the combining step without copying it, it moves out of `MomentFanout.send` into a pure function both call (no change in behavior; the golden replays and Stage 1's tests prove that).

### 6.2 History

From Postgres, the run's users' rows for the run's game ids. The check fails on:

- an expected `(user, messageId)` with no row (**missing**);
- a row that is not expected (**unexpected**);
- a row whose body differs from the expected body, ignoring `createdAt` (**wrong body**).

Duplicates cannot exist in history (the primary key is `(user_id, message_id)`); the socket checks are where a duplicate would show.

### 6.3 Sockets

Per socket, over the run's game ids:

- **Duplicate:** the same `messageId` arrived twice on one socket. (A user with two tabs gets each push once per tab.)
- **Out of order:** within one game, the pushes that arrived are not in the expected order. There is no rule across games: different games are processed in different lanes.
- **Missing:** an expected push did not arrive. It is allowed only if (a) history has it, and (b) the socket was disconnected at some time between the push's history `created_at` (the timestamp of its `notifications` record) and the arrival of the first push this socket did receive whose `created_at` is later (or the end of the run). One user's pushes are in one lane and Stage 2 delivers them in order, so if a later one arrived and the socket never dropped in between, the missing one was published to an open socket and lost.
- **Unexpected:** a push about the run's games that the oracle did not expect.

### 6.4 Self-test: proof the checker can fail

A checker that always passes proves nothing. `make check-selftest` runs the `ci` set three times, each with one fault the checker injects through the real paths after `T0`, and expects a failure with the matching finding:

| `FAULT` | What the checker does | Must report |
|---|---|---|
| `srem` | `SREM`s one synthetic user from one of their `sub:*` sets (Redis lost a write: #16, #18) | missing, in history and on the socket |
| `dup` | re-publishes one delivered push on the user's `gw:<gatewayId>` channel | duplicate on that socket |
| `follow` | flips one switch through `PATCH /api/v1/follows/{id}` and back | follow changed (freeze rule) |

A fault run exits 0 only when the expected finding is reported; anything else (no finding, or other findings) exits non-zero.

### 6.5 The report

Printed at the end, and the Job's exit code is non-zero on any failure:

```
run t3k2ab  set evening  speed 10×  users 500 (52 with two tabs)  games 10
plays 1,832   expected pushes 41,207
history   missing 0   unexpected 0   wrong body 0
sockets   missing 0 (allowed in disconnects: 0)   duplicate 0   out of order 0   unexpected 0
follows   frozen ✓
outside the run: 0 messages
latency detectedAt → socket: p50 212 ms  p99 640 ms  (preview; 4.3 measures it properly)
PASS
```

Each failing finding lists up to 20 examples (user, game, messageId, times) and the total.

## 7. Cleanup

**Problem.** Postgres keeps its data on the Mac (`~/.sportsnotifier/postgres`) across `make down`, so every run's 500 users would accumulate. Deleting the `users` rows is not enough: only `follows` cascades from `users` (`notifications` and `notification_reads` have no foreign key), and the users would stay in the Redis `sub:*` sets, where Stage 1 would keep sending to them.

**Fix.** After the report, unless `KEEP=1`:

1. Through the API, with each user's session: `DELETE /api/v1/follows/{id}` for each follow (the real save path removes them from the `sub:*` sets), then `POST /api/v1/auth/logout`.
2. In one Postgres transaction: delete the run users' `notifications` and `notification_reads` rows, then the users (`username LIKE 'ck\_<runId>\_%'`).
3. In Redis: `DEL unread:<userId>` for each.

`make check-clean` removes every leftover synthetic user (`ck_%`), from a crashed run or a `KEEP=1` run, without sessions: it deletes them in Postgres as in step 2 (follows cascade), then deletes `sub:built`, which makes user-pref rebuild the sets from Postgres (the path decision 8 already has for "Redis disagrees with Postgres"). Stage 1 pauses for the rebuild, a few seconds.

`KEEP=1` leaves the users and prints one username; the password is fixed (`CHECKER_PASSWORD`, default `checker-pass`) so the run can be inspected in the UI.

Replayed games' Redis state expires on its own (a day after the final); their Kafka records go with the topic's 7-day retention.

## 8. `sub:built` says what was built (#16, #18)

**Problem.** `sub:built` only says "the sets were built". #16: a `helm upgrade` that changes set shapes (3.3 added `…:ANY` and the innings sets) keeps the marker, so the rebuild is skipped and Stage 1 reads missing sets until the daily rebuild. #18: `SubRebuilder` notices a Redis restart by comparing Redis's `run_id` with one held in memory; when Redis and user-pref restart together, there is nothing to compare, and an AOF restart can keep the marker but lose the last second of `SADD`/`SREM`s.

**Fix.** `sub:built` becomes a hash `{version, runId}`:

- `version` is a constant, `FollowSets.SHAPE_VERSION` (starts at 2), bumped whenever set shapes change.
- `runId` is Redis's `run_id` at the time of the build, from `INFO server`.

`SubRebuilder` rebuilds when the marker is missing, its `version` differs from the constant, or its `runId` differs from Redis's current `run_id`; the in-memory `lastRunId` goes away. Stage 1's wait check treats "missing or a different version" as not built. A failed follow save still deletes the marker (unchanged).

**Why one fix.** Both bugs are "the marker says built when the sets are not". The checker's `FAULT=srem` self-test is the end-to-end proof that a Redis/Postgres disagreement is caught.

## 9. In the cluster

- **The checker** is `tools.Checker` (`SERVICE=checker`), one process per run: sign up, prime the replay, follow, `T0`, run the replay in-process, listen, wait for the end (section 5), verify, report, clean up. Started by `make check SET=… SPEED=… [USERS=…] [SEED=…] [KEEP=1] [FAULT=…]` as a one-off Job from a suspended CronJob (`templates/checker-cronjob.yaml`), as `make simulate` does; the make target streams its log and exits with the Job's result. `make check-clean` runs it in clean mode.
- **Network policy.** Checker pods may reach the gateway (API and socket), Event Processing (they run the poller), Kafka (reading `game-events` and group lag), Postgres (snapshots, history, cleanup), and Redis (game status, the self-test faults, cleanup). The stranger-pod check in the smoke test is unchanged.
- **Credentials.** The checker reads Postgres with the same secret as the services. It is a test tool in our own cluster; a read-mostly role is not worth the chart complexity now.
- **CI.** After `make smoke`, `make check SET=ci SPEED=60` runs (about 4 minutes), before `make e2e`. On failure the report is in the job log.
- **The ESPN poller** may stay on during a check; scoping (section 1) keeps its games out.

## 10. Tests

### 10.1 Unit

- `RecordedScoreFeed`: virtual clock picks the right board at boundaries; ids rewritten; start times shifted; `done()`; prime holds the clock at each game's first board.
- `pack-replay.py`: drops unchanged boards; refuses a game that does not start scheduled or end final (a small fixture).
- The oracle's audience against hand-written follows: each row of section 3.1's table, both teams' switches combined, a game follow with the alert off, `END_OF_INNING` innings, a league follow for a game in another league.
- The oracle reproduces the 4 golden games' alerts for one follower of each team (same alert ids as the golden timelines).
- Verification, from hand-built logs: each finding in sections 6.2 and 6.3 is reported (and only it); a missing push inside a disconnect with a history row is allowed, outside one is not, and with no history row is not; out of order only within a game; other games' messages are "outside the run".
- The freeze rule: changed alerts, a deleted follow, an added follow, `updated_at > T0`, and `expires_at` set to a future time (allowed) or a past time (not).
- The end condition with a fake clock and fake lag: zero lag for 9 s then a record is not the end; 10 s is; not-final games hold it; the timeout names the failing condition.
- Run id and username format: 6 base-36 characters; usernames within 20 characters for `n` up to 99,999.

### 10.2 With Redis and Postgres

- `sub:built`: missing → rebuild; old `version` → rebuild, Stage 1 waits, then resumes; different `runId` → rebuild; same both → no rebuild.
- `FollowStore`: a switch change sets `updated_at`; creating a follow sets both timestamps.
- Cleanup removes the run's users, follows, history, reads, and `unread:` keys, and no other user's; `check-clean` removes every `ck_` user and deletes `sub:built`.
- The extracted combining step: Stage 1's existing fan-out tests pass unchanged.

### 10.3 Cluster

- `make check SET=ci SPEED=60` passes (CI and laptop).
- `make check SET=evening SPEED=10` passes (laptop; report in the PR).
- `make check-selftest` passes (laptop).

## 11. Order of work

1. `sub:built` as `{version, runId}` (#16, #18), with its tests.
2. Migration `V2`: `follows.updated_at`; `FollowStore` sets it.
3. Extract the per-user combining step from `MomentFanout` into a pure function; Stage 1's tests and `make replay-detectors` unchanged.
4. `pack-replay.py` and the two sets; `RecordedScoreFeed`, priming, `Replay`; `ReplayMain` and `make replay`. Watch one replay in the UI.
5. The oracle (plays + follows → expected pushes), with the golden-game test.
6. The socket client and its log.
7. Verification and the report.
8. `tools.Checker`: users, follows, freeze snapshots, the end condition, cleanup and clean mode, the three faults.
9. Chart: checker CronJob, network policy, `make check` / `check-clean` / `check-selftest`; CI step.
10. Docs and the quiz.

## 12. Docs to update

- `DESIGN.md`: build order step 4 split into 4.1–4.3, with 4.2 building and running the chaos suite on k3d and Phase 6 running it on EKS; alert waves 2 and 3 moved to step 7; decision 14 notes the oracle is recomputed from `game-events` and Postgres, and the run-id scoping; decision 8 notes the versioned marker.
- `CLAUDE.md` and `AGENTS.md`: "New in Phase 4.1", the new make targets, `SERVICE=checker` and `SERVICE=replay`, the next step.
- `docs/study/phase-4-quiz.md`: a 4.1 section.
- Close #16 and #18 with the PR.

## Known gaps accepted in 4.1

- **The oracle shares the detectors and the combining step with Stage 1**, so a detector bug is in both and the checker cannot see it. The golden replays cover detectors; `make replay-detectors` stays the check.
- **Heals run on wall time**, so a sped-up replay may accept a slightly different set of plays than live would. The checker is unaffected (section 2.4).
- **Recordings are 30 s apart**, so plays between polls merge into one snapshot, as they would have live at that cadence.
- **One broker, one copy of each service** in 4.1. Two copies and the kills are 4.2.
- **Latency is a preview**: `detectedAt` and socket arrival are on one machine here; 4.3 measures with traces.
