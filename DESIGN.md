# SportsNotifier — design and decisions

This is the map of the project: what it does today, what we are changing it into, and every decision we made on the way. It is written so you can explain the system without reading the code line by line.

Last updated 2026-10-14. Phases 1, 2, 3.1–3.4, 3b, 4.1, and 4.2 are merged (see "Build order"); 4.3, traces and metrics, is built (PR #55). "What exists today" describes the course version as it was before the redesign.

Revised 2026-09-28: hosting moved from one always-on Hetzner VM to Kubernetes, run on your laptop (k3d) and on AWS (EKS) only when you want it up. Kafka gets three brokers in the cloud. The evidence step became a correctness checker plus a chaos suite. Decisions 2, 6, 7, and 9 changed; 12 through 14 are new.

Revised 2026-09-29: decision 15, the notification catalog. Four sports, alerts as moments rather than every score, switches per follow, detected from the scoreboard.

Revised 2026-09-30: decision 15 tightened. League follows get a daily summary instead of a final per game. The final is always its own push. Close-game conditions are explicit and never apply in overtime. Game-follow defaults, precedence between follows, overlap rules, NFL ties, and duplicate-proof alert ids are defined.

Revised 2026-10-02: Phase 2 built (live-game check and merge pending); the fix list below says what it closed and what Phase 3 still owes. Decision 10 drops the Phase 2 provider comparison. ESPN only for now, plus a check that ESPN answers from a data-center address. The interface is named `ScoreFeed`.

Revised 2026-10-07: Phase 3 is split into 3.1–3.4 (build order). 3.1, the Kafka pipeline, is built. Decision 5's crash window closes with an outbox in Redis and one unsent play per game; the script heals an ESPN clock that runs ahead of reality; a per-game `version` counter orders accepted plays.


Revised 2026-10-07 (Phase 3.3 spec): decision 15's detectors run in Stage 1 with their memory in `det:<gameId>`; the poller sends every change of game position; a score's alert id counts scoring alerts fired, which never goes down; a push's id is its headline alert's id and the final is sent after the moment's push; "most specific follow wins" uses `sub:<kind>:<target>:ANY` sets.

Revised 2026-10-11 (Phase 4.2 spec): the chaos suite's eight scenarios and how a crash and a cut are made on k3d; two copies of Stage 1, Stage 2, and the gateway by default; the checker's rule for a push missing from a socket uses Stage 2's delivery time.

Revised 2026-10-13 (Phase 4.3 spec): one `grafana/otel-lgtm` pod and Strimzi's Kafka exporter; the trace id also rides in the poller's HTTP header and the outbox; the p50 and p99 come from a latency histogram recorded at the gateway, with exemplars linking a point to its trace, not from the traces themselves.

Revised 2026-10-09 (Phase 4.1 spec): Phase 4 is split into 4.1–4.3; the chaos suite is built and first run on k3d (4.2) and run again on EKS in Phase 6; alert waves 2 and 3 move to Phase 7. Decision 14 says how the checker computes what was expected; decision 8's marker records the set-shape version and Redis's run id.

---

## The full picture

This is a redesign of a semester project, not a new app. The goal is a system you can run, host, and explain in an interview: what you chose, what you gave up, and what happens when a piece dies. You do not need to have typed every line. You do need to walk the hot paths and defend the decisions in this document.

### Where it came from

**Fall 2025, distributed systems.** The project was a job system. Clients submitted work onto a stream. A pool of workers pulled jobs. One of them was the leader. Workers sent heartbeats. If the leader died, the remaining workers elected a new leader, a simplified ZooKeeper. If a worker died, the leader noticed the missed heartbeats and gave that worker's jobs to someone else. The point of the assignment was coordination: membership, failure detection, and agreement, written by you. Scale meant "start more workers."

**Spring 2026, advanced distributed systems (COM3810, Yeshiva).** This sports notification project. Same theme, different decision. Instead of electing a leader inside the app, the services coordinate through Redis: a stream, consumer groups, a Lua script, and an inverted index of who follows which team. Nine separate Java processes, no shared memory, no database. The course grade was 18.5/20. The professor's two deductions were a time-of-check/time-of-use race on the score (final score 43 instead of 50 under a 50-event burst) and some structure (a large gateway class, Redis key strings scattered around, missing field comments). Both are already fixed in the code on disk. The race fix is the Lua script, and the burst test is the number 43, then 48, then 50.

The course also required a specific folder layout (`com3810/`), staged design docs, and diagrams. Those were the assignment. They are not rules for the product. Where the professor or the design docs found a real engineering hole, it still counts. Where they only reflect the homework format, ignore them.

### What you are doing now

Taking that spring project and making it something you would actually run for other people, and something a hiring manager can take seriously.

The semester version is a real pipeline with one store doing every job. Redis is the database, the log, the cache, the session store, and the notification bus. It runs on one laptop via shell scripts. A restart of Redis deletes every account. A dead worker's event sits forever. Notifications only arrive if a browser tab is open. Nothing is in git, nothing is in Docker, nothing has a public URL.

The redesign keeps the part that was the point of the course and replaces the part that only worked because it was a demo:

| Keep | Change |
|---|---|
| Separate stateless Java services | Package them as one image and run them on Kubernetes: k3d on your laptop, EKS on AWS when you want it up |
| The Lua script as the authority on the live score | Run it before producing, and produce to Kafka instead of a Redis stream |
| Fan-out from an inverted subscriber index | Index stays in Redis, but it is a cache rebuilt from Postgres |
| More workers when there is more work | Kafka lanes, so a dead worker's games move without a leader you wrote, and workers added when a lane backs up |
| "It works" as a demo | A checker that proves every subscriber got every play exactly once, run while things are being killed |
| ESPN as the data source for now | Hide it behind an interface, poll faster when games are live, and don't pretend it is a licensed feed |

You are not throwing away the semester and starting over. You are also not leaving it as homework with a README that says "millions of users." The resume story is smaller and true: you found a race, proved it, fixed it atomically, then took the same pipeline and gave the log, the user data, and the failure handling each a real tool, and proved it with a checker while killing workers, brokers, and gateways. The fall class is why you can say you have built leader election and chose not to build it again.

### What "done" looks like

A person can open a URL, create an account, follow a team, and get a notification when a play they care about happens. The account survives a Redis restart and a full teardown of the cloud environment. Kill a worker, a Kafka broker, or a gateway during a replayed game, and the checker still reports every subscriber got every play exactly once, in order. The README leads with the race, the checker results, and the timings you measured, not with a scale claim you did not measure. You can draw the path from ESPN to the browser and explain every box.

Hosting is on demand. The same Helm chart runs on your laptop in k3d and on AWS in EKS. `terraform apply` brings the cloud up for a demo or a load test, `terraform destroy` takes it down, and you pay only for the hours it was up. In the cloud the machines are actually separate, spread across three AWS data centers, so a node dying is a real failure you can show, not a simulated one.

---

## What the system is

A real-time sports notification platform. A person creates an account, follows teams, leagues, or specific games, and chooses which kinds of events they care about. When one of those events happens, they get a notification.

The product goal is the thing the original design doc promised: updates arrive as they happen, so the person does not have to keep the app open and refresh. The course version only does that while a browser tab is open. Real push to a closed phone is a later phase.

Four sports at launch: NFL, NBA, MLB, NHL. Each has its own list of alerts, because the games do not produce events at the same rate. Alerts are moments, not every score: the NFL alerts on every touchdown because scores are rare, and the NBA does not alert on every basket because there are hundreds. The full catalog is decision 15.

Those choices are user preferences. They do not decide which Kafka lane a game uses.

---

## What exists today

Nine Java processes. They do not call each other's code. They talk over HTTP and through Redis. Redis holds every piece of state: accounts, password hashes, preferences, sessions, live scores, the subscriber index, the event log, notification history, and the pub/sub hop to the browser. There is no Postgres and no Kafka. The repo is not a git repository. There is no Docker setup.

A score flows like this:

1. **ESPN poller.** Every 30 seconds it asks ESPN's public scoreboard for NBA, NFL, MLB, and NHL. It remembers the last score it saw in a Java `HashMap` on that one process. If the score changed, it builds an event and POSTs it to Event Processing.
2. **Event Processing.** A Lua script inside Redis checks that the event is newer than the last one saved for that game, claims the event id so a duplicate is ignored, and writes the new score. After the script returns, the event is appended to a Redis Stream called `game-events`.
3. **Two workers** in one Redis consumer group. Redis gives each stream entry to one of them. The worker looks up who cares in Redis sets (`sub:team:LAL`, `sub:game:<id>`, `sub:league:NBA`) and then checks that the user asked for that event type. It acknowledges the entry when it is done.
4. **Notification service.** Pushes the notification onto a per-user history list and publishes it on `notify:<userId>`.
5. **Gateway.** Holds the open WebSockets. It subscribes to `notify:*` and forwards the payload to that user's browser tabs.

Signup and login go through the gateway to the user-preference service. Passwords are bcrypt hashes. The session is a random id in Redis with a 24-hour TTL, sent as a cookie.

The useful data source is ESPN's own website JSON, not a paid API. No key, no charge. It is unofficial: ESPN can change it or block clients, and the terms do not grant permission to poll it. `curl` from this machine already gets a 403; a browser and the Java client currently get through. Verified live: MLB play-by-play (every pitch, scoring plays flagged) and NFL drives (rush, pass, touchdown, field goal, interception, and so on). NBA uses the same URL shape when games are on.

---

## What is already strong

These stay. The work after this is packaging, correctness, and the new stores. It is not a rewrite.

**The Lua script.** Redis runs one command at a time, so a single command cannot be interrupted. Three commands in a row can: another client can land between them. A Lua script is sent to Redis as one unit, and Redis finishes every line before it runs anything else.

The script, for one event: read the last saved timestamp for the game, reject the event if it is older, claim the event id with `SET NX` (only if absent), write the score, write the new timestamp. The Cubs are 2–2 at time 1000. Event A is time 2000, score 3–2. Event B is time 3000, score 4–2. Without the script both can read 1000, both can decide they are newer, and A's write can land last. The game shows 3–2 even though 4–2 happened later. That is the bug measured under a 50-event burst: final score 43, then 48 after a partial fix, then 50 once check, claim, and write were one script. Whichever script Redis starts runs to the end before the other script starts. The timestamps decide who wins. The script makes sure the loser cannot overwrite the winner.

**Consumer groups instead of a leader you wrote.** The fall 2025 project had a leader, heartbeats, and an election when the leader died. This project asks Redis to assign stream entries. Adding a worker is starting another process. The tradeoff, already taken in the course version and kept here, is that you do not survive the loss of the coordinator by electing a new one inside the app.

**The subscriber index.** When a user saves preferences, the service writes them into Redis sets keyed by team, game, league, and event type. Fan-out reads those sets instead of scanning every user. The sets are paged with `SSCAN` so a popular team does not load a million ids into the worker at once.

**Stateless services.** Sessions, scores, and subscriptions are not kept only in a process's memory. Kill the gateway, the user reconnects, the session is still in Redis. The exception is the poller's `HashMap`, which we are removing.

**Idempotency.** Each event has an id. Processing it twice is a no-op. Retries are safe because of that. One catch in the code on disk: the ESPN poller makes a random UUID for the id and uses its own clock for the timestamp, so for real ESPN data the dedup never fires. That is on the fix list below.

**History separate from the live push.** The history list survives. The pub/sub message only reaches whoever is listening right now. A user who was offline sees history when they open the app.

---

## Target architecture

Three infrastructure processes, each with one job:

| Piece | Job |
|---|---|
| Postgres | Accounts, password hashes, preferences, notification history. Source of truth. |
| Kafka (one broker locally, three in the cloud) | The event log. Accepted plays in, deliveries out. |
| Redis | Sessions, live score, subscriber-set cache, pub/sub to gateways. Rebuildable from Postgres, except sessions, which are disposable. |

The Java services stay separate processes. One Docker image, an env var (`SERVICE`) picks which `main` runs. One Helm chart describes every service and how many copies of each to run. It is installed on k3d on your laptop and on EKS on AWS, with a different settings file for each.

```
ESPN
  → poller                         (adapter, stateless, snapshots in Redis)
  → Event Processing               (Lua script, then produce)
  → Kafka topic game-events        (key = gameId)
  → Stage 1 workers                (who is subscribed?)
  → Kafka topic notifications      (key = userId)
  → Stage 2 workers                (deliver)
       → Redis pub/sub on the channel of the gateway holding that user's socket
            → that gateway → WebSocket
  → history writers                (a second consumer group on notifications)
       → Postgres history              (batched inserts)
```

Postgres is written by the user service and by the history writers. The user service rebuilds `sub:*` from Postgres whenever its "built" marker is missing (decision 8).

### Where it runs

| | Laptop | Cloud |
|---|---|---|
| Cluster | k3d (Kubernetes inside Docker) | EKS, nodes in three AWS availability zones |
| Created by | `k3d cluster create` | `terraform apply` |
| Kafka | Strimzi, one broker | Strimzi, three brokers, one per zone |
| Postgres, Redis | One pod each | One pod each |
| Java services | One copy each | Several copies; workers scaled by KEDA on Kafka lag |
| Cost | Nothing | Hourly, only while it is up |
| Torn down by | `k3d cluster delete` | `terraform destroy`, after a Postgres dump to S3 |

The tools, one line each:

- **Kubernetes.** Runs containers across machines and keeps them running. You declare "three Stage 2 workers" and it makes that true, restarting or moving them when something dies.
- **Helm chart.** The Kubernetes config for the whole system as one template, with a settings file per environment.
- **k3d.** A small Kubernetes inside Docker, so the laptop runs the same chart as the cloud.
- **EKS.** AWS's hosted Kubernetes. AWS runs the control plane; you rent the machines.
- **Terraform.** The AWS setup (network, EKS, S3 bucket) written as code. `apply` creates it, `destroy` deletes it.
- **Strimzi.** A Kubernetes add-on that runs Kafka: storage, broker identities, rolling restarts.
- **KEDA.** A Kubernetes add-on that scales a service on an outside signal. Here, Kafka consumer lag.
- **OpenTelemetry.** Records timings and passes a trace id from service to service, so one play is one timeline from poller to browser.

The deploy path: Maven builds the JARs, Docker builds one image, GitHub Actions pushes it to ECR (AWS's image registry), and the Helm chart points Kubernetes at that image. Locally the image is loaded straight into k3d.

---

## How one accepted play moves

Packers–Falcons, game id `401872948`. A touchdown is newer than the saved timestamp.

1. The poller emits one event with a fixed event id, the game id, the type, the score, and the play text.
2. The Lua script accepts it and the live score moves forward.
3. Event Processing produces to `game-events`. The message key is the game id. Kafka hashes the key: `hash("401872948") % 12`. That lane is the only lane this game's events will ever use. Every later play from this game hashes to the same lane.
4. The Stage 1 worker that owns the lane reads it, in order with the other plays from that game. It asks Redis who follows the Packers, the Falcons, this game, and the NFL, then who asked for touchdowns. For each matching user it writes one message to `notifications`, keyed by that user's id.
5. Those messages spread across 48 lanes. Stage 2 workers deliver in parallel: look up which gateway holds that user's socket and publish on that gateway's Redis channel so an open tab updates. A user's messages stay in one lane, so that user still sees them in order.
6. History writers read the same 48 lanes as a separate consumer group and insert the history rows in Postgres in batches. A slow or stopped Postgres delays history, not the push.

A normal game's history is the full sequence. The poller sends 2–2, then 3–2, then 4–2. Each is newer than the last, so each is accepted. The timeline matches the game. A play is dropped only when an older event arrives after a newer one was already saved. The live score must not go backwards. That late play can be logged. It is not delivered as if it were the new score.

---

## Decisions

Each one is in the form you will use out loud: what we chose, what we gave up, why.

### 1. Kafka, not Redis Streams, for the event log

**Chose.** Apache Kafka as the log. Broker count is decision 2.

**Gave up.** Redis Streams, which is what the course code uses. Streams are simpler, already written, lighter on memory, and fast. At this volume (a few thousand events a week) they would not fall over. Replay exists on Streams too, within whatever you have not trimmed. `MAXLEN 1000` silently dropping old events is a config mistake, not a reason Streams cannot retain data.

**Why.** Ordered, parallel, fault-tolerant consumption needs lanes that each have one owner, with ownership moving when a worker dies. Kafka does that. Streams can be hand-sharded (`hash(gameId) % 12` separate stream keys), and then you still have to decide which worker owns which shard and what happens when that worker dies. That is the leader-election project again, inside the notification app. We also stop sharing one Redis process between the cache and the log, so a backlog in the log is not competing with session lookups.

The interview line: "I used Redis Streams first. Throughput was fine. What I could not get was key-based lanes with automatic reassignment, and building that myself would have meant writing a coordinator in the application."

### 2. Apache Kafka, not Redpanda

**Chose.** Apache Kafka in KRaft mode, run on Kubernetes by Strimzi. One broker on the laptop. Three brokers in the cloud, one per availability zone, every partition copied to all three (replication factor 3), and a write counts only once two copies have it (`min.insync.replicas=2`, producer `acks=all`).

**Gave up.** Redpanda, which speaks the Kafka protocol and uses less memory. The Java client would be the same. Also gave up Amazon MSK: it is the managed version of the same broker, but its minimum cost is higher than the rest of the project, and running Kafka yourself is the part worth being able to explain.

**Why.** Cost was set aside for this choice. Kafka is the broker every tutorial, failure write-up, and managed service is about. You learn the real broker. The resume line has no asterisk. Redpanda remains the answer to "what would you run on a tiny box," and it would be an image swap, not a code change.

Three brokers in the cloud is the change from the first draft. On one VM, three brokers would have been fake: the VM dies and all three die with it. On EKS the brokers sit on machines in separate data centers, so killing one is a real failure and Kafka's own leader election is something you can watch happen.

### 3. Two stages, not one

**Chose.** Two topics and two consumer groups.

- `game-events`, key = `gameId`. Stage 1 only decides who to notify and writes one message per user.
- `notifications`, key = `userId`. Stage 2 delivers. History writers, a second consumer group on the same topic, save it to Postgres (since Phase 3.2), so a Postgres outage delays history but never the push.

**Gave up.** A single worker that reads "Packers scored" and notifies everyone itself. That is simpler, has one less hop, and is enough for a few hundred users. We also accept write amplification: one event with two million subscribers becomes two million messages on `notifications`. A crash between stages can emit duplicates; the per-user dedup key absorbs them.

**Why.** Ordering says one Stage 1 worker owns a game. If that worker also delivers, one game's audience is a ceiling no matter how many workers you add. Fan-out is I/O (Redis, Postgres, later Web Push). Event intake is not. Splitting them lets delivery scale with workers, and each delivery channel can later be its own consumer group on `notifications` without blocking the others.

The hop is a few milliseconds. The poll interval is seconds. The hop is not why a notification feels late.

Per-user messages, not batches. Batching ("these 100 users") cuts Kafka writes and loses per-user ordering and per-user retry. Batching is the escape hatch if write volume ever shows up in the load test. It is not the first design.

### 4. Hash the game id. Do not pick the quietest lane

**Chose.** `lane = hash(gameId) % 12` on `game-events`, and `hash(userId) % 48` on `notifications`. Twelve and 48 because partitions are cheap and you cannot usefully run more consumers than you have partitions. Extra consumers sit idle.

**Gave up.** A balanced assignment. Some lanes will have two or three games and some will have zero. We also gave up "put this event on whichever lane is least busy."

**Why.** The same game id always hashes to the same lane, on every producer, after every restart, with no lookup. Least-busy-per-event splits one game across lanes and the later score can finish first. Least-busy-once-then-sticky preserves order only if every producer remembers the assignment. That memory is a coordinator on the write path, two pollers can race to claim different lanes, and the balance is stale as soon as the next game starts. Moving a live game to a quieter lane splits the game.

A hot lane is watched, not repaired by moving the game. Kafka reports lag per lane. If one lane is actually behind, the fix is inside Stage 1 (page the subscriber walk, or emit smaller chunks), not a new lane for a game already in progress. The Super Bowl is one lane either way, because one game must stay in one lane. Its audience is Stage 2's problem.

Event type is not the key. A touchdown and a field goal from the same game share a lane. The type is a field used later, when filtering who asked to hear about it.

### 5. Lua script before the produce

**Chose.** The script accepts or rejects the event. Only an accepted event is produced to `game-events`.

**Gave up.** A Kafka copy of events we refused (duplicates and late plays that would move the score backwards). We also accept a crash window: the script can say yes, and the process can die before Kafka acknowledges the write. A naive retry then looks like a duplicate and the play never arrives. The produce has to be retried without the script treating that retry as a new claim. Rejected events get an application log line, not a topic.

**How the window closes: an outbox** (built in Phase 3.1). The claim has two states, and they live in Redis next to the score. In the same atomic script that accepts a play, the play is written to the game's outbox slot (`outbox:<gameId>`): that is "claimed". When Kafka confirms the write, Event Processing clears the slot: that is "produced". A retry loop on every Event Processing pod re-sends whatever is still in the outbox after 5 seconds, which covers a pod that crashed after the script said yes, and a send that failed while Kafka was down. Nothing else would retry it: the poller has no memory and compares ESPN with the saved record, which already shows the new score. This is the transactional outbox pattern.

**One unsent play per game.** While a game's play is in the outbox, the script answers "pending" to any newer play for that game and changes nothing; the poller sends it again on its next poll. So a game's plays reach Kafka in the order they were accepted, even when a crashed pod's play is re-sent late. Without the rule, a stuck play (seq 10) re-sent after a newer one (seq 11) would be dropped by Stage 1 as old. A per-game lock in Redis was rejected: a pod that stalls past the lock's timeout, in a long garbage-collection pause, can still send an older play after a newer one. The script can't be interleaved, so the rule holds without a timeout. The worst case is a few copies of the same play on the topic (the original send plus one per pod's retry loop, which skips a play while its own send is in flight), all with the same `version`, which Stage 1 drops.

**When ESPN's clock runs ahead of reality.** In 2 of 23 recorded NHL games, ESPN briefly showed a clock ahead of the real one (4:01 left, then 11:45). That snapshot's `seq` became the bar, and every real snapshot after it was "stale" for minutes. The script now also accepts an older-looking play in the same period if it has more points (an older snapshot never does), or if the same period and score have kept arriving for 10 seconds (30 until Phase 3.4: an ESPN glitch that showed 0:00 mid-period for 90 seconds then froze the score card for another 30); either lowers the bar to the real clock. Only for real game positions (`seq` below 10^14), not the simulator's run-ordered values. Because `seq` can now go down, each game also has a `version`, a counter the script raises on every accepted play in the same step as the score. Everything after the script orders and dedups by `version`; the API's `version` is the same counter. A missing counter starts from the current time in milliseconds, not 0 (Phase 3.2): Postgres history and an open UI outlive Redis, so versions must keep rising across a Redis reset.

**Why not the other way.** Produce-then-script makes Kafka a complete inbox of everything the poller sent, and a crash cannot eat the raw event. Every reader of that topic then has to apply the stale check. The check is cheap (well under a millisecond). Forgetting it in one new consumer is a bug: a history writer or a metrics job would treat a rejected score as real. A second "accepted only" topic fixes that and puts the gate one hop later, with extra lag that looks like real backlog when the inbox is full of duplicates.

The scoring timeline stays correct on the normal path. The poller sends plays in order. Each is newer than the last, so each is accepted. The jump from 2–2 to 4–2 happens only when the older play arrives after the newer one was already saved. The script is protecting the live score in that case.

### 6. Buy coordination. Do not write leader election

**Chose.** Kafka's consumer group assigns lanes. Workers heartbeat to the broker. Miss the heartbeat and the broker gives those lanes to someone else. In the cloud, Kafka also elects a new leader for each lane when a broker dies. Redis's single-threaded script is the authority for the score. There is no leader among the Java workers.

**Gave up.** The fall project's property: any single machine can die and the remaining workers elect a replacement. Kafka gets that property in the cloud, from its own election, not from ours. Redis and Postgres do not. If the Redis pod or the Postgres pod dies, score updates or account writes stop until Kubernetes restarts it. Making them survive that means Redis Sentinel and a Postgres replica. We are not doing that.

**What "restart" means.** The process is dead. Kubernetes starts it again, on another machine if the first one is gone. Workers keep running and their calls fail and retry. Plays Kafka had already acknowledged are still on disk. Plays in flight are retried. Users get nothing new until the process accepts connections again. That gap is usually seconds. One catch: a Redis or Postgres disk on AWS lives in one availability zone, so that pod can only restart in the same zone.

**What "more than one" means here.** Three Kafka brokers, each lane with a copy on every broker. If the leader broker for a lane dies, the remaining copies elect a new leader, and a write acknowledged by two copies is not lost. That is the one piece of replicated, self-electing infrastructure in the system, and the chaos suite kills a broker to show it. Redis Sentinel (a primary, a replica, and sentinels that promote the replica) and Postgres replicas are the next adds if this ever needed to stay up. They are infrastructure, not Java election code.

A dead worker is the failure this design absorbs by itself. Kill Stage 1 mid-game. The lane waits out the session timeout. Another worker reads from the last committed offset. The per-user dedup key (`notif:<eventId>:<userId>`, set-if-absent) stops a double notification.

### 7. Kubernetes, not Compose on one VM

**Chose.** Kubernetes everywhere. k3d on the laptop, EKS on AWS. One Helm chart for both. In the cloud, nodes are spread across three availability zones.

**Gave up.** The simplicity of one Compose file on one VM, which was the first draft. Kubernetes is more to learn: pods, deployments, services, persistent volumes, a Helm chart instead of one YAML file. Also gave up ECS, AWS's simpler container service. ECS would mean Compose on the laptop and ECS task definitions in the cloud, two descriptions of the same system that drift apart.

**Why.** Three reasons. The laptop and the cloud run the same chart, so "works locally" means something. Machines in the cloud are actually separate, so killing a node or a broker tests real failure domains instead of processes that share one VM's fate. And KEDA can scale workers on Kafka lag, which is the right signal (decision 12). Kubernetes is also what most teams run, so the resume line is one people recognize.

**What it still is not.** Redis and Postgres are single pods. Losing the zone that holds their disk stops them. We say that out loud.

### 8. Postgres for durable user data, Redis for speed

**Chose.** Postgres holds accounts, password hashes, preferences, and notification history. Redis holds sessions, the live score, the `sub:*` cache, and pub/sub to the gateways. Startup rebuilds `sub:*` from Postgres.

**Gave up.** One store. We now run Postgres, and a preference write can land in Postgres before Redis has the new set membership. That window is short and rebuildable.

**How a save stays in step (Phase 3.2).** A follow save commits to Postgres first, then updates the Redis sets. If the Redis update fails, the user service deletes the `sub:*` "built" marker, and its 30-second check rebuilds every set from Postgres. Undoing the Postgres write instead would not cover a Redis update that succeeds followed by a commit that fails. A rebuild holds a Postgres lock that saves wait on, so it cannot re-add someone who unfollowed while it ran. A full rebuild also runs daily, which bounds how long a missed update can last. The marker says what was built (Phase 4.1, issues #16 and #18): it holds the set-shape version and Redis's `run_id`, so a `helm upgrade` that changes set shapes, or a Redis restart that kept the marker but lost recent writes, also triggers a rebuild.

**Why this direction.** Redis can be rebuilt from Postgres. Postgres cannot be rebuilt from Redis. A cache is not the only copy of a person's account.

The live score in Redis is rebuilt from the accepted log if we need it (replay `game-events`). It is not a Postgres table.

### 9. AWS on demand, not always on

**Chose.** The laptop is where you work, in k3d. AWS is where you demo, load-test, and let friends use it, and it is up only when you want it. Terraform describes the AWS side: the network, the EKS cluster, an S3 bucket, and an ECR registry. `terraform apply` creates it, `terraform destroy` deletes it. An AWS load balancer with a TLS certificate is the public entry point, and only the gateway sits behind it.

What survives a teardown:

- **Postgres** is dumped to S3 before destroy and restored after apply. Accounts, preferences, and history survive.
- **Redis** is rebuilt from Postgres on startup, which decision 8 already requires. Sessions are lost; people log in again.
- **Kafka** is deleted. It only holds plays in flight.
- The S3 bucket and the Terraform state stay. They cost cents a month.

**Gave up.** A URL that is always up. Friends can use it while it is running, not at 3 a.m. Also gave up the first draft's cheap always-on VM on Hetzner. Cloud cost is hourly (the EKS control plane is about $0.10 an hour, plus the machines), so a demo afternoon is a few dollars and an idle month is almost nothing.

Watch for: a NAT gateway costs money every hour it exists and is easy to create by accident. Nodes in public subnets with locked-down security groups avoid it. Anything a load test adds has to go away on destroy.

**Why.** The resume claim is "runs on AWS, comes up from code, survives a teardown," not "has been up for 200 days." Starting from zero with one command is a stronger demo than a VM nobody rebuilt. It also forces the data story to be real: if Redis could not be rebuilt from Postgres, a teardown would show it.

The app cannot move to Cloudflare Workers, Lambda, or Cloud Run. Those run short request handlers, not a JVM consumer that holds a Kafka partition or a poller that runs forever.

### 10. ESPN behind an interface, no paid feed

**Chose.** A `ScoreFeed` interface (named `SportsDataProvider` in earlier drafts; renamed so it is not mistaken for the paid SportsDataIO). The ESPN poller is the current adapter. Poll about every 5 seconds while games are live and about every 60 seconds when nothing is. The ESPN host and User-Agent are settings, used by the poller and the gateway alike. The defaults are `site.web.api.espn.com` and an honest app User-Agent, not a browser imitation: as of 2026-10-01, ESPN refused browser-imitating agents and refused `site.api.espn.com` from a cloud address while `site.web.api` answered. ESPN's filtering changes over time, so a daily GitHub Actions check tries each host and User-Agent and fails when the default stops working. Back off and alert on 403s. Snapshots of the last score live in Redis, not in a `HashMap`, so a restart does not re-send "game start" for every game in progress. Play-by-play (`summary?event=<id>`) is polled only for games someone is actually following.

**Gave up.** A licensed push feed (Sportradar, Genius, Stats Perform / Opta). Those are what the real apps pay for. Official feeds are push, not poll, and they are enterprise contracts. Cheap developer APIs are still polling and are not meaningfully faster than ESPN. Even the paid play-by-play feeds trail the television broadcast by roughly 15–20 seconds.

**Why.** Almost all of the delay a user feels is the poll interval plus ESPN, not Kafka. The in-system path is tens of milliseconds. We will measure it (timestamp when the poller saw the play, record the delay at the gateway). The interface is so a push provider is one class later, and so the unofficial API is an adapter you can explain instead of a hidden dependency.

**ESPN only, for now.** ESPN stays the source for all four sports, because it covers them in one shape and carries team logos and colors. The provider comparison planned for Phase 2 (MySportsFeeds and the leagues' own MLB and NHL feeds) was dropped on 2026-10-02; a different feed is a later version's decision, and it would be one new `ScoreFeed` class plus the `seq` computation for that feed. Phase 2 instead checks one thing that matters for Phase 5: whether ESPN answers requests from a data-center address, using a GitHub Actions runner. Phase 5 repeats the request from AWS first. The API the UI talks to does not change with the source.

### 11. Hybrid fan-out, later, only if measurement says so

**Not in the first build.** After two-stage delivery is load-tested, we may add this:

- Stage 1 counts the audience with `SCARD` on the subscriber sets (an overestimate, which is the safe direction).
- Under about 1,000 users, Stage 1 delivers directly and skips the second topic.
- At or above that, it uses `notifications` as normal.
- Once a game takes the Stage 2 path, it stays there for the rest of the game (`game:<id>:fanout` in Redis, set before the first Stage 2 produce). Fast then slow preserves order. Slow then fast can reorder.
- Both paths call the same delivery code and the same per-user dedup key.

**Why it waits.** If the extra hop is a few milliseconds on top of a multi-second data delay, the hybrid is a second code path nobody can feel. "Designed, measured, skipped" is a better result than building it early. The direct path also pulls I/O back onto the game's only Stage 1 worker, which is the ceiling two stages exist to remove. Web Push on the direct path makes that worse. If we build it, the direct path should do the fast channel only, and history plus push should stay on the topic.

### 12. Scale workers on Kafka lag, not CPU

**Chose.** KEDA scales Stage 1 and Stage 2 workers on consumer-group lag: how many messages are waiting that no worker has read yet. The ceiling is the partition count, 12 for Stage 1 and 48 for Stage 2, because a consumer with no partition sits idle. The floor is one of each. Gateways scale on open connections.

**Gave up.** CPU-based scaling, which is the Kubernetes default. Fan-out spends its time waiting on Redis and Postgres, so CPU stays low while the backlog grows. Also gave up adding partitions later: changing the count changes which lane a game hashes to, which splits a live game. The counts are fixed up front.

**The costs of scaling, handled on purpose:**

- **Rebalances.** Every worker added or removed makes Kafka reassign lanes. Use the cooperative-sticky assignor so only the moved lanes pause, and a scale-in cooldown so a spike does not cause flapping.
- **The bottleneck moves.** Twenty history writers doing one-row inserts will swamp Postgres. History inserts are batched, and each writer's connection pool is sized so that max writers × pool size stays under Postgres's connection limit.
- **Gateways drain.** A gateway being removed tells its clients to reconnect, the load balancer stops sending it new connections first, and clients reconnect with random jitter so they do not all arrive at once.

**Why it is not the headline.** Friends-scale traffic never triggers it. The load test does. Autoscaling is about twenty lines of config on Kubernetes. The interesting part is the three bullets above.

### 13. Send each notification to the gateway that holds the socket

**Chose.** When a user's WebSocket connects, the gateway records `conn:<userId> → <gatewayId>` in Redis with a TTL it refreshes while the socket is open. Stage 2 looks that up and publishes on `gw:<gatewayId>`. Each gateway subscribes only to its own channel. No entry means the user is offline; history in Postgres still gets the row.

**Gave up.** The course version's broadcast: every gateway subscribes to `notify:*` and throws away messages for users it does not hold. That is simpler and has no lookup, and it is correct.

**Live game updates are a second path (Phase 3.4).** When the accept script accepts a play, it also publishes it on `live:<gameId>`, in the same step as the score, so updates leave in version order and only for saved state. A gateway subscribes to `live:<gameId>` while at least one of its tabs shows that game, and sends a tab the game's current state when it subscribes. Updates are per game, not per user: a game with 100,000 viewers is one message per gateway.

**Why.** With broadcast, adding a gateway does not reduce any gateway's work. Every gateway still reads every notification. With routing, each gateway reads only its own users' messages, so gateways scale out. The TTL handles a gateway that dies without cleaning up: its entries expire, and a reconnect to another gateway overwrites them. A user with tabs on two gateways needs a set of gateway ids, not one.

### 14. Prove it with a checker, not a demo

**Chose.** Three tools, built before the cloud deploy:

- **Recorded-game replay.** Record a real game's ESPN responses once and replay them at 1× or 10×. Demos and tests no longer need a live game, and a run is repeatable.
- **The correctness checker.** Synthetic users follow teams. After a replay, the checker compares what each user received with what the accepted plays say they should have received. The rule: every subscribed user got every accepted play they asked for, exactly once, in game order.
- **The chaos suite.** Scripts that kill a Stage 1 worker, a Stage 2 worker, a Kafka broker, and a gateway during a replay, and cut the network between a worker and Kafka. The checker runs after each one. Phase 4.2 has eight: crashes of Stage 1, Stage 2, History, a gateway, and the broker; a gateway rolling restart; and Stage 1 or Stage 2 cut from Kafka.

**How the checker knows what was expected (Phase 4.1).** It does not trust Stage 1's output. It reads the run's accepted plays from `game-events` and the synthetic users' follows from Postgres, runs the detectors, and computes each alert's audience itself in plain Java, so a lost or repeated moment in Stage 1, or a Redis set that disagrees with Postgres, shows up. The follows are created before the replay starts and frozen; a follow that changes during the run fails the check. Every replayed game id carries the run's id (`<league>-rp<runId>-<espnId>`), and every check is scoped to those ids, so live games and other runs cannot pass or fail it. The run ends only when the replay is done, every game is final, and the consumer groups have stayed at zero lag for 10 seconds. A self-test injects a lost Redis write, a duplicate push, and a follow change, and the checker must catch each. A push missing from one socket is excused only when history has it and the socket was down within 2 seconds of when Stage 2 delivered it; Stage 2's delivery script records that time from Redis's clock (Phase 4.2).

Tracing runs alongside it (Phase 4.3). OpenTelemetry carries a trace id from the poller's HTTP request through the outbox, each Kafka message's headers, and the Redis pub/sub body, so one touchdown is one timeline from poller to gateway, with the outbox wait and any redelivery visible in it. The p50 and p99 in the README come from a latency histogram (`pipeline.latency`, time since the poller saw the play) recorded at each stage and, for the headline, at the gateway's socket write; each sample keeps the trace id as an exemplar, so a point in a spike opens that play's trace. Kafka lag comes from Strimzi's Kafka exporter. One `grafana/otel-lgtm` pod (collector, Prometheus, Tempo, Grafana) stores and shows both, and the chaos suite marks each fault on the dashboard.

**Gave up.** Time that could have gone to features. A chaos run finding a duplicate is a week of debugging nobody sees in a screenshot.

**Why.** "Exactly once under injected failure, verified by a checker" is the claim distributed-systems interviewers probe, and almost no side project can back it up. It is a small version of what Jepsen does to databases. It also finds the bugs the design missed, and those bugs are the best interview stories.

Optional, if the checker finds duplicates from Stage 1 crashes: Kafka transactions, which commit Stage 1's read of a play and its writes to `notifications` as one unit. The per-user dedup key stays either way, because Redis and Postgres writes are outside Kafka's transaction.

### 15. Alerts are moments, chosen per follow, detected from the scoreboard

Decided 2026-09-29, revised 2026-09-30. This replaces the course version's three generic types (`GAME_START`, `SCORE_UPDATE`, `GAME_END`).

**Chose.** Each sport has its own catalog of alert types. Every follow (a league, a team, or one game) has its own switches, drawn from that sport's catalog. A new follow starts from defaults, so nobody has to set fifteen switches to follow a team. Some switches carry options, not just on and off, such as which innings get an end-of-inning alert.

#### Rules across every sport

- **Your team's game, not just your team.** Following the Bears means alerts for everything in Bears games, including the other team's scores. A Raiders touchdown in Bears–Raiders reaches a Bears follower. Game follows work the same way. Following both teams gives one alert, not two.
- **League follows get significant live moments and one daily summary.** They start with close game late, comeback, overtime, and the sport's big-moment alerts. Instead of a final for every game, one end-of-day summary lists that day's final scores. A final per game is an option, off by default. Following the whole NBA with every alert on would be ten games of quarter ends a night.
- **Every in-game alert carries the score, the period, and the clock.** "Halftime: Bears 14, Raiders 10," not "Halftime."
- **The most specific follow wins.** For one game, a user's switches come from their follow of that game if they have one, otherwise from their follow of either team in it, otherwise from their league follow. Turning an alert off on a game you picked silences it even if your team follow has it on. Following both teams in a game combines those two teams' switches.
- **One push per moment per user.** However many follows lead a user to the same alert, they get it once (see "Overlapping alerts").
- **Comeback is two steps under one switch.** "Comeback on" fires when a team that was far behind gets close in the second half: the moment to turn the game on. "Comeback complete" fires when it takes the lead, only if step one fired. Hockey completes on the tying goal instead, because a tied hockey game is usually decided in overtime or a shootout.
- **Close game late is a regulation alert in every sport.** It never fires in overtime or extra innings. Overtime has its own alert, and an overtime game is close by definition.
- **Finals are win, loss, or tie.** A tie only happens in the NFL regular season: "Final (OT): Bears 20, Raiders 20. Tie."
- **v1 uses the scoreboard only.** Every alert below comes from the scoreboard the poller already reads for every game, or from the schedule. Rows marked **?** depend on a field that has to be confirmed in ESPN's live JSON before the alert is promised.

#### The catalog

**Team**, **League**, and **Game** say whether the alert starts on for a new follow of that kind. A dash means the alert does not apply to that kind of follow.

**Every sport** (wording varies: kickoff, tip-off, first pitch, puck drop):

| Alert | Team | League | Game | Rule |
|---|---|---|---|---|
| Starting soon | off | off | off | 15 minutes before the scheduled start. Comes from the schedule, not a snapshot |
| Game start | on | off | on | Status turns in progress |
| Delayed / postponed | on | off | on | Status changes to delayed, postponed, or canceled. MLB also alerts when play resumes |
| Lead change | off | off | off | The leading team changes. NBA: Q4 and overtime only. Other sports: any time |
| Close game late | on | on | on | Conditions in the close-game table. Once per game, never in overtime |
| Comeback | on | on | on | Two steps. Thresholds below |
| Overtime / extra innings | on | on | on | Fires when overtime starts. Each further overtime period fires again ("Double overtime"). MLB fires once, when extra innings start |
| Final | on | off | on | Final score and win, loss, or tie for your team. An option for league follows |
| Daily league summary | — | on | — | After the last game of the user's local calendar day ends, one push with every final score from that day. Postponed games are left out; suspended games are noted |

**Close game late**, written as a score-gap condition and a late-game condition. The gap is the absolute difference in score, so a tie is a gap of 0. The alert fires on the first snapshot where both conditions hold, and not again that game.

| Sport | Score gap | Late-game condition | In overtime |
|---|---|---|---|
| NBA | 5 or fewer | 4th quarter, 5:00 or less remaining | Never |
| NFL | 8 or fewer | 4th quarter, 5:00 or less remaining | Never |
| NHL | 1 or fewer | 3rd period, 5:00 or less remaining | Never |
| MLB | 2 or fewer | 8th or 9th inning, either half | Never (extra innings have their own alert) |

**NFL.**

| Alert | Team | League | Game | Rule |
|---|---|---|---|---|
| Touchdown | on | off | on | Score jumps by 6. Sent at once with "extra point pending" |
| Extra point / 2-pt result | on | off | on | Follow-up to a touchdown, to the same people: extra point good, 2-point conversion good, or try no good |
| Field goal | on | off | on | Score jumps by 3 |
| Safety | on | off | on | Score jumps by 2 outside a pending try |
| End of quarter (Q1, Q3) | on | off | on | |
| Halftime | on | off | on | |
| Two-minute warning | off | off | off | Clock crosses 2:00 in Q2 or Q4 |
| Red zone | off | off | off | ? Team with the ball inside the 20 |
| Chance to tie or win | on | on | on | ? Trailing by 8 or less, has the ball, last 2:00 of Q4 |

Comeback: down 14 or more, then within 8 in the second half.

The touchdown rules need memory. After a touchdown the game is in "try pending." A +1 or +2 by the scoring team resolves it as good, and a +2 is read as a 2-point conversion, not a safety. If the clock starts moving again or the quarter ends with no points added, the try failed. If the touchdown and the try land in the same snapshot (+7 or +8), one combined alert goes out.

**NBA.** No alert per basket.

| Alert | Team | League | Game | Rule |
|---|---|---|---|---|
| End of quarter (Q1, Q3) | on | off | on | |
| Halftime | on | off | on | |
| Big run | off | off | off | One team scores 10 or more straight points, read from consecutive snapshots |
| Big lead | off | off | off | A 20-point lead. Once per game |
| Tie game late | off | off | off | Q4 and overtime only |

Comeback: down 15 or more, then within 3 in the second half.

**MLB.** Innings read "Bot 4th."

| Alert | Team | League | Game | Rule |
|---|---|---|---|---|
| Run scored | on | off | on | One alert per snapshot, however many runs scored ("Cubs score 2!") |
| End of inning | off | off | off | Full innings only. Option: every inning, or chosen innings |
| Big inning | off | off | off | 3 or more runs in one inning |
| Tie game late | off | off | off | 7th inning or later |
| Bases loaded | off | off | off | ? |
| No-hitter watch | on | on | on | ? After the 6th inning, then after every inning while it lasts. "No-hitter over" if broken up. The final says "no-hitter" if it holds |
| Walk-off | on | on | on | Home team takes the lead in the bottom of the 9th or later, which ends the game |

Comeback: down 4 or more, then within 1, from the 6th inning on.

**NHL.**

| Alert | Team | League | Game | Rule |
|---|---|---|---|---|
| Goal | on | off | on | |
| End of period (1st, 2nd) | on | off | on | |
| Tie game late | off | off | off | 3rd period, 5:00 or less remaining |
| Big lead | off | off | off | 3 or more goals. Once per game |
| Shutout watch | off | off | off | A team scoreless after two periods, with a follow-up if they score |
| Shootout | on | on | on | Separate from overtime |

Comeback: down 3 or more, then within 1, from the 2nd period on; complete on the tying goal. The final notes OT or SO.

#### Overlapping alerts

**What can fire more than once in a game:** each score, each lead change, each new tie in tie-game-late, each big run (a new run starts after the other team scores), each chosen end of inning, each no-hitter update, each further overtime period.

**What fires at most once per game:** starting soon, game start, halftime, each period's end, close game late, comeback on, comeback complete, big lead (per team), shutout watch (per team) and its follow-up, shootout, walk-off, final.

**One moment, one push, except the final.** Every alert produced by the same snapshot of the same game is combined into one notification per user. The headline is the most important alert and the rest ride along. Priority, highest first: walk-off or game-ending score, comeback complete, overtime or shootout, score, lead change, close game late, comeback on, tie game late, big run or big lead or big inning, end of period or halftime, the rest. Some alerts absorb others outright:

- A walk-off absorbs the run scored and the lead change: "WALK-OFF! Cubs win 5–4 · Bot 10th."
- Overtime starting absorbs the end of regulation's last period.
- Halftime absorbs the end of the 2nd quarter (NFL, NBA).
- "Touchdown, Bears take the lead, comeback complete" is one push, not three.

**The final is always its own push.** It is never absorbed or merged, even when the moment that ended the game already alerted. A walk-off is two notifications: the walk-off with its score, then "Final: Cubs 5, Cardinals 4. Cubs win." The same goes for an overtime winner or a shootout. The final replaces the last period's end, and its message includes a no-hitter or shutout if one held.

Genuinely distinct moments stay separate. A lead change in the 2nd quarter and another in the 4th are two alerts. So are a touchdown and its extra point, which happen a minute apart.

In the pipeline, the combining happens in Stage 1. It already works out every alert's audience for a snapshot, so it groups them by user and writes one `notifications` message per user per moment.

#### Scoreboard limits and reliability

**What snapshots can and cannot promise.** The poller sees each game every few seconds.

- **Exact:** start, final, period and halftime ends, overtime, shootout, scores, the NFL try result, close game late, big lead, walk-off, delays.
- **From snapshots:**
  - Lead change and tie game late can be missed if the lead flips twice inside one poll.
  - Big run is almost always right, because both teams rarely score within the same few seconds, but it is not a play-by-play count.
  - Comeback relies on the largest deficit seen in a snapshot.

The catalog promises only these.

**Detection that does not depend on catching a brief moment:**

- **Period ends** are detected from whichever comes first: ESPN's short "end of period" or halftime status, or the period number going up (the status can fall between polls). One flag per period stops the second sighting, and halftime always ends the 2nd quarter, even when ESPN already shows period 3.
- **Clock thresholds** (5:00, 2:00) are judged on snapshots, not on an exact crossing. Close game late and a failed NFL try need the condition on two plays in a row, because a single snapshot can carry a wrong clock (seen: 0:00 mid-period).

**Score corrections.** When a goal or touchdown is overturned, the score goes down. Once the lower score has held (60 seconds of game clock, MLB two more plays, a new period, or the final), the same followers get "Rangers goal overturned" with the corrected score, under the scoring switch it undoes, and its id takes the next value of the scoring counter. A drop that comes back first was an ESPN glitch: it sends nothing, and the goal is not announced twice. The recordings show both: real overturns drop at the stopped clock of the review and stay down; glitches drop while the clock runs and are back within seconds (up to 27 seen). Revised 2026-10-08 (Phase 3.3); it used to send nothing.

**No alert is sent twice.** Every alert has a deterministic id built from the game, the alert type, and what makes it distinct:

- the period, for a period end;
- the inning, for an end of inning or a no-hitter update;
- the scoring team and how many scoring alerts that team has fired this game, for a score. A counter, not the score: after an overturned goal (3 → 2) the next real goal is the score's "3rd" again, and an id built from the score would be dropped as a duplicate;
- the lead change's sequence number, for a lead change;
- nothing extra, for once-per-game alerts.

Retries, repeated snapshots, and two pollers overlapping during a rolling update all produce the same id. The "already fired" flags and the counters live in Stage 1's per-game memory, `det:<gameId>`, written only if the play's version is higher, after the play's notifications are confirmed; a re-read finds the same alerts with the same ids. A push's id is its headline alert's id, and the per-user dedup key is `notif:<alertId>:<userId>` (decision 5), so a crash and retry after a partial delivery does not notify anyone twice. When one snapshot produces a push and the final for the same user, the final is sent after it in the user's lane.

#### How alerts are detected

ESPN gives facts: a score, a clock, a status. "Close game," "lead change," and "comeback on" are not in the feed. They come from comparing this snapshot with the previous one for the same game. That comparison is a set of small detectors, one per alert type. Each is a pure function of (previous state, current state) → alerts, so each one is unit-tested without Redis or Kafka.

The previous state and the "already fired" flags live with the game state in Redis, next to the score the Lua script owns. Detection is correct because every snapshot for a game is handled in order: `game-events` is keyed by game id, so one worker owns a game at a time (decision 4). The detectors run in Stage 1. Their memory for a game is `det:<gameId>` in Redis, written after the play's notifications are confirmed and only if the play's version is higher, so a crash between detection and produce re-detects the same alerts with the same ids. The poller sends a play on every change of game position, not only on scores, so clock and period facts reach the detectors (Phase 3.3 spec).

Starting soon and the daily league summary are not triggered by a snapshot. They come from a small scheduler that reads the schedule and each user's timezone, which is stored with their preferences.

#### How preferences reach fan-out

Preferences are resolved when they are saved, not when an event arrives. Postgres keeps the raw settings: each follow and its switches. On save, the user service writes the result into Redis sets per follow target and alert type, such as `sub:team:LAL:CLOSE_LATE` and `sub:game:<gameId>:HALFTIME`.

At fan-out, the audience for an alert in one game is built in a few set operations, with the most specific follow winning:

1. Everyone in the game's set for that alert.
2. Plus the two teams' sets, minus anyone who follows this game.
3. Plus the league's set, minus anyone who follows this game or either team.

"Anyone who follows" is a set per target, `sub:<kind>:<target>:ANY`, written with the others.

The result is already filtered, and a user reached through several follows appears once. This removes the per-subscriber `SISMEMBER` loop from the fix list. The sets can be rebuilt from Postgres like the rest of Redis (decision 8). Chosen innings for MLB are sets of their own (`…:END_OF_INNING:<n>`), written at save time, so delivery looks nothing up.

#### Gave up, why, and build order

**Gave up.**

- **Play-by-play alerts in v1:** home runs, turnovers, big plays, hat tricks, and the exact reason a try failed.
- **A simple UI:** a screen per follow with a dozen switches is more to build than three checkboxes.
- **Exactness between snapshots**, as listed above.
- **A final per game for league follows:** one daily summary instead.

**Why.** A notification app that pings every basket gets muted. Alerts people actually want are moments: the end of a quarter with the score, a close finish, a comeback worth turning on. Resolving preferences at save time moves the work to the rare event (someone changes a setting) and off the common one (a play happens), the same trade the subscriber index already makes. Detecting alerts from ordered per-game state is a direct use of the lane-per-game decision, not a feature bolted beside it. Deterministic alert ids make "no duplicate alerts" a property the Phase 4 checker can verify.

**Build in waves.** The detector framework is built once. Each alert after that is one small class and its tests.

- **Wave 1:** game start, final (win, loss, tie), scores (with the NFL try follow-up), end of period and halftime, lead change, close game late, overtime and shootout, delayed or postponed. Also the combining rules and deterministic ids.
- **Wave 2:** the scheduler with starting soon and the daily league summary. Until it lands, league follows use the per-game final. Also comeback, tie game late, big run, big lead, big inning, walk-off, no-hitter watch, shutout watch, and two-minute warning.
- **Wave 3:** the alerts that need a field confirmed first (red zone, chance to tie or win, bases loaded).

Waves 2 and 3 are built in Phase 7, after the proof tools and the cloud (moved 2026-10-09): the checker verifies any detector set, so adding detectors later costs nothing.

**Later, with play-by-play and box scores.** These come together with player alerts, because both need the same extra feed, polled only for games someone follows.

- **Player alerts.** Follow a player; milestones such as 40 points, a triple-double, 100 rushing yards, a hat trick.
- **NFL.** "Try no good" becomes "extra point missed" or "2-point try failed"; missed field goals, turnovers, 40+ yard plays, sacks.
- **MLB.** Home runs, grand slams, perfect game watch (walks are not on the scoreboard), cycle watch, 10+ strikeouts, pitching changes, the pitcher's name on a no-hitter.
- **NBA.** Buzzer-beaters, game-winning shots, ejections.
- **NHL.** Power-play goals, pulled goalie and empty net, penalty shots.

---

## Failure behavior you should be able to walk

| What dies | What happens |
|---|---|
| Stage 1 or Stage 2 worker | Kafka session timeout, lanes move to another worker, dedup key prevents a second notification. |
| Gateway | Its WebSockets drop. Users reconnect to another gateway, which overwrites their `conn:` entry. Sessions are in Redis. Anything sent in the gap is in history. |
| Kafka broker, laptop (one broker) | The first play after it goes down is accepted and waits in the outbox; after that the game answers "pending" and its live score freezes. Nothing accepted is lost. When Kubernetes restarts the broker (its disk has the acknowledged log), the retry loop sends the waiting play first, and the next poll carries everything that happened meanwhile as one play. |
| Kafka broker, cloud (three brokers) | Kafka elects a new leader for that broker's lanes from the remaining copies. Writes acknowledged by two copies are not lost. The pipeline pauses for seconds, not minutes. |
| Redis or Postgres pod | Score updates, sessions, or account writes stop until Kubernetes restarts the pod. Disk has the Redis snapshot and the Postgres data. Only in the same availability zone as its disk. |
| A cloud node | Kubernetes restarts its pods on other nodes. If it held a broker, see above. If it held Redis or Postgres, see above. |
| The whole cloud environment (`terraform destroy`) | Expected. Postgres comes back from the S3 dump, Redis is rebuilt from Postgres, Kafka starts empty. |
| Poller | No new plays until it restarts. Restart does not replay a burst of false "game start" events, because the last snapshot is in Redis. |
| Duplicate or late old play | Lua script rejects it. Live score does not move backwards. Log line, not a Kafka record. |

In-system speed, once a play exists: produce with no intentional batching delay on the ingest producer (`linger.ms=0`). Stage 1 can batch for a few milliseconds because it writes many small notification messages. Publish to the WebSocket path before waiting on the Postgres history insert. The number we want on the resume is measured (p50 and p99 from "poller saw it" to "gateway sent it"), not a guess.

---

## Security and correctness still to fix in the current code

These were the known bugs in the course code. Phase 2 fixed the ones that survive into the new pipeline, Phase 3.1 closed the pipeline ones, Phase 3.2 the account and team-id ones, and the rest close as Phase 3 lands. Details of each fix are in `docs/superpowers/specs/2026-10-02-phase-2-correctness-security-design.md`.

**Still open (Phase 3.3 onward):**

- An early "final" from ESPN still becomes the bar for the rest of the game. Never observed; the clock heals below cover only the same period.
- Clock-based alerts (close game late at 5:00) could fire early during a clock glitch. 3.3's detectors.

**Fixed in Phase 3.2** (details in `docs/superpowers/specs/2026-10-07-phase-3-2-postgres-design.md`):

- **Team ids were bare abbreviations.** `sub:team:BOS` was the Celtics, the Red Sox, and the Bruins at once. Team ids are league-qualified (`nba-bos`), and so are the Redis sets (`sub:team:nba-bos:GAME_START`). The smoke test proves an `nhl-bos` follower hears nothing from two Boston games in other leagues.
- **Accounts were lost when the cluster was deleted.** Accounts, follows, and history live in Postgres, whose data on the laptop is a folder on the Mac. `scripts/persistence-check.sh` deletes and recreates the cluster and checks that the account logs in, keeps its follow, and still gets alerts.

**Fixed in Phase 3.1** (details in `docs/superpowers/specs/2026-10-06-phase-3-1-kafka-pipeline-design.md`):

- **The script-then-publish window.** An accepted play is written to an outbox in the same script and cleared only when Kafka confirms it; a retry loop re-sends leftovers, and a game has at most one unsent play so plays reach Kafka in order (decision 5). Tested by a crash after accept, two retry loops at once, a slow broker, and a paused broker.
- **A worker that dies before acknowledging.** Kafka consumer groups move its lanes; workers commit only after a batch is done. Stage 1 dedups by `version` (recorded set-if-higher, so a stalled worker can't lower it); Stage 2 delivers through one script that checks `notif:<messageId>:<userId>`, writes history, publishes, and claims it last (Redis doesn't roll back a failed script), so a redelivery is a no-op; pipelined script errors fail the batch instead of being swallowed. Killing either stage worker during a simulation left each user's history equal to what Stage 1 sent (305, 306, 308 messages).
- **The `notify:*` broadcast.** Each gateway listens only on `gw:<its id>` and records itself in `conn:<userId>` (decision 13); it removes itself before closing sockets with 4000 on shutdown. The smoke test checks a live socket gets the notification.
- **An ESPN snapshot that runs ahead of reality.** The goal heal and the 10-second no-score heal (decision 5). Seen for real in 2 of 23 recorded NHL games.

**Fixed in Phase 2:**

- **Fan-out matched only the tagged team.** A follower of either team now gets every event in the game. Redis builds the audience with `SUNIONSTORE` and `SINTERSTORE` instead of one `SISMEMBER` per subscriber; with millions of followers that one command would pause Redis briefly, which Phase 4's load test should measure.
- **Poller ids and timestamps.** Events are ordered by `seq`, a 14-digit number built from game progress (phase, period, half, clock, outs, stage, score total), and the event id is `<gameId>:<seq>`. An overlapping poller's events are duplicates or stale, so no singleton lock is needed. The 50-event burst test from the course (43 → 48 → 50) now runs on every push.
- **Poller memory and the snapshot-before-POST bug.** The poller keeps no memory: it compares ESPN with the game record the Lua script saved, which only changes when an event is accepted. A restart re-sends nothing, and a failed POST is retried by the next poll with the same id. `GAME_SYNC` records games seen for the first time without alerting anyone.
- **Game keys never expired.** A record expires a day after a final, or after 8 idle days. The game list reads a bounded `games:recent` set instead of scanning every key.
- **Signup race.** The username is claimed with `SET NX`, after the user record is written. Before the fix, 20 of 20 simultaneous signups for one name succeeded; now exactly one does.
- **Passwords** are 8 to 72 bytes (bcrypt ignores anything past 72).
- **Login throttling.** 5 attempts per username or 20 per client address in 15 minutes, then `429`. Each attempt is counted *before* the password check: the first version counted failures afterwards, and the final review showed 50 simultaneous guesses all got through (the same check-then-act bug as the score race). The address is the one Traefik saw, never the client-written first `X-Forwarded-For` entry.
- **Session cookie** is `SameSite=Lax`, and `Secure` everywhere except the laptop (`COOKIE_SECURE=false` in `values-local.yaml`).
- **Internal ports.** Network policies: only Traefik reaches the gateway, only the gateway reaches user-pref, game-state, and notification, only the poller and simulator reach event-processing, only our pods reach Redis. The smoke test proves a stranger pod is refused.
- **`GameStateService.updateGameState`** was dead code and is deleted. The Lua script is the only writer of game state.
- **Startup crash loop.** Services wait for Redis instead of crashing when they start first.

## Build order

We implement in this order. The system runs at the end of each step. Everything through step 4 happens on the laptop and costs nothing. Mistakes are free there.

0. **Lock the plan.** This document. Git init, and commit the course code untouched first, so the original is always there to point back to.
1. **Foundation.** Flatten `com3810/`. One Dockerfile, `SERVICE` picks the main. A Helm chart running the current services and Redis on k3d. Tests in GitHub Actions. **Done.**
2. **Correctness and security.** The bugs above, retries, the provider interface, poller snapshots in Redis, poller ids from ESPN's data. **Done** (PR #2, 2026-10-02). Fixes from 60 hours of recorded ESPN data followed in PR #4. Issue #3, the by-hand live-game check, was closed 2026-10-07 as covered by those recordings and the smoke test's WebSocket check; the full live check happens with the new UI.
3. **The new pipeline**, split into four sub-phases, each with its own spec, plan, and PR:
   - **3.1 Kafka pipeline.** Kafka through Strimzi with one broker, the two topics, script-then-produce with the outbox, Stage 1 and Stage 2 consumer groups, per-user dedup, gateway routing (decision 13). **Merged** (PR #6, 2026-10-07; spec and plan `2026-10-06-phase-3-1-kafka-pipeline`).
   - **3.2 Postgres.** Accounts, follows shaped for decision 15, notification history, Redis subscriber sets rebuilt on startup, the `/auth`, `/me`, `/follows`, `/notifications` endpoints of `docs/api/`. The course UI stops working here; the new UI arrives in 3b. **Merged** (PR #13, 2026-10-07; spec and plan `2026-10-07-phase-3-2-postgres`; history writers are their own consumer group; follow saves commit to Postgres before Redis).
   - **3.3 Alerts, wave 1.** The detector framework and wave 1 detectors in Stage 1, most-specific-follow set math, combining, deterministic alert ids (decision 15). **Merged** (PR #14, 2026-10-08; spec and plan `2026-10-07-phase-3-3-alerts-wave-1`; detectors replayed against the ESPN recordings; overturns alert once they hold).
   - **3.4 Live API.** `/games`, `/leagues`, the alert catalog, and live `game_update` over the WebSocket through a per-game channel published by the accept script; a schedule cache for yesterday through 7 days ahead; the unread badge as a Redis counter; `Final` labels; the no-score heal down to 10 seconds. **Merged** (PR #28, 2026-10-08; spec and plan `2026-10-08-phase-3-4-live-api`).
3b. **New UI.** A full redesign of the web UI, frontend only: it calls the gateway's API and WebSocket and has no database, auth, or server code of its own. The code lives in `web/` and ships as static files in the same Helm chart. It is built against the API spec in `docs/api/`, written before Phase 3 and implemented by Phase 3, so the UI can start early against a mock server. Built as an installable web app (PWA) so Phase 7's Web Push can reach a phone. Screens can be designed any time; the build lands after Phase 3. Spec and plan `2026-10-08-phase-3b-ui`: the app is called Sideline, rebuilt on Vite and React from the user's v0 design (v0's code is the reference, not ported); the gateway serves the built files from the same image at `/`, same origin as `/api/v1`, so the session cookie and the socket need no CORS; checked by a Playwright run in CI after the smoke test and by a live-game check with real ESPN data (issue #3). Installing on a phone needs HTTPS, so it is first checked in Phase 5. **Merged** (PR #32, 2026-10-09). The live-game check was waived for the merge and is issue #36, due before Phase 5.
4. **Proof tools**, split into three sub-phases, each with its own spec, plan, and PR:
   - **4.1 Replay and checker.** Recorded games replayed through the real poller at 1× to 60×, synthetic users on real sockets, the checker of decision 14, and the versioned `sub:built` marker (#16, #18). Spec `2026-10-09-phase-4-1-replay-checker`. **Merged** (PR #46, 2026-10-09): the `evening` set (10 games) passed with 6,573 expected pushes, none missing or duplicated; the self-test caught all three faults.
   - **4.2 Chaos suite on k3d.** Spec `2026-10-11-phase-4-2-chaos`. Eight scenarios, each one checked replay (`evening` at 60×) with one fault at 40% of the way: a crash (SIGKILL from the k3d node, not a polite `kubectl delete`) of a Stage 1 worker, a Stage 2 worker, the History writer, a gateway, and the broker; a rolling restart of the gateways; and a Stage 1 or Stage 2 worker cut off from Kafka for 60 s, while it keeps Redis. The cut is a network policy plus deleting the pod's tracked Kafka connections on the node, because k3s lets connections that are already open through a new policy (measured). Two copies of Stage 1, Stage 2, and the gateway become the chart's default. `make chaos` on the laptop, and a manual GitHub workflow, not every push. Fixes for what it trips over (#9, #10, #17, #31, #44; #12 closes), and the checker's own gaps that chaos exposes: a missing push is excused only if its socket was down within 2 s of when Stage 2 delivered it, timed by Redis's clock (#48), and a time limit on the checker Job (#49). **Merged** (PR #53, 2026-10-13): all eight pass on the laptop and in the Chaos workflow.
   - **4.3 Traces and metrics.** Spec `2026-10-13-phase-4-3-observability`. One `grafana/otel-lgtm` pod, on by default and in CI; manual OpenTelemetry instrumentation (not the Java agent), so every hop is code in the repo. One trace per play: the poller's HTTP header, a `traceparent` field in the outbox (so a play the retry loop sends after a broker outage stays one trace), Kafka headers on both topics, and a field in the pub/sub body. One latency histogram, "time since the poller saw it", at four stages, with exemplars; Kafka lag from Strimzi's exporter; a "Pipeline" dashboard whose screenshot during `make chaos` (each fault marked by an annotation) shows the spike and the recovery. `make trace-check` proves one trace spans every service, in CI (after freeing the runner's disk, which the observability image filled). Also #54 (4.2's six deferred minors). **Built** (PR #55): all eight chaos scenarios pass with telemetry on; screenshots in `docs/evidence/phase-4-3/` show a Stage 1 crash (p99 44 s, Stage 1 lag climbing to about 500 and draining) and a broker crash (p99 about 30 s). Two lessons: each process needs its own `service.instance.id`, or two copies of a service share one Prometheus series and every alternation reads as a counter reset; and a laptop that sleeps mid-run jumps Redis's clock, so checks run under `caffeinate`.
5. **AWS.** Terraform for the network, EKS, ECR, and S3. The Postgres dump and restore. Three Kafka brokers. KEDA on lag. GitHub Actions pushes the image to ECR.
6. **Evidence.** The chaos suite with the checker, on EKS (built and first run on k3d in 4.2). A load test that makes KEDA scale out and back in. The numbers, a trace screenshot, and the scaling graph go in the README. A public demo build of the UI (fake data, static hosting), so the UI can be shown while AWS is off.
7. **Push.** Web Push for a closed browser, quiet hours, rate caps. Alert waves 2 and 3 (decision 15), each detector tested against recorded games. Then play-by-play and box scores for followed games: player alerts and the rest of decision 15's "later" list.
8. **Maybe.** Hybrid fan-out, only if step 6 says the extra hop matters. Kafka transactions for Stage 1, only if the checker finds duplicates.
9. **Next project phase: AI layer.** Scoped separately once the pipeline works. It would be its own consumer group, so it cannot slow delivery.

---

## What we are not building

- A leader-election library, heartbeats between workers, or ZooKeeper.
- Redis Sentinel or Postgres replicas. Kafka is the only replicated piece, and only in the cloud.
- Always-on hosting. The cloud is up when you want it.
- Amazon MSK, ECS, Fly.io, Railway, Hetzner, or Lambda as the place it runs.
- Multi-region, a service mesh, a schema registry, or Redis Cluster.
- A paid sports feed.
- A UI with its own backend. Generator tools that add a database or login system are used for the frontend only (phase 3b).
- Kafka as a replacement for Redis pub/sub on the last hop to the browser. Open sockets stay a gateway concern. Redis pub/sub is how the right gateway hears a notification.
- The AI layer, inside this redesign. It is the next phase.
- "Designed for millions of users" as an unmeasured claim. The measured claim is: lanes by game, delivery by user, a dead worker loses nothing, here are the timings.
