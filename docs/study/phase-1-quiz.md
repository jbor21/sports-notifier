# Phase 1 quiz: Foundation

Interview-style questions on Phase 1: packaging, Docker, Kubernetes, Helm, k3d, and CI. Each question shows your answer from the first run. Below it, under a fold, are the feedback and a model answer. Try answering out loud before you open it.

To take or re-take it: ask Claude, "quiz me on Phase 1." Questions marked **Review** come first, then **Not yet taken**.

| # | Topic | First run |
|---|---|---|
| 1 | One image instead of eight | Got the core idea |
| 2 | Startup vs liveness vs readiness checks | **Review** |
| 3 | Why Redis and the poller are fixed at one copy | **Review** |
| 4 | Why the first smoke test failed | **Review** |
| 5 | Tagging images with the git commit, not `latest` | **Not yet taken** |
| 6 | What survives a Redis restart vs `make down` | **Not yet taken** |
| 7 | Why the smoke test follows LAL with GAME_START only | **Not yet taken** |
| 8 | Why every make target names the k3d cluster | **Not yet taken** |

---

## 1. One image instead of eight

> You have eight separate services but build only one Docker image. Why not one image per service? What would go wrong with eight?

**Your answer:** The services share code, and I don't want to risk stale code on one service.

<details>
<summary>Feedback and model answer</summary>

**Right:** version mismatch is the main reason.

**Add:**
- **A concrete failure.** A new gateway runs next to an old worker after `GameEvent` or a Redis key name changes. Nothing crashes. Notifications just go missing or come out wrong, so the failure is silent.
- **Why eight images buys almost nothing.** The 8 JARs were nearly identical: the same libraries plus a few hundred lines each. And updating one service alone is exactly what causes the mismatch.
- **The tradeoff.** Every deploy gives every service a new image, so they all restart. On one cluster that takes seconds.

**Model answer:** "The services share one codebase: the same event classes and the same Redis key names. With separate images it's easy to deploy a new gateway next to an old worker, and that fails silently. One image means every service always runs the same version. The cost is that every deploy restarts everything, which is cheap here. I'd split images if the services had separate codebases or very different dependencies."

</details>

---

## 2. Startup vs liveness vs readiness checks

> Every service has three health checks. What does the startup check protect against, and what goes wrong with only a liveness check?

**Your answer:** Not sure.

<details>
<summary>Feedback and model answer</summary>

**What each check does when it fails:**

| Check | Question it asks | If it fails |
|---|---|---|
| Liveness | Are you stuck? | Kubernetes kills and restarts the pod |
| Readiness | Can you take traffic right now? | The pod is removed from its Service until it passes; it is not restarted |
| Startup | Have you finished booting? | Keeps waiting (every 5 s, up to 2 min). The other two checks start only after it passes |

**What it prevents:** a JVM that is still booting can't answer `/health`, which to a liveness check looks exactly like "stuck." With liveness alone, Kubernetes restarts the service mid-boot, again and again. That is a crash loop on a healthy service. This happens most on slow or busy machines, such as CI runners.

**Why not a fixed "wait 60 s" delay:** it is wrong both ways. It wastes time when boot is fast, and it still kills the pod when boot is slower than the guess. The startup check ends as soon as the service is up.

**Model answer:** "Liveness restarts a service that stops responding. A JVM that's still booting looks exactly like that, so liveness alone can kill a healthy service over and over. The startup probe gives it up to two minutes to boot, and liveness and readiness only begin once it passes. Unlike a fixed initial delay, it doesn't slow down fast boots."

**In this repo:** `deploy/helm/sports-notifier/templates/_helpers.tpl` (`sports-notifier.httpProbes`) and the `probes` block in `values.yaml`.

</details>

---

## 3. Why Redis and the poller are fixed at one copy

> Most services have a copy setting you can raise. Redis and the ESPN poller are fixed at one. Why can't you run 2 of each? Give a separate reason for each.

**Your answer:** Not sure.

<details>
<summary>Feedback and model answer</summary>

**The test:** extra copies are safe when they split the work. They are unsafe when they duplicate the work or split the data. Workers pass the test: the Redis consumer group gives each event to exactly one worker.

**Redis splits the data.** A second Redis pod is a second, separate, empty database, not a backup.
- The Service spreads requests across both, so a signup can land on A while the login hits B: "user not found."
- The Lua script is the score authority only because one Redis runs scripts one at a time. With two, each keeps its own score, and the race the professor caught comes back.
- Real copies need replication (primary + replicas + Sentinel). DESIGN.md decision 6 chose not to build that.

**The poller duplicates the work.** Each poller sees the same play and posts it. Today event IDs are random UUIDs and timestamps come from each poller's own clock, so the dedup check accepts both, and every user is notified twice.
- Phase 2 builds IDs from ESPN's data. Both pollers then send the same ID, the Lua script drops the second, and a second poller becomes harmless.
- Until then it is fixed at one, with `strategy: Recreate` so an upgrade never runs two at once.

**Model answer:** "Copies are safe when they share work. Workers do: Redis hands each event to one consumer. Two Redis pods would be two unrelated databases. A signup and a login could hit different ones, and the Lua script stops being the single authority on the score. Real replication is Sentinel, which I chose not to run. Two pollers would send every play twice, because event IDs are random today. Once IDs come from ESPN's data, the dedup check makes a second poller harmless."

**In this repo:** `deploy/helm/sports-notifier/templates/redis.yaml` and `templates/espn-poller.yaml`. Both hard-code `replicas: 1`, with a comment saying why.

</details>

---

## 4. Why the first smoke test failed

> The first smoke run failed: its simulator ran, but the test user never got "LAL vs BOS has started." Other simulator runs were still going. Why did the notification never arrive, and why is that the system working correctly?

**Your answer:** Not sure.

<details>
<summary>Feedback and model answer</summary>

**What happened:**
1. For each game, the Lua script stores the newest accepted timestamp and rejects any event that isn't newer. That keeps the live score from going backwards.
2. Every simulator run plays the same game ID, `game-LAL-BOS`, and stamps events with its own clock at send time.
3. Another run's score event, stamped a split second after our game start, reached Redis first.
4. Our game start arrived with an older timestamp and was rejected as stale, so it was never published and no notification went out. Evidence: `Rejected stale event … game-LAL-BOS` at 21:59:02.976, the same second our simulator Job started.

**Why it is correct:** the script cannot tell a test overlap from a real late play. Refusing anything older than what it already saved is exactly its job. This is the same protection that fixed the professor's 43-instead-of-50 race. The real problem was test isolation.

**Fixes:** the smoke test now deletes running simulator Jobs before starting its own (`scripts/smoke-test.sh`). The lasting fix is IDs and timestamps from the data source (Phase 2 poller, Phase 4 recorded replay). This is logged as Deferred #5 in the Phase 1 spec.

**Model answer:** "Overlapping simulator runs reused the same game ID, and one run's score event, stamped a moment later, landed before our game start. The Lua script rejects anything older than the newest event for that game, so it dropped our game start as stale. That's the guarantee working: the score never moves backwards. The bug was test isolation, so the smoke test now clears other runs first. I confirmed it from the 'Rejected stale event' log line matching the run's start time."

</details>

---

## 5. Tagging images with the git commit, not `latest`

> `make up` tags each image with the git commit hash, plus a `-dirty-<time>` suffix when there are uncommitted changes. Why not just call every build `latest`?

<details>
<summary>Model answer</summary>

Kubernetes decides whether to restart pods by checking whether the pod's definition changed. If every build is called `latest`, the definition looks identical, so Kubernetes can keep the old pods running old code. And with `imagePullPolicy: IfNotPresent`, it won't even look for a newer `latest`. A new tag per build changes the definition, so every pod rolls onto the new code. The `-dirty-<time>` suffix covers the case where you changed code but didn't commit: same commit hash, still a new tag. A bonus is that every running pod tells you exactly which commit it runs.

**Model answer:** "A tag that never changes means Kubernetes sees no change and can leave old code running. Tagging with the commit hash, plus a timestamp for uncommitted work, guarantees each deploy rolls every pod and tells me exactly what's running. I tested it: a dirty build rolled all seven Java pods."

</details>

---

## 6. What survives a Redis restart vs `make down`

> If the Redis pod is deleted and restarts, are accounts still there? What about after `make down` and `make cluster up`? Why the difference?

<details>
<summary>Model answer</summary>

**Redis pod restart: the data survives.** Redis runs as a StatefulSet with a PersistentVolumeClaim, a disk that belongs to the pod's slot rather than the pod itself. Redis writes an append-only file (`--appendonly yes`) to that disk, and the new pod mounts the same disk and replays the file. Tested: set a key, deleted the pod, and read the key back.

**`make down`: the data is gone.** Deleting the cluster deletes its disks too. Every account, preference, and history entry is lost.

**Why that's acceptable for now:** Redis is the only store until Phase 3. Then Postgres becomes the source of truth for accounts, and Redis becomes a cache rebuilt from Postgres (DESIGN.md decision 8). In the cloud, Postgres is dumped to S3 before `terraform destroy` (decision 9).

**Model answer:** "Redis is a StatefulSet on a persistent volume with append-only persistence, so a pod restart reloads the data. Deleting the cluster deletes the volume, so accounts are lost. That's a known Phase 1 gap: in Phase 3 Postgres becomes the source of truth and Redis is rebuilt from it."

</details>

---

## 7. Why the smoke test follows LAL with GAME_START only

> The smoke test signs up a user who follows only LAL and only GAME_START notifications. Why not follow both teams and wait for a score update?

<details>
<summary>Model answer</summary>

- `game-LAL-BOS` is the first game of wave 1, and LAL is its home team.
- The simulator tags GAME_START with the home team, so it is always LAL.
- SCORE_UPDATE is tagged with whichever team scored, chosen at random.
- Fan-out currently matches only the tagged team (the Phase 2 bug, `PubSubWorkerImpl.java:106`). A test waiting for a score would pass or fail on a coin flip.
- GAME_START is also the first event sent, so the test finishes in seconds.

History entries have no event-type field. Because the user enabled only GAME_START, any `game-LAL-BOS` entry must be the game start, and the test also checks the message contains "has started."

**Model answer:** "Game start is always tagged with the home team, while score events are tagged with a random scorer, and current fan-out only matches the tagged team. Waiting on a score would make the test flaky. So it follows the home team for game start only: deterministic and fast. When Phase 2 fixes fan-out to match both teams, the test tightens to require a score too."

</details>

---

## 8. Why every make target names the k3d cluster

> The Makefile and scripts pass `--context k3d-sports-notifier` to every `kubectl` and `helm` call. `kubectl` already has a "current" cluster. Why be explicit?

<details>
<summary>Model answer</summary>

`kubectl` and `helm` act on whatever cluster is current in your kubeconfig, and the current cluster changes behind your back. Creating a cluster switches to it, and in Phase 5 `aws eks update-kubeconfig` switches you to EKS. Without the explicit context:
- `make up` would install the chart into whichever cluster is current, even though the image was only loaded into k3d, giving pods that can't find their image.
- `make smoke` would run `kubectl delete jobs` against EKS.

The final review caught this. The fix was tested by making a decoy cluster current: before the fix, commands went to the decoy; after, all of them reached k3d. The test even caught one hidden case, `kubectl set env --local`, which never contacts a cluster but still loaded the current one and asked for a password.

**Model answer:** "kubectl's current context is global state that other tools change, like the EKS CLI. A local dev command that silently runs against whatever is current could deploy to or delete from the wrong cluster. So every target pins the k3d context. I proved it with a decoy context: before the fix, commands hit the decoy; after, they all reached k3d."

</details>
