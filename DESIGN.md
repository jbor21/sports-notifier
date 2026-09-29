# SportsNotifier — design and decisions

This is the map of the project: what it does today, what we are changing it into, and every decision we made on the way. It is written so you can explain the system without reading the code line by line.

Last updated 2026-09-28. Nothing in the "target" sections is built yet. The code on disk is still the course version described under "What exists today."

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
| Separate stateless Java services | Package them as one image and run them in Compose, on your machine and on one cloud VM |
| The Lua script as the authority on the live score | Run it before producing, and produce to Kafka instead of a Redis stream |
| Fan-out from an inverted subscriber index | Index stays in Redis, but it is a cache rebuilt from Postgres |
| More workers when there is more work | Kafka lanes, so a dead worker's games move without a leader you wrote |
| ESPN as the data source for now | Hide it behind an interface, poll faster when games are live, and don't pretend it is a licensed feed |

You are not throwing away the semester and starting over. You are also not leaving it as homework with a README that says "millions of users." The resume story is smaller and true: you found a race, proved it, fixed it atomically, then took the same pipeline and gave the log, the user data, and the failure handling each a real tool. The fall class is why you can say you have built leader election and chose not to build it again.

### What "done" looks like

A person can open a URL, create an account, follow a team, and get a notification when a play they care about happens. The account survives a Redis restart. Kill a worker during a game and someone else finishes the job without a duplicate notification. The README leads with the race and the timings you measured, not with a scale claim you did not measure. You can draw the path from ESPN to the browser and explain every box.

Hosting is one always-on VM plus your laptop, same Compose file. Cloudflare in front of the VM. The processes are distributed. The machines are not, until a later split is actually worth it. That distinction is part of the explanation, not a footnote.

---

## What the system is

A real-time sports notification platform. A person creates an account, follows teams, leagues, or specific games, and chooses which kinds of events they care about. When one of those events happens, they get a notification.

The product goal is the thing the original design doc promised: updates arrive as they happen, so the person does not have to keep the app open and refresh. The course version only does that while a browser tab is open. Real push to a closed phone is a later phase.

Different sports get different notification types, because the games do not produce events at the same rate.

- NFL: touchdowns, field goals, turnovers, game start, final. Scoring plays are rare. The free feed already labels them.
- MLB: scoring plays, not every pitch.
- NBA: game start, final, and maybe lead changes. Not every basket. A basket happens too often to notify on by default.

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

**Idempotency.** Each event has an id. Processing it twice is a no-op. Retries are safe because of that.

**History separate from the live push.** The history list survives. The pub/sub message only reaches whoever is listening right now. A user who was offline sees history when they open the app.

---

## Target architecture

Three infrastructure processes, each with one job:

| Piece | Job |
|---|---|
| Postgres | Accounts, password hashes, preferences, notification history. Source of truth. |
| Kafka (one broker) | The event log. Accepted plays in, deliveries out. |
| Redis | Sessions, live score, subscriber-set cache, pub/sub to gateways. Rebuildable from Postgres, except sessions, which are disposable. |

The Java services stay separate processes. One Docker image, an env var picks which `main` runs. The same Compose file runs on your Mac and on one cloud VM.

```
ESPN
  → poller                         (adapter, stateless, snapshots in Redis)
  → Event Processing               (Lua script, then produce)
  → Kafka topic game-events        (key = gameId)
  → Stage 1 workers                (who is subscribed?)
  → Kafka topic notifications      (key = userId)
  → Stage 2 workers                (deliver)
       → Redis pub/sub → gateways → WebSocket
       → Postgres history
```

Postgres is written by the user service and by the history path. On startup the user service rebuilds `sub:*` from Postgres.

---

## How one accepted play moves

Packers–Falcons, game id `401872948`. A touchdown is newer than the saved timestamp.

1. The poller emits one event with a fixed event id, the game id, the type, the score, and the play text.
2. The Lua script accepts it and the live score moves forward.
3. Event Processing produces to `game-events`. The message key is the game id. Kafka hashes the key: `hash("401872948") % 12`. That lane is the only lane this game's events will ever use. Every later play from this game hashes to the same lane.
4. The Stage 1 worker that owns the lane reads it, in order with the other plays from that game. It asks Redis who follows the Packers, the Falcons, this game, and the NFL, then who asked for touchdowns. For each matching user it writes one message to `notifications`, keyed by that user's id.
5. Those messages spread across 48 lanes. Stage 2 workers deliver in parallel: publish on Redis so an open tab updates, and insert the history row in Postgres. A user's messages stay in one lane, so that user still sees them in order.

A normal game's history is the full sequence. The poller sends 2–2, then 3–2, then 4–2. Each is newer than the last, so each is accepted. The timeline matches the game. A play is dropped only when an older event arrives after a newer one was already saved. The live score must not go backwards. That late play can be logged. It is not delivered as if it were the new score.

---

## Decisions

Each one is in the form you will use out loud: what we chose, what we gave up, why.

### 1. Kafka, not Redis Streams, for the event log

**Chose.** Apache Kafka, one broker, as the log.

**Gave up.** Redis Streams, which is what the course code uses. Streams are simpler, already written, lighter on memory, and fast. At this volume (a few thousand events a week) they would not fall over. Replay exists on Streams too, within whatever you have not trimmed. `MAXLEN 1000` silently dropping old events is a config mistake, not a reason Streams cannot retain data.

**Why.** Ordered, parallel, fault-tolerant consumption needs lanes that each have one owner, with ownership moving when a worker dies. Kafka does that. Streams can be hand-sharded (`hash(gameId) % 12` separate stream keys), and then you still have to decide which worker owns which shard and what happens when that worker dies. That is the leader-election project again, inside the notification app. We also stop sharing one Redis process between the cache and the log, so a backlog in the log is not competing with session lookups.

The interview line: "I used Redis Streams first. Throughput was fine. What I could not get was key-based lanes with automatic reassignment, and building that myself would have meant writing a coordinator in the application."

### 2. Apache Kafka, not Redpanda

**Chose.** Apache Kafka in KRaft mode, one broker.

**Gave up.** Redpanda, which speaks the Kafka protocol and uses less memory. The Java client would be the same.

**Why.** Cost was set aside for this choice. Kafka is the broker every tutorial, failure write-up, and managed service (Amazon MSK) is about. You learn the real broker. The resume line has no asterisk. Redpanda remains the answer to "what would you run on a tiny box," and it would be an image swap, not a code change.

### 3. Two stages, not one

**Chose.** Two topics and two consumer groups.

- `game-events`, key = `gameId`. Stage 1 only decides who to notify and writes one message per user.
- `notifications`, key = `userId`. Stage 2 delivers.

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

**Why not the other way.** Produce-then-script makes Kafka a complete inbox of everything the poller sent, and a crash cannot eat the raw event. Every reader of that topic then has to apply the stale check. The check is cheap (well under a millisecond). Forgetting it in one new consumer is a bug: a history writer or a metrics job would treat a rejected score as real. A second "accepted only" topic fixes that and puts the gate one hop later, with extra lag that looks like real backlog when the inbox is full of duplicates.

The scoring timeline stays correct on the normal path. The poller sends plays in order. Each is newer than the last, so each is accepted. The jump from 2–2 to 4–2 happens only when the older play arrives after the newer one was already saved. The script is protecting the live score in that case.

### 6. Buy coordination. Do not write leader election

**Chose.** Kafka's consumer group assigns lanes. Workers heartbeat to the broker. Miss the heartbeat and the broker gives those lanes to someone else. Redis's single-threaded script is the authority for the score. There is no leader among the Java workers.

**Gave up.** The fall project's property: any single machine can die and the remaining workers elect a replacement. Here, if the one Kafka broker dies, or the one Redis dies, assignment and score updates stop until that process is restarted. Making them survive that crash means running more than one broker or more than one Redis, on separate machines, and using their own election. We are not doing that.

**What "restart" means.** The process is dead. Docker or the VM starts it again. Workers keep running and their calls fail and retry. Plays Kafka had already acknowledged are still on disk. Plays in flight are retried. Users get nothing new until the process accepts connections again. That gap is usually seconds.

**What "more than one" would mean.** Three Kafka brokers, each lane with a copy on another broker. If the leader broker for a lane dies, the others elect a copy. Redis Sentinel is a primary, a replica, and sentinels that elect the replica. On one VM this is mostly fake: the machine dies and all the copies die with it. It matters when the copies are on separate machines. That is a later infrastructure add, not Java election code.

A dead worker is the failure this design absorbs by itself. Kill Stage 1 mid-game. The lane waits out the session timeout. Another worker reads from the last committed offset. The per-user dedup key (`notif:<eventId>:<userId>`, set-if-absent) stops a double notification.

### 7. One computer, even though the processes are distributed

**Chose.** One cloud VM for the real deployment. Containers on that VM are separate processes. They talk over the network. They share CPU, disk, and fate.

**Gave up.** The sentence "it runs across machines." If the VM dies, everything dies together.

**Why it is still a distributed system.** The services do not share memory. Moving one of them to another address is configuration. We prove the worker story by running several workers in Compose and killing one. We do not prove multi-machine survival, and we do not claim it.

Two VMs later (data stores on one, Java on the other) would make the failure domains real. Not before the single VM is up and the kill-a-worker test has passed.

### 8. Postgres for durable user data, Redis for speed

**Chose.** Postgres holds accounts, password hashes, preferences, and notification history. Redis holds sessions, the live score, the `sub:*` cache, and pub/sub to the gateways. Startup rebuilds `sub:*` from Postgres.

**Gave up.** One store. We now run Postgres, and a preference write can land in Postgres before Redis has the new set membership. That window is short and rebuildable.

**Why this direction.** Redis can be rebuilt from Postgres. Postgres cannot be rebuilt from Redis. A cache is not the only copy of a person's account.

The live score in Redis is rebuilt from the accepted log if we need it (replay `game-events`). It is not a Postgres table.

### 9. Laptop and one VM, same Compose file

**Chose.** Develop on your machine. Deploy the same Compose file to one small cloud VM. Cloudflare in front of the VM for DNS and HTTPS.

**Gave up.** A public URL that stays up when the laptop sleeps, if we only ran it locally. Also gave up AWS as the place the processes run. A few dollars a month on a small VM (Hetzner is the one we aimed at) instead of a larger AWS bill. S3 for backups is an optional cheap AWS touch. Managed Kafka (MSK) is out; it costs more than the rest of the project.

**Why.** The code does not know which machine it is on. The resume claim is the VM that stays up. The laptop is how you work. Cloudflare Tunnel is the way the VM can avoid exposing every port: the VM dials out, users hit Cloudflare.

The app cannot move to Cloudflare Workers. Workers run short JavaScript, not a JVM, not Kafka, not a process that polls forever. Your expense-tracker PWA fits Workers. This system does not.

### 10. ESPN behind an interface, no paid feed

**Chose.** A `SportsDataProvider` interface. The ESPN poller is the current adapter. Poll about every 5 seconds while games are live and about every 60 seconds when nothing is. Send a browser-like User-Agent. Back off and alert on 403s. Snapshots of the last score live in Redis, not in a `HashMap`, so a restart does not re-send "game start" for every game in progress. Play-by-play (`summary?event=<id>`) is polled only for games someone is actually following.

**Gave up.** A licensed push feed (Sportradar, Genius, Stats Perform / Opta). Those are what the real apps pay for. Official feeds are push, not poll, and they are enterprise contracts. Cheap developer APIs are still polling and are not meaningfully faster than ESPN. Even the paid play-by-play feeds trail the television broadcast by roughly 15–20 seconds.

**Why.** Almost all of the delay a user feels is the poll interval plus ESPN, not Kafka. The in-system path is tens of milliseconds. We will measure it (timestamp when the poller saw the play, record the delay at the gateway). The interface is so a push provider is one class later, and so the unofficial API is an adapter you can explain instead of a hidden dependency.

### 11. Hybrid fan-out, later, only if measurement says so

**Not in the first build.** After two-stage delivery is load-tested, we may add this:

- Stage 1 counts the audience with `SCARD` on the subscriber sets (an overestimate, which is the safe direction).
- Under about 1,000 users, Stage 1 delivers directly and skips the second topic.
- At or above that, it uses `notifications` as normal.
- Once a game takes the Stage 2 path, it stays there for the rest of the game (`game:<id>:fanout` in Redis, set before the first Stage 2 produce). Fast then slow preserves order. Slow then fast can reorder.
- Both paths call the same delivery code and the same per-user dedup key.

**Why it waits.** If the extra hop is a few milliseconds on top of a multi-second data delay, the hybrid is a second code path nobody can feel. "Designed, measured, skipped" is a better result than building it early. The direct path also pulls I/O back onto the game's only Stage 1 worker, which is the ceiling two stages exist to remove. Web Push on the direct path makes that worse. If we build it, the direct path should do the fast channel only, and history plus push should stay on the topic.

---

## Failure behavior you should be able to walk

| What dies | What happens |
|---|---|
| Stage 1 or Stage 2 worker | Kafka session timeout, lanes move to another worker, dedup key prevents a second notification. |
| Gateway | That machine's WebSockets drop. Users reconnect. Sessions are in Redis. A second gateway would also receive `notify:*` and push to its own sockets. |
| Kafka or Redis process | Pipeline stalls, retries, then continues when the process is back. Disk has the acknowledged log and the Redis snapshot. |
| The VM | Everything is down until the VM is back. No election. |
| Poller | No new plays until it restarts. Restart does not replay a burst of false "game start" events, because the last snapshot is in Redis. |
| Duplicate or late old play | Lua script rejects it. Live score does not move backwards. Log line, not a Kafka record. |

In-system speed, once a play exists: produce with no intentional batching delay on the ingest producer (`linger.ms=0`). Stage 1 can batch for a few milliseconds because it writes many small notification messages. Publish to the WebSocket path before waiting on the Postgres history insert. The number we want on the resume is measured (p50 and p99 from "poller saw it" to "gateway sent it"), not a guess.

---

## Security and correctness still to fix in the current code

These are known bugs in the course code. They get fixed before or as the new pipeline lands. They are not optional polish.

- The Lua script and the publish are separate steps. A crash between them marks the event processed and never publishes it. The Kafka version has to close that window (decision 5).
- Fan-out matches only the team that just scored. Following the Lakers misses Celtics baskets in a Lakers game. Match home and away as well.
- If a worker dies before ack, the entry sits forever. Kafka's session timeout replaces the missing Redis reclaim. Per-user dedup has to exist before retries are turned on, or people get duplicates.
- Poller memory is a `HashMap`. Restart spams game-start events. Persist snapshots in Redis.
- Game keys never expire, and the game list scans all of them. TTL finished games. Keep a bounded set of live games.
- Internal ports have no auth. Only the gateway is reachable from outside. Signup does get-then-set on the username and can create two users. Login is unthrottled. Password minimum is 4 characters. The session cookie is missing `Secure` and `SameSite`.
- `GameStateService.updateGameState` is only called from tests. The script does the real write. Do not describe the unused method as defense in depth.

---

## Build order

We implement in this order. The system runs at the end of each step.

1. **Foundation.** Git, flatten `com3810/`, Docker Compose with Kafka, Postgres, and Redis, tests in GitHub Actions.
2. **Correctness and security.** The bugs above, retries, the provider interface, poller snapshots in Redis.
3. **Deploy.** Same Compose file on one VM, Cloudflare in front, so there is a URL before the rest lands.
4. **Postgres.** Accounts, preferences, history. Redis subscriber sets rebuilt on startup.
5. **Kafka.** The two topics, the script-then-produce path, consumer groups, per-user dedup.
6. **Observability.** Structured logs, request ids, lag and latency metrics.
7. **Evidence.** Load test, kill a worker, restart Redis, two gateways. Put the numbers in the README.
8. **Push.** Web Push for a closed browser, quiet hours, rate caps. Sport-specific notification types from the play-by-play feed.
9. **Maybe.** Hybrid fan-out, only if step 7 says the extra hop matters.

---

## What we are not building

- A leader-election library, heartbeats between workers, or ZooKeeper.
- Three Kafka brokers, Redis Sentinel, or a multi-VM cluster, until a single VM is actually in use.
- A paid sports feed.
- A React rewrite of the frontend.
- Kafka as a replacement for Redis pub/sub on the last hop to the browser. Open sockets stay a gateway concern. Redis pub/sub is how every gateway hears a notification.
- "Designed for millions of users" as an unmeasured claim. The measured claim is: lanes by game, delivery by user, a dead worker loses nothing, here are the timings.
