# Phase 3.1: Kafka pipeline — design

Status: draft for review, 2026-10-06. Parent document: `DESIGN.md` (build order step 3; decisions 1–6, 12, 13). If this spec and `DESIGN.md` disagree, `DESIGN.md` wins and this spec gets fixed. Task 9 updates `DESIGN.md` where this spec refines it (decision 5's retrier, the heals, the per-game counter).

## Phase 3 is four sub-phases

Phase 3 as `DESIGN.md` writes it is four large changes. It is split so each lands, runs, and is reviewed on its own, with its own spec, plan, PR, and section of `docs/study/phase-3-quiz.md`. Numbered 3.1–3.4 so they do not clash with "3b" (the new UI).

| Sub-phase | What | Needs |
|---|---|---|
| **3.1 Kafka pipeline** (this spec) | Strimzi with one broker, script-then-produce with an outbox, Stage 1 and Stage 2 consumers, per-user dedup, gateway routing (decision 13) | — |
| 3.2 Postgres | Accounts, decision-15-shaped follows, notification history, `sub:*` rebuilt on startup, `/auth`, `/me`, `/follows`, `/notifications` | 3.1 |
| 3.3 Alerts, wave 1 | Detector framework and wave 1 detectors in Stage 1, most-specific-follow set math, combining, deterministic alert ids | 3.1, 3.2 |
| 3.4 Live API | `/games`, `/leagues`, the alert catalog, WebSocket `game_update` through a per-game channel | 3.2, 3.3 |

The API spec (`docs/api/`) is finalized before 3.1 and merged to main, so the UI has a stable target; each sub-phase implements its slice. The course UI (`index.html`) keeps working through 3.1 and is allowed to break at 3.2. No adapter endpoints are added for it.

## Goal

Replace the Redis Stream with Kafka, close the two crash holes the stream left open (a play accepted but never published; a worker that dies before acknowledging), deliver each notification exactly once in effect, and send each notification only to the gateway that holds the user's socket. Accounts stay in Redis and the three course alert types stay; those change in 3.2 and 3.3.

### Done when

1. `make test` passes, including every test in this spec.
2. `make cluster up smoke` passes on the laptop and in CI, with the smoke additions in section 6.
3. Killing a Stage 1 or Stage 2 pod while `make simulate` runs loses no notification and doubles none, checked by hand against history. (Phase 4's checker automates this.)
4. Task 9's docs are updated and `docs/study/phase-3-quiz.md` has a 3.1 section. The user is quizzed only on request.
5. The PR from `phase-3-1-kafka` is merged.

### Branch

`phase-3-1-kafka`, worktree `.worktrees/phase-3-1-kafka`. Main (with PR #4, issue #3's fixes, and PR #5, the API spec) is merged into it before any code is written. Merged, not rebased: the branch is already pushed, and a rebase would need a force-push.

---

## 1. From Event Processing into Kafka

### 1.1 The problem

Today the Lua script accepts a play and writes the new score, then Event Processing appends the play to the Redis Stream. A crash between the two loses the play: the poller has no memory and compares ESPN with the saved record, which already shows the new score, so it never sends the play again. `DESIGN.md` decision 5 says the produce is "retried," but nothing would retry it.

### 1.2 The outbox

In the same atomic script that accepts a play, the play is written to an outbox in Redis. Event Processing sends it to Kafka, and only when Kafka confirms the write is the outbox entry removed. A retry loop re-sends whatever is left. This is the transactional outbox pattern. "In the outbox" is decision 5's `claimed`; "removed" is its `produced`.

**One unsent play per game.** A game has at most one play in the outbox. While it is there, the script answers `pending` to any newer play for that game and changes nothing; the poller tries again on its next poll. A game's plays therefore reach Kafka in the order the script accepted them, which Stage 1's duplicate check depends on (section 2.2).

Without this rule a late re-send is lost: the script accepts seq 10 and Event Processing crashes before sending it; after the restart seq 11 is accepted and sent; the retry loop then sends 10, and Stage 1, having handled 11, drops 10 as old. The same rule fixes the Phase 2 leftover where two plays for one game accepted at once could be published out of order.

A per-game Redis lock was considered and rejected. A lock with a timeout can be held by a pod that stalls (a long garbage-collection pause) past the timeout while a second pod sends a newer play; the first pod then sends the older one after it. Enforcing the rule inside the script relies only on Redis running one script at a time, the same property that fixes the score race.

### 1.3 The per-game counter (`version`)

On every accepted play the script increments `version` in the game record. It changes in the same atomic step as the score.

- Stage 1 drops a play whose `version` is at or below the last one it handled for that game.
- After the script, every id is built from it: `<gameId>:v<version>`.
- The API's `GameSummary.version` is this counter.
- `seq` is only the script's "how far into the game" number. It can now go down (section 1.4), so nothing downstream may treat it as an order. The event id `<gameId>:<seq>` is kept only for the script's duplicate check, because the poller builds it before the script runs and a re-sent poll must carry the same id. A heal never produces a new event with an old id: an identical position is the same play.

### 1.4 The two heals

**Problem.** In the recording of 2026-10-02 to 10-05, 2 of 23 NHL games showed a clock ahead of reality (4:01 left in the 2nd, then 11:45; 0:00 mid-period). The script saved the bad snapshot, its `seq` became the bar, and every real snapshot for the next several minutes of game time was rejected as stale. A goal in that stretch would have sent no alert.

**Goal heal.** A play is also newer if it has the same phase, period, and MLB half as the saved state (the first four digits of `seq`) and a higher score total. On accept, `lastSeq` is set to this play's `seq`, which may lower it. An older snapshot never has more points, so a slower overlapping poller cannot use this path to move the score backwards. Cost: if a goal is taken back at the same moment a slower poller sends the pre-reversal snapshot, that snapshot is accepted and one false "scores!" alert goes out, corrected on the next poll. That needs two pollers at once (seconds, during a rolling update) and a reversal inside those seconds.

**No-score heal.** If plays keep arriving "older" with the same phase, period, half, home score, and away score as the saved state for 30 seconds, the script accepts the next one and resets `lastSeq` to it. The script records `staleSince` in the game record on the first such rejection; any accepted play clears it. The current time is an argument from Event Processing (an injectable clock), not Redis's clock, so tests can move time. Normal clock corrections (an official adding 1–15 seconds) heal on their own within a few polls, well under 30 seconds. An overlapping poller still running after 30 seconds can get a slightly older snapshot accepted; scores cannot change on this path, so no alert fires, and the next fresh poll moves the clock forward again.

**Only for game positions.** Both heals apply only when the play's `seq` and `lastSeq` are both game positions, below 10¹⁴. The simulator's `seq` (`runStartMillis × 1000 + n`, Phase 2 section 2.4) is above 10¹⁵ and orders runs, not positions in a game: its leading digits mean nothing as a period, and they stay the same for about three hours. Without this guard an older simulator run still going could have a higher-scoring play accepted as a "heal", breaking Phase 2's rule that the later run wins. For those values only `seq > lastSeq` counts.

**Not healed:** an early "final" from ESPN (never observed). Clock-based alerts that fire early during a glitch (close game late at 5:00) are 3.3's detector problem.

### 1.5 The script

`EventProcessingServiceImpl.PROCESS_EVENT_SCRIPT`, in this order, as one script:

1. **Duplicate.** `processed:<eventId>` exists → `duplicate`.
2. **Pending.** `outbox:<gameId>` exists → `pending`.
3. **Newer.** Accept if `seq > lastSeq`; or the goal heal holds; or the no-score heal's 30 seconds have passed. If the play matches the no-score condition and `staleSince` is unset, set it. Otherwise → `stale`.
4. **Accept.** `SET processed:<eventId> EX 3600`; `HINCRBY version`; write the full state and `lastSeq`; `HDEL staleSince`; set the record's TTL and `games:recent` as in Phase 2; `SET outbox:<gameId>` to the play as JSON, including `version`; `ZADD outbox:pending <now> <gameId>` (the index the retry loop reads). Return `accepted` with `version`.

A second script, **clear**, runs after Kafka confirms: delete `outbox:<gameId>` and remove it from `outbox:pending` only if the stored play's `version` equals the confirmed one.

`POST /events` answers as in Phase 2, plus `200 {"result":"pending"}`. The poller treats `pending` like `stale`: debug log, try again next poll.

### 1.6 Producing

- Topic `game-events`, key = game id, value = the play as JSON with `version`.
- `acks=all`, idempotent producer (Kafka's own retries cannot write a duplicate or reorder a key's messages), `linger.ms=0`.
- Sending is asynchronous. `POST /events` answers once the script decides; a slow or absent broker never holds the poller's request. `max.block.ms=2000`, so a down broker cannot block a request thread on metadata for the default 60 seconds.
- On confirmation, run **clear**. On failure, log and leave the play in the outbox.

**Every accepted play is produced, including `GAME_SYNC`.** Decision 8 says the live score can be rebuilt by replaying `game-events`, and 3.3's detectors need every state change (halftime arrives as `GAME_SYNC`). Stage 1 notifies nobody for `GAME_SYNC`.

### 1.7 The retry loop

Every second, each Event Processing pod reads games in `outbox:pending` whose entry is older than 5 seconds, produces each play, and runs **clear** on confirmation. Two pods can re-send the same play; Stage 1 sees the same `version` twice and drops the second. They cannot send one game's plays out of order, because the outbox never holds two plays for one game.

### 1.8 Failure behavior

**Event Processing crashes after accepting a play.** The play stays in the outbox; the game is `pending`. Within about 5 seconds another pod's retry loop (or the restarted pod's) sends it, and the game moves on.

**Kafka is down.** The first play after the outage begins is accepted: the score moves and the play sits in the outbox. After that the game is `pending`, its live score freezes, and the poller keeps retrying. Nothing accepted is lost. When Kafka returns, the retry loop sends the waiting play first, then the next poll is accepted and carries everything that happened during the outage as one change (three goals become one "scores!" with the current score).

**Redis restarts.** The outbox entry and the new score are written by one script, so the append-only file keeps both or loses both (Redis can lose its last second of writes). Both kept: the retry loop sends the play. Both lost: the poller sees ESPN ahead of the record and sends the play again. Redis back completely empty (its volume lost): every game is first-seen and recorded quietly with `GAME_SYNC`, so plays from that gap never alert. That is the limit Phase 2 accepted.

### 1.9 Tests (real Redis; Kafka through Testcontainers)

1. Goal heal: saved `seq` from a glitch (4:01 left), then a real snapshot at 11:45 with one more goal → accepted, `lastSeq` lowered to its `seq`.
2. The goal heal does not overreach: an older snapshot with the same score in the same period → `stale`.
3. No-score heal: same period and score keep arriving older; at 29 seconds `stale`, at 30 accepted and `lastSeq` reset.
4. Any accepted play clears `staleSince`.
5. `version` rises by one per accepted play, healed ones included, and never moves on `duplicate`, `stale`, or `pending`.
6. While `version` 10 is unsent, a newer play is `pending` and the saved score does not move.
7. Crash after accepting 10 (the producer is not called), then the retry loop runs and 11 is accepted: Kafka holds 10 before 11.
8. The retry loop sends a play left in the outbox and clears it.
9. Two retry loops at once: the play is on Kafka once or twice, never out of order.
10. **clear** with a stale `version` leaves a newer outbox entry alone.
11. Kafka outage: pause the Kafka container; a game goes `pending`; unpause; the outbox play arrives first, then the game's next play is accepted.
12. `GAME_SYNC` is produced to `game-events`.
13. The 50-thread burst test from Phase 2, with each thread retrying on `pending` the way the poller does: the final score is 50; `version` equals the number of `accepted` answers (stale plays are not counted); the accepted plays' `seq` values rise strictly in `version` order (no heal fires); the published plays are in `version` order.
14. The heals ignore simulator values: after an accepted play from a newer simulator run, a higher-scoring play from an older run is `stale`.

---

## 2. Stage 1 and Stage 2

### 2.1 Consumers

Both stages are Kafka consumer groups with the cooperative-sticky assignor (a rebalance pauses only the lanes that move), manual offset commits after a batch is fully handled, and `auto.offset.reset=earliest` (a new group reads what was sent before it started; Kafka's default, `latest`, would skip it).

**One at a time per lane.** Each worker handles a lane's messages in order, on one thread. Per-user order depends on it. Allowed: Stage 2 pipelining its Redis scripts (Redis runs them in the order sent), and Stage 1 sending a play's per-user messages without waiting for each confirmation (the producer keeps their order within a lane, and Stage 1 waits for all of them before the next play). Not allowed: a thread pool or any parallel work inside a lane.

### 2.2 Stage 1: who should hear about this play

Group `stage1`, topic `game-events`. For each play:

1. If `version` ≤ `s1:last:<gameId>`, skip it (a duplicate).
2. `GAME_SYNC`: record the version and move on.
3. Otherwise build the audience with Phase 2's set operations, write one message per user to `notifications` (key = user id), wait for Kafka to confirm all of them, then record the version.
4. Commit the offset after the batch.

**Recording the version is "set if higher"** (a small script). A worker that stalled and lost its lanes in a rebalance can wake up and finish an older play after the new owner finished newer ones; it must not lower `s1:last`.

| Crash point | Replacement worker | Result |
|---|---|---|
| Before Kafka confirms the messages | Re-reads the play and sends again | Some users' messages twice; Stage 2's dedup absorbs them |
| After recording the version, before the commit | Re-reads the play, finds the version recorded | Skips it; the messages were already confirmed |

The message id is `<gameId>:v<version>`. A message carries the user id, event type, text, both teams and scores, status, league, and `detectedAt` (when the poller saw the play). Stage 1's producer may linger a few milliseconds (`DESIGN.md`: Stage 1 can batch).

### 2.3 Stage 2: deliver exactly once in effect

Group `stage2`, topic `notifications`.

**Problem.** Kafka delivers at least once. Marking a message done before delivering loses it if the worker crashes in between; delivering before marking duplicates it.

**Fix.** One Lua script per message does everything in Redis at once:

1. `SET notif:<messageId>:<userId> NX EX 172800`; if it already exists, stop.
2. Add the notification to the user's history list (`notifications:<userId>`, as today).
3. Remove expired entries from `conn:<userId>` (section 3) and `PUBLISH gw:<gatewayId>` for each one left, with the user id in the message.

Redis runs the script as one step, so marked, in history, and sent happen together or not at all. A retry finds the key and does nothing. That is the exactly-once the Phase 4 checker tests: Kafka is at least once, and the script makes it exactly once in effect.

**Limits.** Redis pub/sub to the gateway is fire-and-forget: if the gateway dies in that instant the live push is lost and history has it (`DESIGN.md` accepts this). In 3.2 history moves to Postgres, which cannot be inside a Redis script: the 3.2 spec makes the history insert idempotent (insert unless that notification already exists), runs it first, then runs this script for the rest.

**Order per user.** A user's messages share a lane, one worker owns the lane, and the worker handles it in order (2.1).

### 2.4 Re-reads are safe

With `earliest`, a group whose committed offsets are lost (renamed, or idle past Kafka's 7-day offset retention) re-reads the whole topic. That is safe only if the dedup records outlive the data:

| Record | Expires after | Guards | Topic retention |
|---|---|---|---|
| `s1:last:<gameId>` | 8 days | `game-events` | 7 days |
| `notif:<messageId>:<userId>` | 2 days | `notifications` | 1 day |

### 2.5 Tests (real Kafka and Redis)

1. Stage 1 dedup: the same play twice → one message per user.
2. Stage 1 crash after sending, before the commit: each user gets the notification once in the end.
3. `s1:last` is set-if-higher: set 7, then 5 → stays 7.
4. `GAME_SYNC` produces no messages.
5. Stage 2 dedup: the same message twice → one history entry, one publish.
6. Rebalance: two Stage 2 workers, one killed mid-batch; every user gets each notification exactly once, in order.
7. `earliest`: a play sent before a never-used group starts is read by it.
8. Re-read: reset Stage 1's offsets to the start of the topic; no user gets a second notification.

---

## 3. Gateway routing

**Problem.** Every gateway subscribes to `notify:*` (`NotificationFanOut.java`), reads every notification, and drops those for users it does not hold. Adding a gateway removes no work from the others.

**Fix (decision 13).**

- A gateway's id is its pod name (`HOSTNAME`). A restarted pod gets a new one.
- `conn:<userId>` is a sorted set: member = gateway id, score = when the entry expires. A set, because a user can be on two gateways at once (a laptop and a phone).
- When a socket opens, the gateway adds itself with an expiry 90 seconds out and sets the key's own expiry to 90 seconds.
- Every 30 seconds the gateway renews every connected user's entry and the key's expiry, in one pipelined batch.
- When a user's last socket on this gateway closes, the gateway removes itself.
- Expiry times use Redis's clock (`TIME` inside a small script), so pods whose clocks drift cannot expire entries early.
- Stage 2's script removes expired entries and publishes on `gw:<gatewayId>` for each one left. Each gateway subscribes only to its own channel and forwards a message to that user's sockets on this gateway.
- The key's own expiry means a user who never returns leaves nothing behind.

**Graceful shutdown.** On the stop signal the gateway removes its id from every connected user's `conn:` set in one pipelined batch, then closes every socket with code 4000 ("reconnect now"), then exits. A notification sent between the removal and the browser's reconnect reaches nobody live and is in history: the same gap `DESIGN.md` accepts for a gateway restart.

**A gateway that dies without shutting down.** Its entries stop being renewed and expire within 90 seconds. Publishes to its channel in that window reach nobody; history has them. A reconnect to another gateway adds that gateway at once. 30 and 90 seconds let a gateway miss two renewals and keep its entries, and forget a dead gateway within a minute and a half, for one Redis write per connected user every 30 seconds.

**Removed:** `PSUBSCRIBE notify:*` and the `notify:<userId>` channels.

### 3.1 Tests

1. A user on gateway A gets the notification; gateway B's channel receives nothing.
2. A user with tabs on A and B gets it on both.
3. A stops renewing (short expiry in the test): its entry is gone, the publish reaches nobody, history has the notification.
4. Closing the last tab removes the entry; closing one of two keeps it.
5. The key expires after the last renewal.
6. Graceful shutdown of A: a user on A and B keeps B's entry; a user only on A has no entry; the client got close code 4000.

---

## 4. Infrastructure

**Strimzi** is a Kubernetes operator: a program in the cluster that reads resources such as "a Kafka with one broker" or "a topic with 12 partitions" and creates and maintains the pods, volumes, and settings to match.

| Piece | Where | Detail |
|---|---|---|
| Strimzi operator | Installed by `make cluster` from Strimzi's Helm chart, version pinned | It adds resource types to Kubernetes, which must exist before our chart uses them. Phase 5 installs it the same way on EKS. |
| Broker | Our chart: a Strimzi `Kafka` resource and node pool, KRaft mode (no ZooKeeper) | One broker locally, three in the cloud (values file). Persistent storage on k3d too (2 GB through k3d's local-path provisioner), so a broker restart keeps confirmed plays, as `DESIGN.md`'s failure table promises. 512 MB heap locally. |
| Topics | Our chart: `KafkaTopic` resources | `game-events` 12 partitions, retention 7 days. `notifications` 48 partitions, retention 1 day. Replication 1 locally, 3 in the cloud; `min.insync.replicas` 1 locally, 2 in the cloud. |
| Broker settings | `Kafka` resource | `auto.create.topics.enable=false`, so a misspelled topic fails instead of being created. |
| Network policy | The listener's `networkPolicyPeers` (Strimzi writes the policy) | Only `event-processing`, `stage1-worker`, `stage2-worker`, and Strimzi's own pods reach the Kafka port. |

**Services.**

| Change | Detail |
|---|---|
| `pubsub-worker` → `stage1-worker` | One copy locally (`DESIGN.md`). |
| `stage2-worker` | New. One copy locally. |
| `notification` | Keeps only its history-read endpoint until 3.2 moves history to Postgres. |
| Startup | Services that use Kafka wait for it the way they wait for Redis (Phase 2 section 7). |

The Java client is Apache Kafka's `kafka-clients`. Kafka tests start a broker with Testcontainers (`apache/kafka-native`, about a second to start), which also lets the outage test pause it. `make test` keeps its Redis container.

**Not in 3.1:** TLS and authentication on Kafka. Inside the cluster the network policy is the protection; Phase 5 adds TLS when traffic crosses machines.

---

## 5. Game ids

API spec item 2: one game id everywhere, `<league>-<espnId>` (`nfl-401872948`), with no provider name in the API. Two places build it today and both change in task 1: `EspnScoreFeed` (the poller) and `EspnProxyHandler` (the course UI's upcoming list, which game follows are made from). Records under `espn-…` expire (a day after a final, 8 idle days); the first poll after the switch records each game under its new id quietly with `GAME_SYNC`. A game followed in the course UI under an `espn-…` id stops matching; game follows are per-game and short-lived, and accounts are lost on `make down` until 3.2.

---

## 6. Smoke test additions

- Both topics exist with 12 and 48 partitions.
- A stranger pod cannot connect to the Kafka port.
- A simulated play reaches a browser socket through Kafka (the existing end-to-end check, now on the new path).
- Stop both stage workers (scale to 0), simulate a play, start them: the play arrives. On CI's fresh cluster this tests `earliest`; on a reused laptop cluster it tests that a play waits in Kafka while workers are down.

---

## 7. Order of work

One branch, one PR, one commit or more per task. CI is green after every task, so the old path keeps working until its replacement lands.

| # | Task | Keeps CI green by | Built |
|---|---|---|---|
| 1 | Game id switch (`EspnScoreFeed`, `EspnProxyHandler`, tests) | — | inline, test-first |
| 2 | Script: pending, both heals, `version`, outbox, **clear** | The existing Redis Stream publish runs **clear**, so games never stay `pending`. This lasts only until task 4 | inline, test-first, small steps |
| 3 | Infrastructure: Strimzi in `make cluster`, broker and topics in the chart, network policy, Testcontainers, `kafka-clients`, smoke checks for topics and the stranger pod | Kafka is deployed but unused | subagent, walked through |
| 4 | Event Processing: producer, clear on confirmation, retry loop, outage test | Publishes to both the Redis Stream and Kafka; the course workers still read the stream. From this task on only Kafka's confirmation runs **clear**: the stream publish stops clearing, or it could clear a play Kafka never got | inline |
| 5 | Stage 1 consumer | Built and tested, deployed switched off (otherwise its messages pile up and Stage 2 would deliver them again) | inline |
| 6 | Stage 2 consumer and delivery script; ends with the switch-over commit: both stages on, Redis Stream publish off, course workers off | Stage 2 publishes on both `notify:<userId>` and `gw:` until task 7. At the switch-over the laptop cluster is rebuilt (`make down`, `make cluster up`): Stage 1's first start reads from the beginning of `game-events`, and on a reused cluster it would re-alert every play produced since task 4. CI's cluster is always fresh | inline |
| 7 | Gateway routing and graceful shutdown | Removes the `notify:` publish in the same commit that moves the gateway to its own channel | inline |
| 8 | Delete the Redis Stream, the course workers, `NotificationFanOut`'s broadcast, old `RedisKeys` names; smoke check for plays sent before workers start | Deletes only code already switched off | inline |
| 9 | Docs and the quiz section | — | inline |

"Inline" follows `CLAUDE.md`: core distributed code is built in small steps the user reads.

Task 9: `DESIGN.md` (build order step 3 split into 3.1–3.4; decision 5 names the outbox and the one-unsent-play rule; the heals and `version`; the fix list), `CLAUDE.md` and `AGENTS.md`, one `README.md` paragraph, and the 3.1 section of `docs/study/phase-3-quiz.md`.

## Known gaps accepted in 3.1

| Gap | Closed by |
|---|---|
| The three course alert types; NBA still alerts on every basket | 3.3 |
| Accounts lost on `make down` | 3.2 |
| An early "final" from ESPN is not healed | Not observed; left as is |
| Clock-based alerts can fire early during a clock glitch | 3.3's detectors |
| Kafka traffic in the cluster is unencrypted | Phase 5 |
| Redis pub/sub to the gateway is fire-and-forget | Accepted; history has every notification |
| A goal reversal racing an overlapping poller sends one false score alert | Accepted (section 1.4) |
