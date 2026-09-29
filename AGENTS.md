# Sports Notification App — Agent Context

Read `DESIGN.md` first. It is the decision record for the redesign: what the
semester project was, what we are changing it into, and every tradeoff already
made. This file is the short brief for working in the repo. If the two
disagree, `DESIGN.md` wins. Do not reopen a locked decision unless the user
asks. Last updated 2026-09-28.

---

## How to use this file

This project **began** as a graduate Advanced Distributed Systems course project
(COM3810, Yeshiva University) and is **now becoming a real, cloud-hosted product.**

The course rubric and professor feedback are recorded below as **context, not
constraints.** They are useful because they represent a sharp reviewer's read of
the system — but they describe a semester deliverable, not the product we're
building. Do **not** treat any course requirement as a rule to preserve.

Specifically, these are **no longer binding**:
- The `com3810/` directory nesting, or any course-mandated file layout
- Google Docs deliverables, Canvas submission, stage-by-stage structure
- The C4/BPMN diagrams as a spec the code must mirror (they're historical)
- Keeping a design decision only because it was defended in a graded document

Where the professor's critique identifies a **real engineering problem**, it
still counts. Where it reflects **course scope**, ignore it.

---

## What the system does

A real-time sports notification platform. Users create accounts, follow teams /
leagues / specific games, choose which event types they care about
(`GAME_START`, `SCORE_UPDATE`, `GAME_END`), and receive live push notifications
over WebSocket as those events occur.

## Architecture

Nine independent JVM processes coordinated **entirely through Redis**. There is
no database — Redis holds streams, pub/sub, sets, hashes, and TTL sessions.

```
Simulator / ESPN Poller
      │ POST /events
      ▼
Event Processing (8083) ──► atomic Lua: staleness + dedup + game-state write
      │ XADD game-events (MAXLEN ~1000)
      ▼
Redis Stream ──► worker-1 / worker-2   (XREADGROUP, one consumer group)
                      │ SSCAN sub:team:* / sub:game:* / sub:league:*
                      ▼
              Notification (8087) ──► LPUSH history + PUBLISH notify:<userId>
                                                │
Browser ◄── WebSocket ◄── Gateway (8080) ◄── PSUBSCRIBE notify:*
```

| Service | Port | Entry point | Role |
|---|---|---|---|
| Gateway | 8080 | `gateway.GatewayMain` | HTTP API, WebSocket push, sessions |
| User Preference | 8081 | `service.impl.UserPreferenceServiceMain` | Accounts, prefs, subscription index |
| Game State | 8082 | `service.impl.GameStateServiceMain` | Game state reads **only** |
| Event Processing | 8083 | `service.impl.EventProcessingServiceMain` | Validate, dedup, state write, publish |
| PubSub Worker ×2 | 8084/8085 | `service.impl.PubSubWorkerMain` | Consume stream, fan out |
| Notification | 8087 | `service.impl.NotificationServiceMain` | History + Redis publish |
| Simulator / ESPN Poller | — | `simulator.*` | Event sources |

**Stack:** Java 17 · Maven · Javalin 6.1.6 · Jedis 4.4.7 · Gson · jbcrypt
**Size:** ~2,400 lines Java · one 1,851-line `index.html` (vanilla JS, no framework, no build step) · 31 tests

All Redis key prefixes live in `util/RedisKeys.java` — use them, don't inline strings.

---

## Course history (context only)

Final grade **18.5 / 20**. Full feedback is in the uploaded
`Advanced_Distributed_Systems_Semester_Project_Feedback.md`.

The professor deducted 1.5 points for exactly two things:

1. **A TOCTOU race** in dedup/staleness — under a 50-event concurrent burst,
   game score came out 43 instead of 50. Suggested fix: a Lua script making
   dedup + staleness + update atomic.
2. **Minor code structure** — `GatewayMain` was a ~200-line monolith, Redis key
   strings were inline, model classes lacked field-level Javadoc.

### Every graded deduction has already been fixed in the current code

This is important, because the feedback document describes an **older snapshot**
than what's on disk. Verified against the source:

| Deduction | Status in current code |
|---|---|
| TOCTOU race | **Fixed** — `EventProcessingServiceImpl.java:40` is a single Lua script doing staleness + dedup + state write atomically. Exactly the fix suggested. README documents 43 → 48 → 50. |
| `GatewayMain` monolith | **Fixed** — now 157 lines; `EspnProxyHandler` (103) and `GameListingHandler` (54) extracted. |
| Inline Redis key strings | **Fixed** — `util/RedisKeys.java` centralizes them. (Leftover: `user:` / `username:` in `UserPreferenceServiceMain` are still inline.) |
| Model field Javadoc | **Fixed** — e.g. `model/GameEvent.java` documents every field. |

Earlier-stage feedback also addressed: Stage 2's "Event Processing querying User
Preference on every event will bottleneck at Super Bowl scale — use pub/sub
fan-out and cache preferences" is handled by the Redis Streams fan-out plus the
`sub:*` inverted index.

**Do not re-fix these.** If a future task sounds like one of them, verify first.

---

## Open problems (found by review, not by the professor)

These are still true of the code on disk. They are not the architecture plan.
The target fixes, and a few more, are in `DESIGN.md` under "Security and
correctness still to fix." Do not re-solve them by keeping Redis as the only
database or Redis Streams as the log.

These are live and unaddressed:

1. **Signup TOCTOU** — `UserPreferenceServiceMain.java:73-82` does
   `GET username:<name>` then `SET`. Two concurrent signups for the same
   username both pass. Needs `SETNX`. Same bug class the professor flagged
   elsewhere.
2. **Fan-out is N+1** — `PubSubWorkerImpl.java:119-123` calls `SISMEMBER` in a
   Java loop, one round trip per subscriber. This is the actual bottleneck for
   the "millions of users" story. Pipeline it or intersect in Redis.
3. **Dead code** — `GameStateServiceImpl.updateGameState()` is called *only from
   tests*; the Lua script does the real write. The `gameStateService` field in
   `EventProcessingServiceImpl.java:17` is assigned and never read. The README
   calls this "defense-in-depth," which describes intent, not behavior.
4. **No pending-entry recovery** — if a worker dies mid-event, `xack` never
   fires and the entry sits in `XPENDING` forever. Needs `XCLAIM` + dead-letter.
5. **No service-to-service auth** — `user-pref:8081` serves `/prefs/<anyUserId>`
   to anything that can reach the port. Fine on localhost, **a real hole in
   cloud** without a private network.
6. **Cookie lacks `Secure` / `SameSite`** — `SessionAuth.java:26-29`. Must fix
   before HTTPS.
7. Password minimum is 4 characters.

---

## Current state of the repo

- **Not a git repository.** No `.git` anywhere. The course version lived at
  `Yeshiva-University-CS/Borenstein_Jacob_800755097`. Initializing a fresh repo
  is an early task.
- **Not built** — `code/target/` is empty. Build with
  `mvn -f com3810/code/pom.xml clean package` (produces 8 fat JARs).
- **No deployment infrastructure at all** — no Dockerfile, no compose, no CI, no
  IaC. `scripts/run-all.sh` is `nohup` + PID files on one machine.
- Tests require a live Redis on `localhost:6379` and flush DB 1.

---

## Where this is going

The target is a redesign of the spring 2026 course project, not a rewrite and
not a new product. Full rationale is in `DESIGN.md`. Locked:

- **Apache Kafka, one broker.** Two topics: `game-events` hashed by `gameId`,
  `notifications` hashed by `userId`. Not Redis Streams, not Redpanda, not
  least-busy lane assignment.
- **Lua script stays the score authority and runs before the Kafka produce.**
  The topic contains only accepted plays.
- **Postgres** is the source of truth for accounts, password hashes,
  preferences, and notification history. **Redis** is sessions, live score,
  the `sub:*` cache, and pub/sub to gateways. Redis is rebuildable from
  Postgres.
- **No leader election in the Java services.** A dead worker is a Kafka
  rebalance. A dead broker or Redis is a restart. One cloud VM, not a
  multi-broker cluster. Containers on that VM are not "different machines."
- **Same Compose file** on the laptop and on one small VM (Hetzner), Cloudflare
  in front. Not Fly.io, Railway, Cloud Run, or Lambda. Not Cloudflare Workers.
- **ESPN** stays behind a provider interface. No paid feed. Frontend stays as
  is unless a backend change forces a touch.
- Hybrid fan-out (deliver small audiences inside Stage 1) is a later phase,
  only after the two-stage path is load-tested.

Build order is in `DESIGN.md`: foundation, correctness, deploy, Postgres,
Kafka, observability, evidence, push.

### AI features
Not part of this redesign. **Ask before designing.** Any LLM work here should
load the `Codex-api` skill first and use current Codex models.

---

## Conventions

- Interfaces in `service/`, implementations in `service/impl/`, each service has
  a `*Main` with a Javalin app and a shutdown hook.
- Every Jedis handle goes through try-with-resources. Pool via
  `JedisPoolHolder.create()`; `JedisPoolHolder.dedicated()` only for blocking
  subscribe/consume connections.
- All config through env vars with defaults — keep it 12-factor.
- Redis key prefixes come from `util/RedisKeys.java`.
- Match the existing comment density: the code explains *why* at the tricky
  spots (the Lua script, the fan-out) and stays quiet elsewhere.

## Open decisions

None on architecture. Flatten `com3810/` during the foundation step. Services
stay separate processes in one Compose file. Postgres and Kafka are decided.
Do not propose Fly.io, Railway, Redpanda, Redis Streams as the log, or a
React rewrite unless the user asks.
