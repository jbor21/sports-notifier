# Phase 6.1: Chaos on EKS — design

Status: approved, 2026-10-26. Parent document: `DESIGN.md` (build order step 6, decisions 12–14). The chaos suite itself is the Phase 4.2 spec (`2026-10-11-phase-4-2-chaos-design.md`); this spec only says what changes to run it on EKS. If this spec and either of those disagree, `DESIGN.md` wins, then 4.2, and this spec gets fixed.

## Goal

Run the chaos suite on the AWS cluster: three machines in three zones, three Kafka brokers, the real network policies. Add the one fault a laptop cannot show, a whole machine dying. Fix #47 first, because the checker will run on the cluster that real accounts use.

The resume line this part makes true: "the correctness checker passes on EKS while pods, a Kafka broker, and a whole machine are killed."

Phase 6 is split in three, each with its own spec, plan, PR, and at most one AWS session: **6.1 chaos on EKS** (this spec), 6.2 KEDA and a load test, 6.3 README evidence and a static UI demo (on the Prism mock).

Not in 6.1:
- KEDA and the load test (6.2). The worker counts stay fixed at two.
- #52 (a Redis crash loses dedup claims, so a push can repeat). Still after Phase 6; it is why the machine-dies scenario never picks Redis's machine (section 4.3).
- Running the suite on EKS from GitHub Actions. The Chaos workflow stays on k3d; the EKS run is by hand, in the user's AWS session.

### Decided before this spec (brainstorm, 2026-10-26)

- The 6.1 / 6.2 / 6.3 split, and short specs that point at `DESIGN.md` instead of repeating it.
- #47: replayed games reach only synthetic users, through a Redis set `synthetic:users`, and `/games` hides replayed games (section 1).
- Crash on EKS through a privileged node debug pod running `crictl stop --timeout 0`; exit 137 stays the proof (section 3.1).
- Cut on EKS through the same `chaos-cut` NetworkPolicy. Measure first whether the VPC CNI's policy engine cuts connections already open; if not, kill them from the node (section 3.2).
- A machine dying: terminate one EC2 instance. Never Redis's machine; the replacement must land in the same zone (section 4.3).
- Sockets that go silent count as down from their last received frame; the checker pings every 5 s, and the report counts pushes excused this way (section 2).

### Done when

1. `make test` and the web tests pass, including the new tests (section 6).
2. CI is green, and the Chaos workflow (k3d, label `chaos` on the PR) passes all eight scenarios with the changes.
3. On EKS, in one AWS session: `make chaos` passes all nine scenarios (section 4), with `KUBE_CONTEXT=sports-notifier-aws`.
4. The reports and two dashboard screenshots (broker crash, machine dies) are in `docs/evidence/phase-6-1/`.
5. If a live game is on during the session: its alerts reach the user's phone through the poller on AWS (moved from Phase 5). If none is on, this moves to the 6.2 session.
6. `make aws-down` ends with nothing billing.
7. Docs updated (section 7); `docs/study/phase-6-1-quiz.md` written.
8. The PR from `phase-6-1-chaos-eks` is merged.

### Branch

`phase-6-1-chaos-eks`, worktree `.worktrees/phase-6-1-chaos-eks`, from main at `4033006`.

---

## 1. Replayed games reach only synthetic users (#47)

**Problem.** A replayed game keeps its real teams and league (`nfl-buf`, `nfl`). Any real account that follows one of them gets the replay's made-up pushes, in its history and its badge. On EKS that means real users get fake alerts on every `make check`.

**Fix.** For a replayed game, Stage 1's audience is intersected with the set of synthetic users.

- `RedisKeys.SYNTHETIC_USERS` = `synthetic:users`, a set of user ids.
- user-pref adds the id when it creates a user through the keyed `ck_` signup (#51), and removes it when that user is deleted.
- The follow-set rebuild refills it from `users WHERE username LIKE 'ck\_%'` along with the `sub:*` sets, and `FollowSets.SHAPE_VERSION` goes up by one, so a cluster with the old shape rebuilds before Stage 1 resumes.
- `GameIds.isReplay(gameId)` (the `-rp` marker) is the one helper everything uses.
- `AlertAudience.build`: when `isReplay(play.gameId())`, each alert's audience gets one more `SINTERSTORE` with `synthetic:users`, in the same pipeline. One command per alert, no lookup per push.
- If `synthetic:users` is missing, the intersection is empty: a replay reaches nobody. That is the safe direction. The rebuild restores it.
- `/games` (`GameRoutes` / `GameSummaries`) leaves out replayed games, so real users don't see them in their list. Simulated games (`-sim`) stay visible: `make simulate` is how a developer sees pushes on their own account.

**Consequence.** `make replay` on its own now pushes to nobody (no synthetic user follows anything). It still drives the pipeline, the dashboard, and the detectors; the checker is what gives replays an audience.

**The checker is unaffected.** Its oracle computes audiences in Java from the run's own users only (`Snapshots`, `Audience`), and those are all `ck_` users.

## 2. Sockets that go silent

**Problem.** A machine that dies sends nothing: no close frame, no TCP reset. A socket through it looks open. Neither the checker's `SocketClient` nor the UI's `connection.ts` has a deadline for an answer to its ping, so the socket stays "up" until TCP itself gives up, which on Linux takes minutes. For the checker, pushes that went to the dead gateway in that time count as "missing while the socket was up": a false FAIL. For a real user, the app shows live while nothing arrives. (Phase 4.2's gateway crash never showed this: a killed process on a living machine still gets a TCP reset sent for it.)

**Fix, checker.** `SocketClient` pings every 5 s instead of 25 s (`tools.Checker` passes the interval; the UI stays at 25 s). It remembers when it last received any frame. At each ping, if nothing has arrived since the previous ping, the socket is dead: it aborts it and reconnects as after any close. The gap in `SocketLog` then starts at the last received frame, not at the abort, and is marked silent (`Gap.silent`). With 5 s pings a silent gap is about 10 s long. `Verifier`'s rule is unchanged: a push missing inside a gap is excused only if history has it. A socket that ends any other way (close frame, reset, error) keeps its gap starting at the moment it ended and is not silent, so the 4.2 scenarios are judged exactly as before.

**Counted separately.** `Verifier.Result` gains `allowedInSilentGaps`: the excused pushes whose excusing gap is a silent one (counted in `allowedInGaps` too). The report's sockets line becomes `missing N (allowed in disconnects: A, silent: S)`, and `chaos.sh` adds `silent S` to each scenario's summary line. Expected: 0 in every scenario except `node-loss`, so a non-zero count elsewhere is a reason to read that scenario's log.

**Cost of 5 s pings.** About 750 sockets (500 users, some with two tabs) send about 150 pings a second across two gateways, each answered with a few bytes. The gateway's 60 s idle close is unaffected.

**Fix, UI.** `connection.ts` gets the same rule on its ping tick (the Web Worker clock from #57): no message since the previous ping, so close and reconnect with backoff, then refetch as after any reconnect. `docs/api/websocket.md` gets one line: a client should treat an unanswered ping as a dead connection by the next ping.

**Why this rule.** A healthy socket always answers a ping with a pong, so "nothing in 25 s after a ping" means the path is gone. The longest a user can sit on a dead socket drops from minutes to under 50 s, and the checker's to about 10 s. Backdating the checker's gap only for this case keeps every other judgement as strict as it was.

## 3. Faults on EKS

On k3d the faults reach into the node with `docker exec`. On EKS there is no Docker on the Mac side; the node is an EC2 machine. `scripts/chaos-lib.sh` picks the platform from the context: `k3d-*` uses today's functions; anything else uses the EKS ones below. The scenarios in `chaos.sh` don't change.

### 3.1 Crash

`kubectl debug node/<node> --profile=sysadmin` with a small public image pinned by version (busybox) starts a pod on the pod's machine that shares the machine's processes and network and mounts its disk at `/host`. In it: `chroot /host crictl stop --timeout 0 <container id>`. The debug pod is deleted afterwards (always, from the cleanup trap too). Proof stays the same: the restarted container's last exit is 137. Plan task 1 checks by hand that the EKS node image has `crictl` and that a debug pod starts in a few seconds (it adds to the fault time; anything under 15 s is fine at 60×).

### 3.2 Cut from Kafka

The same `chaos-cut` NetworkPolicy (egress only to Redis and DNS). EKS enforces it through the VPC CNI's policy agent (eBPF), not k3s's iptables, so whether a connection already open survives a new policy is unknown. Plan task 1 measures it: apply the policy to a Stage 1 pod and see whether it leaves its group within 50 s.

- If it does, the cut is the policy alone.
- If it doesn't, the debug pod on that machine kills the pod's open Kafka connections from inside the pod's network namespace, with the machine's own tools: `chroot /host nsenter -t <container pid> -n ss -K '( dport = :9092 )'`. Their next packets are new connections, which the policy refuses.

The proof is unchanged: by 50 s the cut pod's address has left the group and the other copy is still in it.

### 3.3 Kafka admin commands

With three brokers, the group reads (`chaos_members`) and the steady check run on a broker that is Ready and is not the one at fault, instead of always `dual-role-0`. `chaos_steady` waits for all three brokers and for no under-replicated partitions.

## 4. The scenarios on EKS

Nine, each one checked replay of `evening` at 60× with one fault at 40% of the replay, as in 4.2. The pass rule is 4.2's: the checker passes and the fault provably landed. A fault step that cannot run fails its scenario, never the suite.

### 4.1 The eight from 4.2

`stage1-crash`, `stage2-crash`, `history-crash`, `gateway-crash`, `gateway-rollout`, `stage1-cut`, `stage2-cut` run as written, with the EKS fault functions. `gateway-rollout`'s request loop goes through the real front door (`BASE_URL=https://sports.<domain>`: NLB, Traefik), so "no 5xx" now covers the load balancer too.

### 4.2 `broker-crash` on three brokers

On k3d, a broker crash stops Kafka entirely until it restarts. On EKS it should only move leadership. The scenario crashes the broker that leads the most partitions of `game-events` and `notifications` and adds two proofs:

- Within 30 s, every partition it led has a new leader on another broker (in another zone), and none is left without one.
- Before the next scenario, it is back in every partition's in-sync replicas (no under-replicated partitions).

### 4.3 `node-loss`: a machine dies (EKS only)

`aws ec2 terminate-instances` (SSO profile `sports`) on one node; the instance id comes from the Node's `spec.providerID`. Everything on it goes at once: a broker (there is one per machine), and whichever app pods it holds. Its Auto Scaling group starts a replacement.

**Which machine.**
- Never Redis's (`redis-0`). Losing Redis loses Stage 2's claims, so a push can be delivered twice: #52, a known limit, after Phase 6.
- Never Postgres's (`postgres-0`). Postgres would come back (its volume is zonal, like the broker's), but it adds minutes and tests nothing new.
- Never the checker's. The scenario starts the checker Job on Redis's machine (a pod affinity on `redis-0`), so with three machines there is always one left that holds neither Redis, Postgres, nor the checker.
- Among what is left, prefer one without the observability pod (its data lives in the pod; losing it loses the screenshot). If there is no such choice, the machine is the remaining one and the scenario says the dashboard data was lost.
- On k3d (one node) the scenario is skipped with a note and does not count; `all` on k3d stays eight.

**Proof the fault landed.**
- The instance reaches `shutting-down` / `terminated`, and its Node goes NotReady or is removed.
- A replacement Node joins, Ready, in the **same zone** as the dead one. The ASG keeps zones balanced, so it should; if not, the scenario fails as "fault did not land", because the broker's disk (zonal EBS) cannot follow it.
- The broker comes back on the replacement and rejoins every partition's in-sync replicas within 15 minutes.

The scenario reports, with the usual line: seconds until the Node went NotReady, until the replacement was Ready, until the broker was back, until nothing was under-replicated. Expected: the checker run ends (on two brokers, `min.insync.replicas=2` still writable) well before the broker returns, about 5 to 10 minutes after the termination. The next scenario waits for steady state with a 15-minute limit instead of 3.

**What it exercises that nothing else does.** Kafka with one zone gone; consumer group sessions timing out instead of closing; Kubernetes rescheduling pods off a Node that vanished; a gateway that dies silently (section 2).

## 5. The AWS session

The user runs `make aws-up`, `make chaos`, and `make aws-down`; Claude reads and explains the output, and never runs those or any `terraform` command. In order:

1. `aws sso login --profile sports`, then `caffeinate -i make aws-up TAG=pr<N>` (about 25 min).
2. Plan task 1's measurements (about 10 min): `crictl` through a debug pod, the debug pod's start time, whether the policy cuts open connections.
3. If a live game is on: the poller is already on (aws-up's ESPN check passed), so follow the game on the phone. The suite can run while it plays.
4. `make chaos KUBE_CONTEXT=sports-notifier-aws BASE_URL=https://sports.<domain>` (about 75 min: eight scenarios of about 6 min, plus about 20 min for `node-loss` and its recovery).
5. Screenshots of the Pipeline dashboard for `broker-crash` and `node-loss` (the summary prints the links).
6. `caffeinate -i make aws-down` (about 15 min), then `make aws-status`: nothing billing.

**Cost.** About 2.5 hours up at about $0.45 an hour, so about $1.20; the replacement machine replaces one that stopped billing. A live game adds about $0.45 an hour. The $25 budget alarm is far away.

## 6. Testing

- **#47:** `AlertAudience` tests: a replayed game reaches a synthetic follower and not a real one following the same team; a missing `synthetic:users` reaches nobody; a `-sim` game is unaffected. user-pref: a keyed signup adds to the set, delete removes, the rebuild refills it. `/games` leaves out a replayed game and keeps a simulated one.
- **Silent sockets:** a `SocketClient` test against a server that stops answering pings (but keeps the TCP connection) sees the socket aborted by the second ping and a silent gap starting at the last frame; one against a server that closes normally keeps the old gap start and is not silent. A `Verifier` test: a push excused by a silent gap counts in both `allowedInGaps` and `allowedInSilentGaps`, one excused by an ordinary gap only in the first; a `Report` test for the new line, and a `test-chaos-scripts.sh` case that the summary shows `silent`. A `connection.ts` unit test with a fake ticker: no message between two ticks closes and reconnects.
- **Chaos scripts:** `scripts/test-chaos-scripts.sh` gains fake-kubectl cases for the EKS paths: a debug pod that never starts, a broker at fault excluded from admin commands, and `node-loss` choosing the machine (Redis, Postgres, checker excluded; a replacement in another zone fails "fault did not land").
- **On the laptop:** the Chaos workflow (label `chaos`) passes all eight scenarios; `node-loss` is skipped.

## 7. Docs

- `DESIGN.md`: build order step 6 split into 6.1–6.3; #47 resolved; the silent-socket rule.
- `CLAUDE.md`: the Phase 6.1 line, `synthetic:users` in the Redis list, `make chaos` on EKS.
- `docs/api/websocket.md`: the unanswered-ping rule.
- `docs/evidence/phase-6-1/`: the summary, each scenario's line, two screenshots.
- `docs/study/phase-6-1-quiz.md`.
