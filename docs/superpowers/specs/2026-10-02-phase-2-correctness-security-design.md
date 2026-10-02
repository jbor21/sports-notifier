# Phase 2: Correctness and security — design

Status: draft for review, 2026-10-02. Parent document: `DESIGN.md` (build order step 2, "Security and correctness still to fix", decisions 5, 10, 13, 15). If this spec and `DESIGN.md` disagree, `DESIGN.md` wins and this spec gets fixed.

## Goal

Fix the known bugs in the course pipeline that survive into Phase 3, close the security holes, and make the ESPN poller safe to restart and safe to run twice. Redis stays the only store. Postgres and Kafka are Phase 3.

### Done when

1. `make test` passes, including every new test in this spec.
2. `make cluster up smoke` passes on the laptop and in CI, with the stronger smoke test (section 8).
3. A live game was watched once by hand with the poller on, and a follower of the team that did *not* score got the score.
4. `DESIGN.md` marks what Phase 2 closed in "Security and correctness still to fix." `CLAUDE.md` and `AGENTS.md` "Open problems" match.
5. `docs/study/phase-2-quiz.md` exists. The user is quizzed only on request.
6. The PR from `phase-2-correctness` is merged.

## Scope

**In Phase 2:** the fan-out bug and its N+1, event ordering (`seq`), the poller (ids, memory, retry, polling speed, the `ScoreFeed` interface), game expiry, account security, network policies, startup retry, an ESPN cloud-IP check, docs.

**Left to Phase 3 by `DESIGN.md`:**

| Item | Closed by |
|---|---|
| Crash between the Lua script and the publish | Decision 5's two-state claim |
| Worker dies before ack | Kafka consumer-group rebalance |
| Worker retries | Need per-user dedup first (decision 6) |
| Gateway `notify:*` broadcast | Decision 13 |
| Per-sport alert catalog, NBA without per-basket alerts, live clock in the UI | Decision 15, wave 1, and the API spec |

**Dropped:** the provider comparison (MySportsFeeds, MLB and NHL feeds). ESPN is the only source for now. `DESIGN.md` decision 10 is updated to match.

Phase 2 keeps `GAME_START`, `SCORE_UPDATE`, and `GAME_END`, and adds one quiet type, `GAME_SYNC` (section 3). An NBA basket can still notify in Phase 2; decision 15 removes that in Phase 3.

## Order of work

One branch, `phase-2-correctness`, in the worktree `.worktrees/phase-2-correctness`. One commit or more per task. CI green after each task. One PR.

| # | Task | Depends on | Built |
|---|---|---|---|
| 1 | Fan-out: both teams, set operations, smoke test tightened | — | inline, test-first |
| 2 | Event ordering: `seq`, Lua rewrite, simulator, burst test, dead code removed | — | inline |
| 3 | `ScoreFeed` interface and the poller | 2 | inline |
| 4 | Game expiry and the recent-games list | 2 | inline |
| 5 | Account security | — | inline |
| 6 | Network policies and the isolation smoke checks | 5 | subagent, walked through |
| 7 | Startup wait for Redis | — | subagent, walked through |
| 8 | ESPN cloud-IP check in GitHub Actions | 3 | subagent, walked through |
| 9 | Docs, `DESIGN.md` fix list, quiz file | all | inline |

"Inline" follows `CLAUDE.md`: core distributed code is built in small steps the user reads.

---

## 1. Fan-out matches both teams

**Problem.** `PubSubWorkerImpl.fanOutNotifications` reads `sub:team:<event.team>`, the tagged team only. A score is tagged with the scorer and `GAME_START` / `GAME_END` with the home team, so followers of the other team miss them. Then it calls `SISMEMBER sub:notif:<type>` once per candidate, one Redis round trip per subscriber.

**Fix.** For each event, in one pipeline:

1. `SUNIONSTORE fanout:<eventId>` over `sub:team:<homeTeam>`, `sub:team:<awayTeam>`, `sub:team:<team>`, `sub:game:<gameId>`, `sub:league:<league>`. Null fields are skipped. `team` is kept so events that carry only `team` still match. In real data it is always one of the two teams, so it adds no one.
2. `SINTERSTORE fanout:<eventId> fanout:<eventId> sub:notif:<eventType>`.
3. `EXPIRE fanout:<eventId> 60`, a safety net if the worker dies mid-fan-out.

Then `SSCAN` the result in pages of 1,000, call `NotificationService.sendNotification(page, notification)` per page, and `DEL` the key. The union removes duplicates, so a user who follows both teams gets one notification. `FANOUT_PREFIX = "fanout:"` goes in `RedisKeys`. `GAME_SYNC` never reaches fan-out (section 3).

Multi-key commands are safe because there is one Redis, not Redis Cluster (`DESIGN.md`, "What we are not building"). Decision 15's "most specific follow wins" adds `SDIFF` steps to this same shape in Phase 3.

**Tests, written first and seen failing** (`PubSubWorkerImplTest`):

1. A BOS follower gets LAL's `SCORE_UPDATE` in LAL–BOS.
2. A BOS follower gets `GAME_START` for LAL–BOS (tagged with the home team, LAL).
3. A CHI follower gets an LV `SCORE_UPDATE` in CHI–LV (NFL).
4. A user following both teams gets exactly one notification.
5. A BOS follower without `SCORE_UPDATE` enabled gets nothing.
6. A follower of an unrelated team gets nothing.
7. No `fanout:*` key remains afterwards.

The existing fan-out tests keep passing.

**Smoke test.** The smoke user (Bob in section 8) follows **BOS**, the away team in `game-LAL-BOS`, with `GAME_START` and `SCORE_UPDATE` on. The test waits for a `game-LAL-BOS` history entry containing `has started` and one containing `scores!`. Both require the fix: the start is tagged LAL, and every score reaches a BOS follower whichever team scored.

---

## 2. Event ordering

**Rule:** the saved game state never goes back in game time.

**Problem.** The Lua script orders events by `timestamp`, which the poller fills from its own clock, and the poller's event ids are random UUIDs. The same play seen twice is two new events, and two overlapping pollers (a rolling update) both get through. A lagging poller can post an older score with a later clock reading and move the score backwards.

### 2.1 The `seq` field

`GameEvent` gains `long seq`, documented like the other fields. It is a 14-digit decimal number built from game progress. Bigger means later. Each block is readable in the digits.

| Block | Digits | Values |
|---|---|---|
| Phase | 1 | 0 before, 1 live, 2 final |
| Period | 2 | quarter, period, or inning; overtime keeps counting |
| Half (MLB) | 1 | 0 top, 1 middle, 2 bottom, 3 end; 0 for other sports |
| Clock | 5 | `36000 −` tenths of a second remaining; 0 for MLB |
| Outs (MLB) | 1 | 0–3; 0 for other sports |
| Stage | 1 | 0 in play, 1 delayed, 2 end of period, 3 halftime |
| Score total | 3 | home + away; breaks ties at a stopped clock |

Example: NFL, 4th quarter, 2:00 left (1200 tenths), in play, 24–21 → `1 04 0 34800 0 0 045` → `10403480000045`.

- **Phase 2 is only a real final:** ESPN's `completed` is true, or the status is canceled.
- **Postponed and suspended** take stage 1 (delayed) at the current position. Phase is 1 if the game had started, 0 if not. A suspended game that resumes days later keeps rising.
- **Score total** only matters when every block before it is equal. A correction after the clock has moved has a larger `seq` and is accepted with the lower score, which is correct.
- Every value is below 10¹⁴, and the simulator's (section 2.4) below 2 × 10¹⁵. Both are under 2⁵³, so Lua's numbers compare them exactly.
- `timestamp` stays wall-clock milliseconds and is used only for display.

The computation is a pure function in `feed/Seq.java` with its own unit tests (block layout, each sport, the example above, halftime above end of the 2nd quarter's last play, the touchdown-then-extra-point tie-break).

### 2.2 The Lua script

`EventProcessingServiceImpl.PROCESS_EVENT_SCRIPT` is rewritten. Order of checks:

1. **Id.** If `processed:<eventId>` exists → return `0` (duplicate). This is a read, not a claim.
2. **Seq.** If `seq ≤ lastSeq` → return `-1` (stale). The id stays unclaimed, so a stale event never burns an id. That property is what the course fix protected.
3. **Claim and write.** `SET processed:<eventId> 1 EX 3600`, then `HSET` the full state on every accepted event: `homeTeam`, `awayTeam`, `league`, `homeScore`, `awayScore`, `status`, `lastSeq`, `lastTimestamp`. Set the game key's TTL (section 4). `ZADD games:recent` (section 4). Return `1` (accepted).

Steps 1–3 are one script, so nothing can run between the read in step 1 and the claim in step 3. Keeping the three results distinct lets Phase 3 add "claimed but not produced, so produce again" (decision 5) as a fourth result.

The script no longer special-cases the event type. Every event carries the full state, and `GAME_START` no longer resets the score to 0–0.

`processEvent` publishes to the stream only when the result is `1` and the type is not `GAME_SYNC`.

### 2.3 The HTTP endpoint

`POST /events` on Event Processing:

| Case | Response |
|---|---|
| Missing `gameId`, `eventId`, `eventType`, or `seq ≤ 0`, or bad JSON | `400` |
| Accepted | `200 {"result":"accepted"}` |
| Duplicate | `200 {"result":"duplicate"}` |
| Stale | `200 {"result":"stale"}` |
| Redis unreachable | `5xx` |

Today it answers `202` for everything. The poller needs the difference (section 3).

### 2.4 The simulator

- `seq = runStartMillis × 1000 + n`, where `n` counts the events this run has sent for that game. Each run is newer than the last, so `make simulate` and `make smoke` can run repeatedly on one cluster, and the later of two overlapping runs wins (Phase 1 deferred item 5).
- Event ids are `sim:<runId>:<gameId>:<n>`, where `runId` is `runStartMillis`.

### 2.5 Dead code

`GameStateService.updateGameState` and its implementation are removed, with their tests. The unread `gameStateService` field and constructor parameter in `EventProcessingServiceImpl` are removed. The script is the only writer of game state, and nothing describes the old method as defense in depth.

### 2.6 Tests

- Existing event-processing tests move to `seq`.
- Same id twice → second is `duplicate`. Lower `seq` → `stale`, and its id stays unclaimed. Equal `seq` with a new id → `stale`.
- A retry of an accepted event returns `duplicate`, not `stale`.
- `GAME_SYNC` writes state but adds nothing to the stream.
- A missing `seq` → `400`.
- **`EventProcessingBurstTest` (new).** 50 threads post 50 events for one game at once, with `seq` 1…50 and home score equal to `seq`. The final saved `homeScore` is 50 and `lastSeq` is 50, every run. The course measured 43 → 48 → 50 by hand. This makes it a test that runs on every push.

### 2.7 Known limits (accepted)

- A score correction at the same position (same clock, lower total) is rejected as stale. Phase 3's detectors handle corrections.
- If play resumes at exactly the same position after a delay, that snapshot is not newer. The status stays delayed until the clock, an out, or a score moves.
- A clock put back by an official (for example +0.4 s after a review) makes a few snapshots look older. It heals on the next snapshot with less time left, which carries the full score.

---

## 3. The `ScoreFeed` interface and the poller

### 3.1 Structure

| Piece | Job |
|---|---|
| `model/GameSnapshot` | One game at one moment, independent of the source: game id, league, teams, scores, phase, period, half, clock tenths, outs, stage, normalized status, scheduled start time |
| `feed/ScoreFeed` | Interface: `List<GameSnapshot> fetch(String league)`. Throws `FeedBlockedException` on 403 or 429 |
| `feed/EspnScoreFeed` | The ESPN adapter. The parsing moves here from `EspnPollerMain` |
| `feed/Seq` | Snapshot → `seq` (section 2.1) |
| `feed/PollDecision` | Pure: (saved game record or none, snapshot) → the one event to send, or nothing |
| `simulator/EspnPollerMain` | The loop only: fetch, decide, post, schedule. The chart now gives it `REDIS_HOST` and `REDIS_PORT` |
| `gateway/EspnProxyHandler` | `/api/upcoming` reads through `EspnScoreFeed` instead of its own hard-coded `site.api.espn.com` request, so the gateway and the poller share one host and User-Agent setting. Otherwise the poller would work on AWS while the UI's upcoming list broke |

The interface was called `SportsDataProvider` in `DESIGN.md`. It is renamed `ScoreFeed` so it is not mistaken for the paid SportsDataIO service.

**Normalized status** values, saved in the game record's `status` field: `SCHEDULED`, `IN_PROGRESS`, `END_PERIOD`, `HALFTIME`, `DELAYED`, `POSTPONED`, `SUSPENDED`, `FINAL`, `CANCELED`. An ESPN status name the adapter does not know is mapped by its `state` (`pre`, `in`, `post`) and logged once per name.

**To confirm against real responses** during implementation: the ESPN status names for delays, postponements, suspensions, and cancellations, and where MLB's half-inning and outs live (`status.type.detail` or `shortDetail` such as "Bot 4th", and `situation.outs`).

### 3.2 No memory of its own

**Problem.** The poller keeps the last snapshot per game in a `HashMap`. A restart forgets it and re-sends `GAME_START` for every live game. It also stores the snapshot before the POST, so a failed POST loses that play.

**Fix.** The poller compares each ESPN snapshot with the game record the Lua script saved, `game:<gameId>`, read straight from Redis. The script writes that record only when an event is accepted, so:

- A poller restart loses nothing.
- A failed POST changes nothing. The next poll sees the same difference and sends it again. The same position gives the same `seq` and id, so the retry is safe. That is the retry, with no separate retry loop.
- Two pollers read the same record, and the script decides between them. The chart's poller goes from `strategy: Recreate` back to a rolling update once the two-poller test passes.

**POST results:**
- `2xx` → done.
- `4xx` → a bug in our event; logged as an error and not retried.
- `5xx` or a network error → nothing saved, so the next poll retries.

A snapshot rejected as stale (section 2.7) is re-sent each poll until the game moves on. These results are logged at debug level so they do not flood the log.

**Redis restarts.** Redis runs with its append-only file on a persistent volume (Phase 1), so a pod restart keeps every record. While Redis is down, the poller cannot read or post and sends nothing; it catches up on the first poll after. A crash can lose up to one second of writes, which can at worst re-send one accepted play. If Redis comes back empty, every game is first-seen and quiet (section 3.3).

### 3.3 `GAME_SYNC`: only alert on changes we saw happen

**Problem.** On a fresh start (a new cluster, or AWS coming up in Phase 5), the poller sees games already in progress and the day's finals for the first time. Today it sends "has started" and "Final" for every one of them.

**Fix.** A quiet event type, `GAME_SYNC`. The script saves its state like any event. It is never added to the stream, so nobody is notified. `PollDecision`, in priority order, at most one event per snapshot:

| Saved record vs snapshot | Event |
|---|---|
| No record (first sight: scheduled, live, or final) | `GAME_SYNC` |
| Record not final, snapshot is a real final | `GAME_END` |
| Record not started (phase digit of its `lastSeq` is 0, so scheduled, or delayed or postponed before the start), snapshot live | `GAME_START`, with the current score |
| Snapshot live and either score higher than the record's | `SCORE_UPDATE` |
| Status differs, or a score changed in any other way (a correction, including after a final) | `GAME_SYNC` |
| Nothing differs except the clock | none |

The event id is `<gameId>:<seq>`, for example `espn-401872948:10403480000045`. The game id already starts with `espn-`.

Descriptions keep today's wording, so history and the smoke test still match: `<TEAM> scores! <HOME> h - a <AWAY>` (the team whose score rose; if both rose in one snapshot, the one that rose more), `<HOME> vs <AWAY> has started`, and `Final: <HOME> h - a <AWAY>`.

### 3.4 Polling speed and blocks

- Each league is scheduled on its own. The next poll is in 5 s if, in the last poll, that league had a live game, or a scheduled game whose start time is within the next 10 minutes or has passed while the game is still not live (late starts). Otherwise 60 s. Without the start-time rule, "has started" could arrive up to a minute late. Both are settings: `ESPN_POLL_LIVE_MS` and `ESPN_POLL_IDLE_MS`. `ESPN_POLL_INTERVAL_MS` is removed.
- The ESPN host and the User-Agent are settings: `ESPN_HOST` (`site.api.espn.com` or `site.web.api.espn.com`) and `ESPN_USER_AGENT`. As of 2026-10-01, ESPN has been reported to refuse browser-impersonating User-Agents while honest client agents get through, and `site.api` refused a cloud request that `site.web.api` answered. Defaults: `site.web.api.espn.com` and an honest agent, `sports-notifier/<version> (+https://github.com/boboborenstein-collab/sports-notifier)`. The chart sets both for the poller and the gateway. If the section 9 check finds a better combination, only the defaults change. `DESIGN.md` decision 10 says this already.
- On `FeedBlockedException` the league's interval doubles, up to 5 minutes, with a warning per block. It resets on the first success.

### 3.5 Tests

- **Saved ESPN responses** in `src/test/resources/espn/`, captured live during implementation: MLB postseason, NFL Sunday, NBA and NHL preseason. Halftime, delayed, and postponed variants are made by editing a captured file when no live example is available, using status names confirmed from ESPN.
- `EspnScoreFeed` parses each file into the expected snapshots.
- `Seq` and `PollDecision` table tests, one row per case in 2.1 and 3.3.
- **Two pollers:** two poller instances, one recorded response, one Redis. Exactly one event per change is accepted, and the stream has one entry per change.
- **Failed POST:** a sink that fails once. The next poll sends the same id and it is accepted.
- **First sight:** a live game and a final game seen for the first time produce `GAME_SYNC` and no stream entry.

---

## 4. Old games expire

**Problem.** `game:*` keys never expire, and `GET /api/games` (`GameListingHandler`) scans every one.

**Fix**, all inside the Lua script on each accepted event:

- `EXPIRE game:<gameId>`: 24 hours when the new status is `FINAL` or `CANCELED`, otherwise 8 days, refreshed on every write. A postponed game that never resumes disappears after 8 idle days.
- `ZADD games:recent <now> <gameId>`, with the time from Redis `TIME`, then `ZREMRANGEBYSCORE games:recent -inf <now − 8 days>`.

`GameListingHandler` reads `games:recent`, fetches the records in one pipeline, skips ids whose record has expired, and leaves out `SCHEDULED` games. Upcoming games still come from `/api/upcoming`, the ESPN proxy, as today, so the current UI sees the same kinds of games as before.

A finished game that ESPN still shows after its record expired (the NFL scoreboard shows the whole week) comes back as a first sighting: a `GAME_SYNC`, with no second "Final" alert.

`GAMES_RECENT = "games:recent"` goes in `RedisKeys`. The TTLs are constants in the script's Java class and can be shortened later.

**Tests:** a final event sets a TTL of 24 hours or less, and a live event about 8 days. The list returns recent games and skips expired and scheduled ones. An expired final seen again is quiet.

---

## 5. Account security

### 5.1 Signup race

**Problem.** `UserPreferenceServiceMain.signup` does `GET username:<name>`, then later `SET`. Two concurrent signups for one name both pass.

**Fix.**
1. Write `user:<userId>` (random id, cannot collide).
2. `SET username:<name> <userId> NX`.
3. If the claim fails, delete `user:<userId>` and answer `409`.

A crash between 1 and 2 leaves an unreachable `user:` record, which is harmless. The reverse order could leave a name claimed with no account. `USER_PREFIX = "user:"` and `USERNAME_PREFIX = "username:"` move into `RedisKeys`.

**Test:** 20 concurrent signups for one name produce exactly one `200` and nineteen `409`s, and one `username:` key.

### 5.2 Password length

8 to 72 characters, otherwise `400` with a message saying which. bcrypt reads at most 72 bytes, so a longer password is rejected rather than silently cut short. The 72-byte limit is checked on the UTF-8 length.

### 5.3 Login throttle

In the gateway, which sees the client address:

- `login:fail:user:<username>`: `INCR` on each failure, `EXPIRE 900` on the first. At 5 or more, `/api/login` answers `429` with a "try again later" message until the key expires, without checking the password.
- `login:fail:ip:<ip>`: the same, with a limit of 20.
- A successful login deletes the username key.

The client address is the one Traefik saw: its `X-Real-Ip` header, else the **last** `X-Forwarded-For` entry (the one Traefik appended), else the socket address. Never the first entry: the client writes that one itself, so an attacker could send a new fake address with every request and never reach the limit. The choice is one small function, `ClientAddress.of(request)`, with its own tests. On AWS, with a load balancer in front of Traefik, the trusted hop changes and is set in Phase 5.

Accepted tradeoff: someone who knows a username can lock it for 15 minutes.

Prefix `LOGIN_FAIL_PREFIX = "login:fail:"` in `RedisKeys`. **Tests:** the 6th attempt on a username is `429` even with the right password. The 21st failure from one address is `429`, **including when every request carries a different made-up first `X-Forwarded-For` entry.** A success resets the username count.

### 5.4 Session cookie

`SessionAuth` sets `SameSite=Lax` on the `sid` cookie always, and `Secure` when the setting `COOKIE_SECURE` is true, on create and on logout. The default is true. `values-local.yaml` sets it false, because some browsers (Safari) and tools may refuse a `Secure` cookie on plain `http://localhost`. So the laptop and CI do not exercise `Secure`; Phase 5's HTTPS deploy does, with the default.

`Lax` blocks the cookie on requests other sites trigger and keeps it on a normal link click. `Strict` would log the user out on every arrival from a link.

---

## 6. Isolation

### 6.1 Between users (front door)

Every gateway route takes the user id from the session cookie, never from the request (checked in `GatewayMain`: `/api/me`, `/api/preferences`, `/api/history`, `/ws`). The smoke test makes that a guarantee instead of an accident (section 8).

### 6.2 Between services: network policies

**Problem.** Any pod can call any internal service. `user-pref:8081/prefs/<anyUserId>` answers whoever asks.

**Fix.** Kubernetes NetworkPolicies in the chart, one file, `templates/network-policies.yaml`. k3d's k3s enforces them out of the box. EKS needs a policy engine turned on in Phase 5.

| Pod (by `app.kubernetes.io/name`) | Ingress allowed from |
|---|---|
| `gateway` | Traefik in `kube-system`, port 8080 |
| `user-pref`, `game-state`, `notification` | `gateway` |
| `event-processing` | `espn-poller`, `simulator` |
| `redis` | any pod with `app.kubernetes.io/part-of: sports-notifier` |
| `pubsub-worker`, `espn-poller`, `simulator` | none |
| (default) | deny all ingress in the namespace |

- Health probes come from the node, not a pod. The implementation confirms k3s lets kubelet probes through, and adds a node-address rule if not.
- Egress stays open. The poller and the gateway's `/api/upcoming` reach ESPN.
- Service-to-service credentials (tokens, mutual TLS) are not in Phase 2. One owner, one cluster, and the gateway as the only way in make the network policy the layer `DESIGN.md` asks for.

---

## 7. Startup wait for Redis

**Problem.** On a fresh deploy, services that start before Redis exit with `UnknownHostException: redis`, and Kubernetes restarts them until Redis is up (Phase 1 deferred item 4).

**Fix.** `JedisPoolHolder.awaitRedis()`: `PING` every 2 seconds with a `[<service>] waiting for Redis` line, for up to 60 seconds, then exit with status 1 so Kubernetes restarts the pod as before. Every `*Main` that uses Redis calls it before building anything else. The poller and simulator call it too.

---

## 8. Smoke test

`scripts/smoke-test.sh` after Phase 2:

1. Wait for every workload to be ready (as today).
2. Sign up **Bob** and **Alice** with random names and 12-character passwords.
3. Bob follows team **BOS** with `GAME_START` and `SCORE_UPDATE`. Alice follows nothing.
4. Run the simulator with `SIM_WAVES=1`.
5. Within 60 seconds, Bob's history has a `game-LAL-BOS` entry containing `has started` and one containing `scores!`.
6. Alice's history is empty. `GET /api/history?userId=<Bob's id>` and `GET /api/preferences?userId=<Bob's id>` with Alice's cookie return Alice's own (empty) data.
7. A throwaway pod without the `part-of` label runs `curl http://user-pref:8081/health` with a short timeout. The call must fail.
8. On any failure: `make logs`, exit non-zero.

## 9. ESPN cloud-IP check

`.github/workflows/espn-check.yml`, started by hand (`workflow_dispatch`) and run **daily** on a schedule. A failed scheduled run emails the repo owner, so a rule change at ESPN shows up before a demo does. A run takes seconds, so daily costs a few free Actions minutes a month. From a GitHub-hosted runner, which sits in a data center like AWS does, it requests the NBA, NFL, MLB, and NHL scoreboards for each combination of host (`site.api.espn.com`, `site.web.api.espn.com`) and User-Agent (the honest agent, Java's default `Java-http-client/17`, a browser string). It writes a table of HTTP status and number of games per combination to the run's summary page. It fails if the configured default combination does not return 200 for every league. The first results are copied by hand into `docs/providers/espn-access.md` in task 9.

Purpose: learn before Phase 5 whether ESPN refuses data-center addresses. A pass from GitHub is a good sign for AWS, not proof. Phase 5 runs the same request from AWS first.

## 10. Docs

- `DESIGN.md`:
  - Decision 10 renames the interface `ScoreFeed` and replaces the provider test with "ESPN only for now; the cloud-IP check."
  - The "Security and correctness" list marks what Phase 2 closed, with what remains and which phase closes it.
  - The build order marks step 2 done.
- `CLAUDE.md` and `AGENTS.md`: "Open problems" and "Current state" updated, and the ESPN bullet matches decision 10.
- `README.md`: one paragraph on what Phase 2 changed, including the burst test.
- `docs/study/phase-2-quiz.md`: interview questions with model answers, per the Phase 1 format.

## Known gaps accepted in Phase 2

- An NBA basket can still notify a follower who enabled `SCORE_UPDATE`. Decision 15 ends that in Phase 3.
- A worker that dies mid-event leaves the entry pending, and a `fanout:` key expires after 60 seconds. Kafka replaces this in Phase 3.
- Every gateway still reads every notification (`notify:*`). Decision 13, Phase 3.
- Accounts are still lost on `make down`. Postgres, Phase 3.
- Login throttling uses the address Traefik reports. On AWS the trusted hop is set in Phase 5.
- `SUNIONSTORE` and `SINTERSTORE` run as single Redis commands, and Redis does nothing else while one runs. With friends-scale follower sets that is microseconds; with millions of followers it would pause Redis noticeably. Phase 4's load test measures it.
