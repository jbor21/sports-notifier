# Phase 6.1: Chaos on EKS — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix #47, make sockets notice a silent dead line, and run the chaos suite (nine scenarios, including a machine dying) on EKS.

**Architecture:** #47 adds one Redis set (`synthetic:users`) that Stage 1 intersects with a replayed game's audience. The checker and the UI each get a "ping unanswered by the next ping means dead" rule; the checker pings every 5 s and marks such gaps silent. `chaos-lib.sh` gets EKS versions of its faults (a privileged node debug pod instead of `docker exec`), multi-broker admin and proofs, and a `node-loss` scenario that terminates an EC2 instance.

**Tech Stack:** Java 17, Jedis, JUnit 5, Javalin (test servers); TypeScript + Vitest (`web/`); bash, kubectl, AWS CLI v2.

**Spec:** `docs/superpowers/specs/2026-10-26-phase-6-1-chaos-eks-design.md` (read it with this plan; section numbers below are the spec's).

## Global Constraints

- Redis key names come from `util/RedisKeys.java`; the new one is `SYNTHETIC_USERS = "synthetic:users"`.
- Replayed game ids are `<league>-rp<runId>-<espnId>`; simulated ids `<league>-sim<start>-<slot>` stay visible and reach real users.
- `FollowSets.SHAPE_VERSION` goes from 2 to 3.
- Checker socket ping: every 5 s. UI ping: stays 25 s.
- Debug pod image: `busybox:1.37.0`. AWS profile `sports` (env `AWS_PROFILE`, default `sports`), region `us-east-2`.
- `node-loss` limits: instance terminating within 60 s, Node NotReady or gone within 120 s, replacement Ready and broker back with no under-replicated partitions within 15 min of the termination.
- `broker-crash` on more than one broker: every partition it led has another leader within 30 s, none leaderless.
- Never run `make aws-up`, `make aws-down`, `make chaos` on EKS, or any `terraform` command: the user runs them (memory: aws-hands-on-rules). Remind them of `make aws-down` whenever a cluster is up.
- Every Jedis handle in try-with-resources; config via env vars with defaults; comment density like the surrounding code.

## Review Focus

1. **A synthetic user created while Redis is down** never joins `synthetic:users` until a rebuild. The expected behavior is that the signup still succeeds and a rebuild is asked for (Task 2 tests it).
2. **An aborted Java WebSocket also fires `onError`/`onClose`.** That must not record a second gap or schedule a second reconnect (Task 4 tests exactly one reconnect).
3. **A tab that returns to visible** sends an extra ping. The silence check must use only ticker pings, or the next tick closes a healthy socket (Task 6 tests it).
4. **The broker at fault is `dual-role-0`.** Every admin command must still find a Ready broker (Task 8 tests it with a fake kubectl).
5. **Leftover debug pods after Ctrl-C** keep running privileged on a node. The cleanup trap deletes every `node-debugger-*` pod (Task 7 tests that cleanup calls it).

---

### Task 1: Replay ids in one place, hidden from `/games`

**Files:**
- Modify: `src/main/java/feed/GameIds.java`, `src/main/java/feed/RecordedScoreFeed.java:67-70`, `src/main/java/service/impl/GameStateServiceImpl.java:29-50`
- Test: `src/test/java/feed/GameIdsTest.java` (create if missing), `src/test/java/service/impl/GameStateServiceImplTest.java`

**Interfaces:**
- Produces: `GameIds.replay(String league, String runId, String espnId) -> String` (`nfl-rpabc-401`), `GameIds.isReplay(String gameId) -> boolean` (true iff the part after the first `-` starts with `rp`; false for null).

- [ ] **Step 1: Write the failing tests.** `GameIdsTest.replayIdsAreRecognized`: `isReplay("nfl-rpabc123-401872971")` true; `isReplay("nfl-401872971")`, `isReplay("nhl-sim1700000000-3")`, `isReplay(null)` false; `replay("NFL","abc","401")` equals `"nfl-rpabc-401"`. `GameStateServiceImplTest.listLeavesOutReplayedGames`: with the file's `game(...)` helper, put `nfl-1`, `nfl-rpx-2`, `nfl-sim9-3` on the same day; `listDay` returns ids `[nfl-1, nfl-sim9-3]`.
- [ ] **Step 2: Run:** `mvn -q test -Dtest='GameIdsTest,GameStateServiceImplTest'`. Expected: compile failure / FAIL.
- [ ] **Step 3: Implement** both helpers. `RecordedScoreFeed` calls `GameIds.replay`. `listDay` skips `isReplay` ids before the pipeline reads. `detail` is unchanged.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "#47: GameIds.isReplay; /games leaves out replayed games"`

### Task 2: `synthetic:users`: add on signup, refill on rebuild, remove on cleanup

**Files:**
- Modify: `src/main/java/util/RedisKeys.java`, `src/main/java/service/impl/FollowSets.java:24`, `src/main/java/service/impl/SubRebuilder.java` (`rebuildNow`, new `buildSynthetic`), `src/main/java/service/impl/FollowService.java:224` (expose the rebuild request), `src/main/java/service/impl/UserPreferenceServiceMain.java:60-64`, `src/main/java/checker/Cleanup.java` (`delete`)
- Create: `src/main/java/service/impl/SyntheticUsers.java`
- Test: `src/test/java/service/impl/SyntheticUsersTest.java`, `src/test/java/service/impl/SubRebuilderTest.java`, `src/test/java/checker/CleanupTest.java`

**Interfaces:**
- Produces: `RedisKeys.SYNTHETIC_USERS`. `SyntheticUsers(JedisPool pool, Runnable requestRebuild)`; `static boolean isSynthetic(String username)` (`ck_` prefix, after the same trim/lowercase as signup); `void created(String userId, String username)`, which SADDs when synthetic, and on a Redis failure logs and calls `requestRebuild` instead of throwing. `FollowService.requestRebuild()` is public (today's private `askForRebuild`).

- [ ] **Step 1: Write the failing tests.**
  - `SyntheticUsersTest.addsOnlyCheckerNames`: `created("u1","ck_abc_1")` puts `u1` in the set; `created("u2","alice")` does not.
  - `SyntheticUsersTest.redisDownAsksForRebuild`: a pool on a closed port; `created` does not throw and the `Runnable` ran once.
  - `SubRebuilderTest.rebuildRefillsSyntheticUsers`: Postgres users `ck_r_1` and `bob`; Redis set holds a stale `gone`. After `rebuildNow(true)` the set is exactly `{id of ck_r_1}`.
  - `SubRebuilderTest.rebuildWithNoSyntheticUsersDeletesTheSet`: no `ck_` users, so the key doesn't exist after the rebuild.
  - `SubRebuilderTest`: a marker with version `2` is not vouched (the existing shape test, with the new number).
  - `CleanupTest.removesDeletedUsersFromSyntheticSet`: after `deleteRun`, the run's ids are gone from the set and another run's id stays.
- [ ] **Step 2: Run:** `mvn -q test -Dtest='SyntheticUsersTest,SubRebuilderTest,CleanupTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - `SHAPE_VERSION = 3`.
  - `buildSynthetic`, called inside `rebuildNow` before `markBuilt`: `SELECT id FROM users WHERE username LIKE 'ck\_%'`. SADD to `RedisKeys.SUB_TMP_PREFIX + buildId + ":" + SYNTHETIC_USERS` in batches of `BATCH`, then RENAME over the live key; with no rows, DEL the live key. The temp name is under `sub:tmp:`, so `cleanup` already removes a crashed build's leftover.
  - The `/users` route calls `synthetic.created(r.body().id(), r.body().username())` when `r.isOk()`.
  - `Cleanup.delete`: SREM the ids in the same Jedis block as the `unread:` DEL.
- [ ] **Step 4: Run** the same command, then `make test`. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "#47: synthetic:users set, kept by signup, rebuild, and cleanup"`

### Task 3: Replayed games reach only synthetic users

**Files:**
- Modify: `src/main/java/service/impl/AlertAudience.java:58-75`
- Test: `src/test/java/service/impl/AlertAudienceTest.java`

**Interfaces:**
- Consumes: `GameIds.isReplay` (Task 1), `RedisKeys.SYNTHETIC_USERS` (Task 2).

- [ ] **Step 1: Write the failing tests.** Use the file's `follow(...)` and `audienceOf(...)` helpers, with a replay play `PlayBuilder.nfl("nfl-rpx-1")` (home/away as in `NFL`).
  - `replayReachesOnlySyntheticFollowers`: `ck` and `real` both follow the home team with the alert's switch; `ck` is in `synthetic:users`. Audience = `{ck}`.
  - `replayWithoutSyntheticSetReachesNobody`: the same follows, no set. Audience empty.
  - `liveAndSimulatedGamesIgnoreTheSet`: for `nfl-1` and `nfl-sim9-1`, audience = `{ck, real}`.
- [ ] **Step 2: Run:** `mvn -q test -Dtest=AlertAudienceTest`. Expected: the replay tests FAIL.
- [ ] **Step 3: Implement.** After `p.sunionstore(key, g, t, l)`, when `GameIds.isReplay(play.gameId())`, add `p.sinterstore(key, key, RedisKeys.SYNTHETIC_USERS)`. Add one line to the class Javadoc saying why (#47).
- [ ] **Step 4: Run** `mvn -q test -Dtest='AlertAudienceTest,MomentFanout*,Stage1*'`. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "#47: a replayed game's audience is intersected with synthetic:users"`

### Task 4: Checker sockets notice silence (5 s pings, silent gaps)

**Files:**
- Modify: `src/main/java/checker/SocketLog.java`, `src/main/java/checker/SocketClient.java`, `src/main/java/tools/Checker.java:281`
- Test: `src/test/java/checker/SocketClientTest.java`

**Interfaces:**
- Produces: `SocketLog.Gap(long fromMillis, long toMillis, boolean silent)`; `SocketLog.disconnected(long atMillis, boolean silent)` (the one-argument form stays, as `silent = false`); `Checker.SOCKET_PING_EVERY = Duration.ofSeconds(5)`.

- [ ] **Step 1: Write the failing tests** (the file's `serve(...)` helper; client `pingEvery` = 200 ms).
  - `silentServerIsAbortedAndGapStartsAtLastFrame`: the server sends `hello` on the first connect, then never answers. Await `connects == 2`. The log's first gap is `silent`, its `fromMillis` is ≤ the hello's arrival time + 50 ms, and its `toMillis` is the second hello.
  - `abortedSocketReconnectsOnce`: the same server. 1 s after `connects == 2` it is still 2 (no second reconnect from a late `onError`), and there is exactly one closed gap.
  - `answeringServerIsNeverAborted`: the server answers each ping with `{"type":"pong"}`. Over 1.5 s, `connects == 1` and there are no gaps.
  - `closedSocketGapIsNotSilent`: the server closes with 1000 after hello. The gap is not silent and starts at the close (≥ the hello time).
- [ ] **Step 2: Run:** `mvn -q test -Dtest=SocketClientTest`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - `SocketClient` keeps `lastReceivedAt` (set on every `onText`) and `lastPingAt` (set when a ping is sent; reset to 0 in `onOpen`).
  - In `ping()`: if `lastPingAt != 0 && lastReceivedAt < lastPingAt`, abort the socket and record `down(lastReceivedAt, true)`; otherwise send and set `lastPingAt`.
  - `down` acts only for the socket that is current (pass the `WebSocket` and compare with `ws`), so an aborted socket's later callbacks are ignored.
  - `Checker` passes `SOCKET_PING_EVERY`.
- [ ] **Step 4: Run** `mvn -q test -Dtest='SocketClientTest,SocketLog*,Verifier*'`. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "Checker sockets: 5 s pings; an unanswered ping is a silent gap from the last frame"`

### Task 5: Count pushes excused by silent gaps (report and chaos summary)

**Files:**
- Modify: `src/main/java/checker/Verifier.java:24,73-113,142-147`, `src/main/java/checker/Report.java:31-33`, `scripts/chaos.sh` (summary line)
- Test: `src/test/java/checker/VerifierTest.java`, `src/test/java/checker/ReportTest.java`, `scripts/test-chaos-scripts.sh`

**Interfaces:**
- Consumes: `SocketLog.Gap.silent` (Task 4).
- Produces: `Verifier.Result(List<Finding> findings, int outsideRun, int allowedInGaps, int allowedInSilentGaps, List<Long> latenciesMillis)`. The report's sockets line reads `sockets   missing %d (allowed in disconnects: %d, silent: %d)   duplicate …`.

- [ ] **Step 1: Write the failing tests.**
  - `VerifierTest.silentGapCountsTwice`: a push missing inside a silent gap (in history), so `allowedInGaps == 1`, `allowedInSilentGaps == 1`.
  - `VerifierTest.ordinaryGapCountsOnce`: the same with a non-silent gap: 1 and 0.
  - `VerifierTest.overlappingOrdinaryGapIsNotSilent`: both kinds overlap the push: 1 and 0.
  - `ReportTest`: the line contains `(allowed in disconnects: 3, silent: 2)`.
  - `test-chaos-scripts.sh` case `summary_shows_silent`: put `sockets   missing 0 (allowed in disconnects: 3, silent: 2)` in a log, run the summary's parsing (extract it into a function `chaos_counts <log>` in `chaos-lib.sh`), and expect `excused 3  silent 2`.
- [ ] **Step 2: Run:** `mvn -q test -Dtest='VerifierTest,ReportTest' && bash scripts/test-chaos-scripts.sh`. Expected: FAIL.
- [ ] **Step 3: Implement.** A push counts as silent only when every gap that excuses it is silent. In `chaos.sh`, the summary line gets `silent S` after `socket misses excused N`, through `chaos_counts`.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "Checker report and chaos summary count pushes excused by silent gaps"`

### Task 6: The UI closes a silent socket

**Files:**
- Modify: `web/src/live/connection.ts`, `docs/api/websocket.md` (after the `ping` section)
- Test: `web/src/live/connection.test.ts`

- [ ] **Step 1: Write the failing tests** (the file's `fakeTicker()`).
  - `closes and reconnects when a ping goes unanswered by the next tick`: open, tick (ping), no message, tick. The socket was closed, state is `waiting`, and after the backoff a new socket is created; `onReconnected` fires once it opens.
  - `keeps a socket that answers`: open, tick, deliver `{"type":"pong"}`, tick. Not closed, and a second ping was sent.
  - `a ping on return to visible does not count as the tick's ping`: open, tick, pong, become visible (extra ping), tick. Not closed.
- [ ] **Step 2: Run:** `cd web && pnpm test -- connection`. Expected: FAIL.
- [ ] **Step 3: Implement.** Keep `lastReceivedAt` (every `onmessage`) and `lastTickPingAt` (only the ticker's ping; cleared in `opened`). On a tick, if `lastTickPingAt` is set and nothing arrived since, detach the handlers, `close()`, and `this.closed(1006)`, as the connect timeout does. websocket.md gets one sentence: a client treats a `ping` with no message back by its next ping as a dead connection, and reconnects with backoff.
- [ ] **Step 4: Run:** `cd web && pnpm test && pnpm typecheck && pnpm format:check`. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "UI: an unanswered ping closes the socket and reconnects"`

### Task 7: EKS faults: node debug pod, crash, cut

**Files:**
- Modify: `scripts/chaos-lib.sh` (header, `chaos_crash`, `chaos_cut`, new functions), `scripts/chaos.sh` (`cleanup`)
- Test: `scripts/test-chaos-scripts.sh`

**Interfaces:**
- Produces:
  - `CHAOS_PLATFORM`: `k3d` when `KUBE_CONTEXT` starts with `k3d-`, else `eks`.
  - `chaos_node_of <pod>`: the node's name.
  - `chaos_node_run <node> <shell command>`: runs the command in `chroot /host` through `kubectl debug node/<node> --profile=sysadmin --image=busybox:1.37.0`. It parses the pod name (`node-debugger-…`) from kubectl's output, waits up to 60 s for the pod to finish, prints its log, deletes it, and returns the container's exit code. A pod that never finishes returns 1 with `chaos_node_run: debug pod … did not finish`.
  - `chaos_debug_cleanup`: deletes every `node-debugger-*` pod.
  - `CHAOS_CUT_KILL`: default `1` on EKS, `0` ignored on k3d.

- [ ] **Step 1: Write the failing tests** (fake kubectl).
  - `platform_from_context`: `KUBE_CONTEXT=k3d-x` gives `k3d`, `sports-notifier-aws` gives `eks`.
  - `node_run_debug_pod_never_finishes`: the fake prints `Creating debugging pod node-debugger-n1-abcde …` and fails every `wait`. The function fails, its message names the pod, and a `delete pod node-debugger-n1-abcde` call was made.
  - `eks_crash_uses_crictl_in_chroot`: on EKS, `chaos_crash p` issues a `debug node/<node of p>` call whose command contains `chroot /host` and `crictl stop --timeout 0 <id>`, and no `docker` call.
  - `cleanup_deletes_debug_pods`: `chaos.sh`'s cleanup on EKS calls `chaos_debug_cleanup` (the delete is in the recorded calls).
- [ ] **Step 2: Run:** `bash scripts/test-chaos-scripts.sh`. Expected: the new cases FAIL.
- [ ] **Step 3: Implement.**
  - `chaos_crash` on EKS: `chaos_node_run "$(chaos_node_of "$1")" "crictl stop --timeout 0 $id"`.
  - `chaos_cut` on EKS: the same policy, then, when `CHAOS_CUT_KILL=1`, `chaos_node_run <node> 'pid=$(crictl inspect -o go-template --template "{{.info.pid}}" <id>); nsenter -t $pid -n ss -K "( dport = :9092 )"'` instead of `docker exec … conntrack`.
  - The k3d paths are unchanged. Update the file header: EKS replaces the two node functions.
- [ ] **Step 4: Run:** `bash scripts/test-chaos-scripts.sh`. Expected: all `ok`.
- [ ] **Step 5: Commit** `git commit -m "Chaos on EKS: crash and cut through a node debug pod"`

### Task 8: Three brokers: admin on a healthy broker, steady state, leadership proof

**Files:**
- Modify: `scripts/chaos-lib.sh` (`chaos_first_pod broker`, `chaos_members`, `chaos_steady`), `scripts/chaos.sh` (`crash` for `broker-crash`)
- Test: `scripts/test-chaos-scripts.sh`

**Interfaces:**
- Produces:
  - `chaos_brokers`: pod names of the `strimzi.io/pool-name=dual-role` pods.
  - `CHAOS_BROKER_AT_FAULT`: set by the broker crash, empty otherwise.
  - `chaos_admin_broker`: the first Ready broker that is not `CHAOS_BROKER_AT_FAULT`; fails if none. All `kafka-*.sh` calls go through it.
  - `chaos_leaders`: lines `<topic> <partition> <leader id>` for `game-events` and `notifications`, from `kafka-topics.sh --describe` (`none` as `-1`).
  - `chaos_under_replicated`: the count from `--under-replicated-partitions`.
  - A broker pod `<release>-dual-role-<n>` has node id `<n>`.

- [ ] **Step 1: Write the failing tests** (fake kubectl).
  - `admin_skips_broker_at_fault`: brokers 0, 1, 2 Ready, `CHAOS_BROKER_AT_FAULT=…-0`. The group read execs into `…-1`.
  - `admin_fails_with_no_ready_broker`: the function fails, and `chaos_members` fails with it.
  - `steady_waits_for_all_brokers_and_isr`: two of three brokers Ready, so "not steady" names the broker; all Ready with `chaos_under_replicated` = 4, so "not steady" names `4 under-replicated`.
  - `broker_crash_picks_most_leaders`: given a `chaos_leaders` output, the crash target is the id with the most rows.
- [ ] **Step 2: Run:** `bash scripts/test-chaos-scripts.sh`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - With more than one broker, `broker-crash` picks the broker leading the most partitions and sets `CHAOS_BROKER_AT_FAULT`. After the restart is proven (exit 137), it polls `chaos_leaders` every 2 s for up to 30 s, until no row has that id or `-1`; otherwise `fault_ok=0` with `fault did not land: partitions still led by <id> after 30 s`. The proof line adds `leaders moved in N s`.
  - `chaos_steady` with more than one broker also needs every broker Ready and `chaos_under_replicated` = 0.
  - With one broker (k3d), behavior is unchanged.
- [ ] **Step 4: Run:** `bash scripts/test-chaos-scripts.sh`. Expected: all `ok`.
- [ ] **Step 5: Commit** `git commit -m "Chaos on three brokers: healthy admin broker, ISR in steady, leadership proof"`

### Task 9: `node-loss`

**Files:**
- Modify: `scripts/chaos.sh` (`SCENARIOS`, `deployment_of`, a `node_loss` fault, the checker start), `scripts/chaos-lib.sh` (new functions), `scripts/run-job.sh` (`--near`), `.github/workflows/chaos.yml` (unchanged list: no `node-loss` option)
- Test: `scripts/test-chaos-scripts.sh`

**Interfaces:**
- Produces:
  - `run-job.sh --near <pod>`: the Job's pod gets `nodeSelector: {kubernetes.io/hostname: <node of pod>}`, added with `kubectl patch --local` in the existing create pipeline.
  - `chaos_pick_victim <checker pod>`: prints the node to kill. It excludes the nodes of `redis-0`, `postgres-0`, and the checker pod, and prefers one without the `lgtm` pod. If no node is left, it fails with `no machine free of redis, postgres and the checker`. When the pick holds `lgtm`, it prints a second line, `note: the dashboard data will be lost`.
  - `chaos_instance_of <node>`: the instance id from `spec.providerID` (`aws:///us-east-2a/i-0abc` gives `i-0abc`).
  - `chaos_await_replacement <zone> <nodes before…>`: waits up to the remaining part of 15 min for a Ready node not in the list. It prints `<name> <zone>`; it fails with `fault did not land: replacement in <zone'>, not <zone>` or `no replacement within 15 min`.
- Scenario order: `node-loss` is last in `all` on EKS. `all` on k3d leaves it out. `chaos.sh node-loss` on k3d exits 2 with `node-loss needs EKS`.

- [ ] **Step 1: Write the failing tests** (fake kubectl and a fake `aws`).
  - `victim_excludes_redis_postgres_checker`: nodes a, b, c; redis on a, postgres on b, checker on a. Pick is c.
  - `victim_prefers_no_lgtm`: redis and postgres on a, checker on a, lgtm on b. Pick is c.
  - `victim_none_left`: redis on a, postgres on b, checker on c. Fails with the message.
  - `replacement_other_zone_fails`: before a, b, c; a new node d in `us-east-2b` when the zone was `us-east-2a`. Fails with `fault did not land: replacement in us-east-2b, not us-east-2a`.
  - `instance_from_provider_id`: `aws:///us-east-2a/i-0abc` gives `i-0abc`.
  - `run_job_near_sets_node_selector`: the YAML passed to `create` contains `kubernetes.io/hostname: <node of the pod>`.
  - `node_loss_refused_on_k3d`: exit 2 and the message.
- [ ] **Step 2: Run:** `bash scripts/test-chaos-scripts.sh`. Expected: FAIL.
- [ ] **Step 3: Implement the `node_loss` fault** in `chaos.sh`.
  1. For this scenario, the checker starts with `--near redis-0`.
  2. At 40%, record the nodes, pick the victim and its zone, and `aws --profile "${AWS_PROFILE:-sports}" --region us-east-2 ec2 terminate-instances`.
  3. Prove the instance is `shutting-down`/`terminated` within 60 s, and the Node NotReady or gone within 120 s.
  4. `chaos_await_replacement`, then wait for the broker on it to be Ready and `chaos_under_replicated` = 0, inside the same 15 min.
  5. Proof line: `i-… in <zone>: NotReady +A s, replacement Ready +B s, broker back +C s, fully replicated +D s`.
  - A missing `aws` CLI or a failed terminate fails the scenario (the fault step could not run), never the suite.
- [ ] **Step 4: Run:** `bash scripts/test-chaos-scripts.sh && make -n chaos SCENARIO=node-loss`. Expected: all `ok`; the dry run shows the scenario.
- [ ] **Step 5: Commit** `git commit -m "Chaos: node-loss terminates one EC2 machine and proves a same-zone recovery"`

### Task 10: Docs and quiz

**Files:**
- Modify: `DESIGN.md` (build order step 6 split into 6.1–6.3; #47 resolved; decision 14's checker: the silent-gap rule and 5 s pings), `CLAUDE.md` (Phase 6.1 line, `synthetic:users` in the Redis list, `make chaos` on EKS, the `make replay` consequence), `README.md` if it lists the scenarios
- Create: `docs/study/phase-6-1-quiz.md` (about 10 questions, Review items first, like the earlier quizzes)

- [ ] **Step 1:** Write the edits; each changed claim must match the code from Tasks 1–9 (names, numbers).
- [ ] **Step 2: Run:** `grep -n "eight scenarios\|#47" DESIGN.md CLAUDE.md README.md`. Expected: every hit reads correctly after the change.
- [ ] **Step 3: Commit** `git commit -m "Phase 6.1 docs and quiz"`

### Task 11: PR, CI, and the k3d Chaos run

- [ ] **Step 1:** `make test`, `cd web && pnpm test`, `bash scripts/test-chaos-scripts.sh`. Expected: all pass.
- [ ] **Step 2:** On the laptop: `make down && make cluster up && make check SET=ci SPEED=60`. Expected: PASS; its report line shows `silent: 0`. Then `make replay SET=ci SPEED=60` with your own account following a team in the set: no push reaches it (#47 check by hand), and `/games` doesn't list the replay.
- [ ] **Step 3:** Push and open the PR; add the label `chaos`. Expected: CI green; Chaos workflow `8 of 8 passed`, each line `silent 0`.
- [ ] **Step 4:** Fix anything red and re-run before the AWS session.

### Task 12: The AWS session (the user runs the commands)

Stop and give the user numbered steps with the commands below and what each should print. Read and explain the output; run nothing that creates, changes, or deletes AWS resources.

- [ ] **Step 1:** `aws sso login --profile sports`; `caffeinate -i make aws-up TAG=pr<N>`. Expected: the app at `https://sports.<domain>`; `make smoke` with `BASE_URL`/`KUBE_CONTEXT` passes.
- [ ] **Step 2: Measurements** (spec 3.1–3.2).
  - Time `KUBE_CONTEXT=sports-notifier-aws bash -c "source scripts/chaos-lib.sh; chaos_node_run <node> 'crictl --version; command -v nsenter ss'"`. Expected: a version and two paths, in under 15 s.
  - Then `CHAOS_CUT_KILL=0 make chaos SCENARIO=stage1-cut KUBE_CONTEXT=sports-notifier-aws BASE_URL=https://sports.<domain>`. If it passes, the policy alone cuts: set the EKS default to `0` in a commit. If it fails "still in stage1 after 50 s", keep `1`.
- [ ] **Step 3:** If a live game is on, the user follows it on the phone through the suite.
- [ ] **Step 4:** `make chaos KUBE_CONTEXT=sports-notifier-aws BASE_URL=https://sports.<domain>`. Expected: `9 of 9 passed`, `silent 0` except on `node-loss`.
- [ ] **Step 5:** Screenshots of the dashboard links for `broker-crash` and `node-loss` (through `make grafana` with the AWS context). Copy `target/chaos/<run>/` and the screenshots to `docs/evidence/phase-6-1/`. Commit.
- [ ] **Step 6:** `caffeinate -i make aws-down`, then `make aws-status`. Expected: `nothing billing`. Remind the user if they stop before this step.

### Task 13: Merge

- [ ] **Step 1:** Final whole-branch review; fix findings. Update `docs/evidence/phase-6-1/` and the CLAUDE.md line with the live results (times from `node-loss`, the measurement outcome).
- [ ] **Step 2:** CI green, then merge the PR (memory: decide-by-default) and update the Phase 6 memory.
