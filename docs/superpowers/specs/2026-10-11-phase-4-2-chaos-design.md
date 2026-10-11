# Phase 4.2: The chaos suite on k3d — design

Status: draft for review, 2026-10-11. Parent document: `DESIGN.md` (decisions 3, 13, 14). If this spec and `DESIGN.md` disagree, `DESIGN.md` wins and this spec gets fixed. `DESIGN.md`'s build order (step 4.2) and decision 14 are updated with this spec.

Phase 4 is three sub-phases: 4.1 replay and checker (merged, PR #46), **4.2 chaos suite on k3d (this spec)**, 4.3 traces and metrics. Each gets its own spec, plan, and PR.

## Goal

Back decision 14's claim under failure: while a checked replay runs, crash, restart, or cut one piece of the pipeline, and the 4.1 checker still passes. Every synthetic user's history has every expected push exactly once; no socket gets a push twice or out of game order; a push missing from a socket is allowed only if that socket was really down when the push was delivered.

Also in 4.2: the fixes the chaos runs need or trip over (#9, #10, #17, #31, #44), the checker's own gaps that chaos exposes (#48, #49), and #12, which the Stage 2 crash scenario closes.

Not in 4.2: tracing and metrics (4.3); the chaos suite on EKS (Phase 6); Redis or Postgres kills (section 10); #47 and #51 (Phase 6); #36, the live check (before Phase 5).

### Decided before this spec (brainstorm, 2026-10-11)

- Plain `kubectl` scripts and NetworkPolicies, not Chaos Mesh.
- Two copies each of Stage 1, Stage 2, and the gateway, as the chart's default.
- One checked run per scenario; the checker's verdict is the scenario's verdict.
- Eight scenarios, including a History writer crash (section 3).
- The suite runs on the laptop (`make chaos`) and from a manual GitHub workflow, not on every push.
- #48: Stage 2's delivery script records the delivery time from Redis's `TIME`, so every delivery time comes from one clock.
- #31: the gateway uses Kubernetes' native `lifecycle.preStop.sleep`, not an exec'd `sleep`.

### Done when

1. `make test` passes, including every test in section 8.
2. CI is green: `make cluster up smoke check e2e` with two copies of Stage 1, Stage 2, and the gateway.
3. `make chaos` passes all eight scenarios on the laptop; its summary (section 4.4) is pasted in the PR.
4. The manual Chaos workflow (section 5) has run all eight once and passed.
5. `make check-selftest`, `make replay-detectors` (golden timelines unchanged), and `scripts/persistence-check.sh` pass on the laptop.
6. Docs updated (section 11), and `docs/study/phase-4-quiz.md` has a 4.2 section. The user is quizzed only on request.
7. The PR from `phase-4-2-chaos` is merged.

### Branch

`phase-4-2-chaos`, worktree `.worktrees/phase-4-2-chaos`, from main at `c9448bc`. The spec commit also carries three housekeeping fixes: `__pycache__/` in `.gitignore`, `RunId`'s comment (six base-36 characters last until 2038, not 2059), and `DESIGN.md`'s note that waves 2 and 3 move to Phase 7, which split the wave list in two and now follows it.

---

## 1. Two copies by default

`values.yaml` sets `replicas: 2` for `stage1-worker`, `stage2-worker`, and `gateway`, on the laptop, in CI, and later on AWS. `make check` and CI's check then run the system in its multi-instance shape every time: lanes split between two members of each consumer group, and sockets split between two gateways (the gateway Service spreads connections; sessions and routing are already in Redis). History stays at one copy.

About 1 GB more on the laptop (three pods at 256–512 MiB).

## 2. The three kinds of fault

### 2.1 Crash

**Problem.** `kubectl delete pod` sends SIGTERM: the shutdown hook runs, the consumer leaves its group cleanly, and nothing is really tested. A real crash (out of memory, a node gone) gets no chance to clean up. And the obvious `kubectl exec <pod> -- kill -9 1` does nothing: Java is PID 1 in its container, and Linux ignores SIGKILL sent to a namespace's PID 1 from inside that namespace.

**Fix.** Crash from the k3d node, which sees the container as an ordinary process: `docker exec k3d-<cluster>-server-0 crictl stop --timeout 0 <containerId>`, with the id from the pod's `status.containerStatuses[0].containerID` (without the `containerd://` prefix). `crictl` is on the node (`/bin/crictl`, checked 2026-10-11). Kubernetes then restarts the container in the same pod, so the pod keeps its name (a gateway keeps its gateway id).

**What happens.** The dead consumer does not leave its group; Kafka hands its lanes to the other copy after the session timeout (45 s, the default), and the restarted process joins as a new member. Records it had read but not committed are read again; Stage 2's dedup key and History's `ON CONFLICT DO NOTHING` absorb them. The 45 s stays unless a run gives a reason to change it; the report's worst push delay (section 4.4) shows what it costs.

**Proof it is a crash.** Plan task 1 checks once that `crictl stop --timeout 0` kills without SIGTERM: the previous container's log (`kubectl logs --previous`) has no shutdown-hook output.

### 2.2 Rolling restart

`kubectl rollout restart deployment/gateway`, then `kubectl rollout status`. This is the polite path, every deploy and every scale-in: SIGTERM, the shutdown hook, and Kubernetes moving traffic. It is the test for #10 and #31 (section 6).

### 2.3 Cut from Kafka

**Problem.** k3s enforces NetworkPolicies with kube-router, whose firewall accepts packets of connections that are already open before it checks any policy (`KUBE-NWPLCY-COMMON`: `--ctstate RELATED,ESTABLISHED -j ACCEPT`). Kafka clients hold their connections for minutes. Measured on the laptop on 2026-10-11, with a Stage 2 worker and an egress policy that allowed only Redis and DNS: a new connection to Kafka was refused at once, but the open one survived 68 s, and the worker was still a member of `stage2` with all 48 lanes, past the 45 s session timeout. A policy alone cuts nothing.

**Fix.** A cut is three steps:

1. Label the pod `chaos=cut`.
2. Apply the NetworkPolicy `chaos-cut`: it selects `chaos=cut`, type Egress, and allows only Redis (port 6379, pods named `redis`) and DNS (port 53, UDP and TCP, any namespace).
3. On the node, delete the pod's tracked Kafka connections: `conntrack -D -s <podIP> -p tcp --dport 9092` (`conntrack` is on the node).

In the same measurement, step 3 made the worker lose Kafka (`DisconnectException`, "Node may not be available"), and Kafka removed it from the group. To heal, delete the policy and remove the label; the worker rejoined as a new member with no restart.

**Why this case.** The worker keeps Redis but loses Kafka, the "zombie": a Stage 2 that has a batch in hand can still deliver it while the other copy, now owning those lanes, reads the same batch again. A Stage 1 cut mid-send holds buffered records that may still go out after the heal, after the other copy has sent the same messages. Both are what the dedup key and the `det:` version are for.

**Allowed while cut.** Stage 1's `producer.flush()` can block for up to the producer's delivery timeout (120 s), so its loop stops beating and the liveness probe may restart the pod. That is real behavior, not a failed scenario; the summary notes it.

## 3. The scenarios

Each scenario is one `make check` run on the `evening` set (10 games, all four leagues) at 60× (192 replay minutes, so about 3.2 minutes of replay, about 5 minutes per run with setup and the end condition). With 10 games over 12 `game-events` lanes, both Stage 1 copies have busy lanes; 500 users over 48 `notifications` lanes keep both Stage 2 copies busy.

| Scenario | Fault (section 2) | Target | Proof the fault landed |
|---|---|---|---|
| `stage1-crash` | crash | one `stage1-worker` pod | its restart count went up |
| `stage2-crash` | crash | one `stage2-worker` pod | its restart count went up |
| `history-crash` | crash | the `history` pod | its restart count went up |
| `gateway-crash` | crash | one `gateway` pod | its restart count went up |
| `gateway-rollout` | rolling restart | `deployment/gateway` | both gateway pods are new; no 5xx (below) |
| `broker-crash` | crash | the Kafka pod (`<release>-dual-role-0`) | its restart count went up |
| `stage1-cut` | cut, 60 s | one `stage1-worker` pod | its IP left group `stage1` during the cut |
| `stage2-cut` | cut, 60 s | one `stage2-worker` pod | its IP left group `stage2` during the cut |

- **Which copy.** The first pod in name order: repeatable, and either copy carries traffic.
- **When.** At 40% of the replay (77 s after "run started" at 60× on `evening`), so lanes are busy and there is time to recover before the games end.
- **Group membership** is read with `kafka-consumer-groups.sh --describe --group <g> --members` inside the Kafka pod. The cut is held 60 s; membership is checked at 50 s. A cut whose pod is still a member then is "fault did not land", a failed scenario.
- **`broker-crash`** with one broker is a full Kafka outage, not a leader election (that is Phase 6, with three brokers). Event Processing keeps accepted plays in `outbox:<gameId>` and its retry loop sends them once Kafka is back; the checker's lag check already reads a Kafka error as "not zero lag yet".
- **`gateway-rollout`'s request loop.** From the start of the rollout until `rollout status` returns plus 10 s, the script requests `GET http://localhost:8080/` (the UI's `index.html`, through Traefik, as a browser would) every 100 ms. Any 5xx or failed connection fails the scenario, whatever the checker says. This is #31's test.
- **#12** asks for two Stage 2 workers running together, one killed mid-batch. `stage2-crash` is exactly that, with the checker's verdict; it closes the issue.

## 4. The runner

### 4.1 `make chaos`

`make chaos` runs all eight in table order; `make chaos SCENARIO=stage2-crash` runs one. `SET` and `SPEED` default to `evening` and `60`. Both call `scripts/chaos.sh`. Its fault steps (crash, rollout, cut, heal, membership) are functions in `scripts/chaos-lib.sh`, so Phase 6 can replace the k3d-only ones (the crash and the conntrack step) without touching the scenarios.

### 4.2 One scenario

1. **Steady first.** Wait (up to 3 minutes) until every Deployment is fully ready and each of `stage1`, `stage2`, and `history` has as many group members as its Deployment has pods; otherwise the scenario fails as "cluster not steady", and the next one still runs.
2. Start the checker Job through `scripts/run-job.sh checker CHECK_SET=… CHECK_SPEED=…`, in the background, its output saved to the scenario's log.
3. Wait for the checker's `[Checker] run started` line (section 7.3).
4. Sleep until 40% of the replay.
5. Apply the fault and prove it landed (section 3). For a cut: hold 60 s, check membership at 50 s, heal.
6. Wait for the checker to finish. The scenario passes only if the fault landed, the checker exits 0, and (for `gateway-rollout`) the request loop saw no 5xx.

A failed scenario does not stop the suite; `make chaos` exits non-zero at the end if any failed. A failure saves `make logs` output beside the scenario's log.

### 4.3 Where the output goes

`target/chaos/<UTC time>/`: one `<scenario>.log` per scenario (the checker's report and the runner's own lines), and `summary.txt`.

### 4.4 The summary

```
chaos  set evening  speed 60x  2026-10-12T14:03Z
stage1-crash     PASS  pushes 6,573  socket misses excused 0     worst delay 51.2 s  stage1-worker-…-x7k2p restarted at +77 s
stage2-cut       PASS  pushes 6,573  socket misses excused 0     worst delay 63.0 s  stage2-worker-…-q9d1m left stage2 at +127 s, back at +141 s
gateway-crash    PASS  pushes 6,573  socket misses excused 211   worst delay 1.4 s   gateway-…-m2c8v restarted at +77 s
gateway-rollout  PASS  pushes 6,573  socket misses excused 388   worst delay 1.1 s   2 new pods, 0 of 1,412 requests failed
...
8 of 8 passed
```

"Worst delay" is the checker's largest `detectedAt` → socket arrival: the length of the stall the fault caused. The checker's report gains it beside p50 and p99 (section 7.3). Numbers above are illustrative.

## 5. The manual workflow

`.github/workflows/chaos.yml`, triggered only by hand (`workflow_dispatch`), with one input, `scenario` (a choice: `all` or one of the eight). It sets up the runner like CI's cluster job, runs `make cluster up chaos SCENARIO=…`, and uploads `target/chaos/` as an artifact whatever the result (and `make logs` on failure). Timeout 90 minutes (eight runs of about 6 minutes plus the cluster).

Not on every push: all eight take about 45 minutes, and the repo is private, so the runner is small and minutes count. CI keeps its one checked run, now with two copies.

## 6. Fixes in the product

### 6.1 #10: the gateway's shutdown order

**Problem.** The shutdown hook removes this gateway's `conn:` entries, then closes sockets, and only then stops the renewal scheduler. A renewal already running can put every entry back, so Stage 2 keeps publishing to a gateway that is gone (for up to 90 s; history keeps the pushes). Late `onClose` handlers can also run after the Redis pool is closed.

**Fix.** In order: stop the renewals and the idle sweeper and wait for a running one to finish; remove the `conn:` entries; close every socket with 4000 (reconnect now); stop the Javalin app; stop the gateway channel; close the pool last.

### 6.2 #31: no 504s during a gateway rollout

**Problem.** Kubernetes removes a terminating pod from the Service's endpoints asynchronously; for a few seconds Traefik still sends it requests after its shutdown hook has stopped Javalin. Seen in Phase 3b: about 20 s of 504s.

**Fix.** The gateway container gets `lifecycle.preStop.sleep.seconds: 5`, Kubernetes' native sleep action, so the image needs no shell. Kubernetes runs the preStop hook before SIGTERM, so for 5 s the gateway keeps serving while the endpoints update. `terminationGracePeriodSeconds` stays at 30, above the 5 s plus the shutdown hook. The chart gets it through an optional `preStopSleepSeconds` on a service in `values.yaml`, set only for the gateway.

**Version.** The sleep action is GA since Kubernetes 1.34. k3d runs v1.35.5 (checked 2026-10-11: a server-side dry run of a pod with `preStop: {sleep: {seconds: 5}}` keeps the field). Phase 5's EKS cluster must be 1.34 or later; noted for the Phase 5 spec.

**Test.** `gateway-rollout`'s request loop (section 3).

### 6.3 #9: a tab reload can remove a live `conn:` entry

**Problem.** When a user's last socket on a gateway closes, the gateway `ZREM`s its entry from `conn:<userId>`. If a new socket for the same user registers (`ZADD`) on that gateway just before the `ZREM` lands, the open socket has no routing entry until the next renewal (up to 30 s), and its live pushes go nowhere.

**Fix.** After the `ZREM`, the gateway checks whether it holds a socket for that user again, and if so adds the entry back. Both run on the gateway that holds the sockets, so the check sees every socket that could have registered.

### 6.4 #17: fan-out sets that expire mid-page

**Problem.** `MomentFanout` and `AlertAudience` build an audience in temporary sets that expire 60 s after they are built. Paging a large audience slowly (Kafka sends slowed by a fault) could find a set gone partway and skip the rest of the users silently.

**Fix.** Refresh the TTL of every temporary set the page reads (`EXPIRE`) with each page.

### 6.5 #44 and rebalance logs

**Problem.** Stage 1 counts a poison play's failed tries per `partition@offset` in memory. After a rebalance the count is stale: the lane's new owner starts over, and the old owner keeps an entry it will never clear. The key also leaves out the topic.

**Fix.** Stage 1, Stage 2, and History subscribe with a `ConsumerRebalanceListener` that logs every change (`[Stage1] lanes assigned: …`, `revoked: …`, `lost: …`). Stage 1's also drops the counts of revoked and lost lanes. The count's key becomes `topic-partition@offset`. The logs are also what a person reads to see a fault's effect.

## 7. Fixes in the checker

### 7.1 #48: when a missing push is excused

**Problem.** A push missing from a socket is excused when history has it and the socket was down at some time between the push's Kafka timestamp and the arrival of the next push published after it. Two weak spots: with no later push (often the final), the window runs to the end of the run, so a disconnect minutes later excuses a lost push; and with two Stage 1 producers, Kafka timestamps in one user's lane need not follow delivery order, so "the next push" can be the wrong one.

**Fix.** Ask when the push was actually delivered, and use one clock for it.

- **Stage 2 records the delivery time.** The delivery script's claim, `SET notif:<messageId>:<userId>`, stores the time of delivery in milliseconds, from the `TIME` the script already calls (`seconds × 1000 + microseconds ÷ 1000`), instead of `'1'`. The dedup check is unchanged (`EXISTS`), so claims written as `'1'` before the deploy still dedup.
- **The checker uses Redis's clock.** At T0 and at the end of the run, the checker reads Redis's `TIME` and takes its own clock's offset from it (the midpoint of the round trip); socket arrivals and gaps are shifted into Redis time with the average of the two. On the laptop the offset is near zero; on EKS the checker and Redis run on different nodes.
- **The rule.** A push missing from a socket is excused only if history has it, its claim holds a delivery time, and the socket was down (a gap: from the close to the next `hello`) at some time within **2 s either side of that delivery time**. The 2 s covers pub/sub transit and the gateway's handling. No claim, no history row, or no gap near the delivery time is a `socket_missing` finding.

The checker reads the claims with one pipelined `GET` per missing push, before cleanup. The ordering logic (`nextArrival`) and the Kafka-timestamp window go away.

### 7.2 #49: time limits and exit codes

**Problem.** The checker and replay Jobs have no time limit, so a hung checker runs until CI's 30-minute timeout and `make check` on the laptop waits forever. `run-job.sh` turns the checker's exit 2 (SETUP FAILED) into 1, and waits only about 60 s after the log stream ends, so a log stream that drops early reports a running Job as failed.

**Fix.**

- **Deadline.** `run-job.sh` sets the Job's `activeDeadlineSeconds` from a `DEADLINE` argument. The Makefile computes it from the set's replay length and the speed: replay minutes × 60 ÷ `SPEED`, plus 15 minutes for a check (signup, the 5-minute run timeout, verification, cleanup) or 5 for a replay. Replay lengths sit beside the targets (`ci` 136 minutes, `evening` 192). `check-clean` gets 10 minutes. The CronJobs also carry a default (`checker.activeDeadlineSeconds: 3600`, `replay.activeDeadlineSeconds: 14400`) for a Job started without one.
- **Waiting.** After the log stream ends, `run-job.sh` waits for the Job's own `Complete` or `Failed` condition, with no fixed cap (the deadline is the cap).
- **Exit code.** It exits with the pod's container exit code, so 0, 1, and 2 stay distinct. A Job stopped by its deadline exits 1 with "time limit reached".

### 7.3 Two report lines

- `[Checker] run started: T0 <time>, replay <seconds> s at <speed>x`, printed right after the replay's clock starts. `chaos.sh` times its fault from this line.
- The latency line gains the maximum: `latency detectedAt → socket: p50 … p99 … max …`.

## 8. Tests

### 8.1 Unit

- The delivery script stores a time from `TIME` and still returns 0 for a claim that exists, whether it holds a time or `'1'`.
- `Verifier`, from hand-built logs and claims: a missing push with a gap covering its delivery time is allowed; a gap 3 s after it is not; the #48 case (no later push, a disconnect minutes later) is a finding; a missing claim is a finding; the clock offset shifts socket times before the rule applies.
- Stage 1's attempt counts: dropped for revoked and lost lanes; the key carries the topic.
- The gateway's shutdown order, in a test with a fake registry and scheduler: no renewal runs after `removeAll`.

### 8.2 With Redis

- #9: a socket that registers between the last close's decision and its `ZREM` keeps its `conn:` entry.
- #17: a fan-out whose pages are slowed past a short TTL (the TTL a constructor argument in the test) still reaches every user.

### 8.3 Scripts

- `run-job.sh`: a Job whose container exits 2 makes it exit 2; a Job that outlives its deadline makes it exit 1 with "time limit reached" (against the laptop cluster, in plan task order).

### 8.4 Cluster

- Each of the eight scenarios passes (`make chaos`), on the laptop and once in the manual workflow.
- `make check SET=ci SPEED=60` with two copies passes in CI; `make e2e` (which restarts the gateway mid-game) passes with two gateways.

## 9. Order of work

1. Fault primitives in `scripts/chaos-lib.sh`, tried by hand: the crash (with the proof in section 2.1), the cut and heal, group membership, the rollout.
2. #48: the delivery script's time, the checker's clock offset, the new rule, with tests.
3. #49: deadlines, waiting on the Job condition, exit codes.
4. The two report lines (section 7.3).
5. Rebalance listeners and #44.
6. #17.
7. The gateway: #9, #10, #31 (`preStopSleepSeconds` in the chart).
8. Two copies by default; CI's check and e2e green.
9. `scripts/chaos.sh` and `make chaos`; run every scenario on the laptop; fix what they find (each fix with a test, and the scenario run again).
10. The Chaos workflow; run it once.
11. Docs and the quiz.

## 10. Docs to update

- `DESIGN.md`: build order 4.2 says what was built (eight scenarios, crash versus rolling restart, the cut as a policy plus conntrack, two copies by default, laptop and manual workflow), **merged** when it is; decision 14's chaos bullet lists the eight; decision 14's checker paragraph notes the delivery-time rule. (Housekeeping in the spec commit, above.)
- `CLAUDE.md` and `AGENTS.md`: "New in Phase 4.2", `make chaos`, the workflow, two copies, the next step (4.3).
- `docs/study/phase-4-quiz.md`: a 4.2 section.
- Close #9, #10, #12, #17, #31, #44, #48, #49 with the PR.

## Known gaps accepted in 4.2

- **The crash and the cut reach into the k3d node** (`crictl`, `conntrack` through `docker exec`). EKS nodes are not Docker containers on the laptop; Phase 6 replaces those two functions in `chaos-lib.sh` (for example with a privileged `kubectl debug node/…` pod).
- **The cut refuses, it does not black-hole.** kube-router rejects a blocked connection at once, so the worker knows quickly. A network that silently loses packets (long TCP timeouts) is the nastier case and is not tested.
- **One broker on the laptop**, so `broker-crash` is a full Kafka outage; a leader moving between brokers waits for Phase 6's three.
- **Redis and Postgres are not killed.** `DESIGN.md` treats a dead Redis or Postgres pod as a Kubernetes restart with no replicas. A Redis restart can lose the last second of claims, so a redelivered push can reach a socket twice; that is a known property of decision 9's single Redis, not something 4.2 tests. Tracked as issue #52, after Phase 6.
- **One fault per run**, at one point in the run. Several faults at once, or one at a random time, are not in 4.2.
- **The 45 s session timeout** decides how long a crashed worker's lanes stall. It is measured (the worst delay), not tuned, unless a scenario fails because of it.
- **Heals run on wall time** at 60×, as in 4.1; the checker is unaffected (4.1 spec, 2.4).
