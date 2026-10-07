# Sports Notification App — Agent Context

Read `DESIGN.md` first. It is the decision record for the redesign: what the
semester project was, what we are changing it into, and every tradeoff already
made. This file is the short brief for working in the repo. If the two
disagree, `DESIGN.md` wins. Do not reopen a locked decision unless the user
asks. Last updated 2026-10-07.

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

Separate JVM processes, one Docker image (`SERVICE` picks the main), deployed
by one Helm chart on k3d. **Kafka** (Strimzi, one broker locally) is the event
log; **Redis** holds the live score, the outbox, sessions, accounts and
preferences (until Phase 3.2's Postgres), the `sub:*` index, and pub/sub to
gateways.

```
Simulator / ESPN Poller
      │ POST /events
      ▼
Event Processing (8083) ──► atomic Lua: duplicate · pending · newer (seq, two heals)
      │                     → state write + version + outbox:<gameId>
      │ produce game-events (key = gameId, 12 lanes); confirmation clears the outbox;
      │ a retry loop re-sends leftovers
      ▼
Stage 1 (8085, group stage1) ──► version dedup, audience from sub:* sets
      │ produce notifications (key = userId, 48 lanes), one message per user
      ▼
Stage 2 (8086, group stage2) ──► one Lua script: notif:<msg>:<user> claim
      │                          + history + PUBLISH gw:<gatewayId> per conn:<userId>
      ▼
Browser ◄── WebSocket ◄── Gateway (8080) ◄── SUBSCRIBE gw:<its pod name>
```

| Service | Port | Entry point | Role |
|---|---|---|---|
| Gateway | 8080 | `gateway.GatewayMain` | HTTP API, WebSocket push, sessions, `conn:` registry |
| User Preference | 8081 | `service.impl.UserPreferenceServiceMain` | Accounts, prefs, subscription index |
| Game State | 8082 | `service.impl.GameStateServiceMain` | Game state reads **only** |
| Event Processing | 8083 | `service.impl.EventProcessingServiceMain` | Validate, Lua accept, outbox, produce, retry loop |
| Stage 1 | 8085 | `service.impl.Stage1WorkerMain` | game-events → one message per user |
| Stage 2 | 8086 | `service.impl.Stage2WorkerMain` | notifications → history + the user's gateways |
| Notification | 8087 | `service.impl.NotificationServiceMain` | History reads |
| Simulator / ESPN Poller | — | `simulator.*` | Event sources |

**Stack:** Java 17 · Maven · Javalin 6.1.6 · Jedis 4.4.7 · Gson · jbcrypt ·
kafka-clients 4.3.1 · Strimzi 1.2.0 (Kafka 4.3.1, KRaft) · Testcontainers 2.0.5
**Size:** ~4,000 lines Java · one 1,851-line `index.html` (vanilla JS, no framework, no build step) · 194 tests

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
| TOCTOU race | **Fixed** — `EventProcessingServiceImpl` runs one Lua script: id check, `seq` check, then claim + state write, atomically. Exactly the fix suggested. `EventProcessingBurstTest` (50 concurrent events → 50) runs on every push. |
| `GatewayMain` monolith | **Fixed** — now 157 lines; `EspnProxyHandler` (103) and `GameListingHandler` (54) extracted. |
| Inline Redis key strings | **Fixed** — `util/RedisKeys.java` centralizes them (the last inline `user:` / `username:` keys moved there in Phase 2). |
| Model field Javadoc | **Fixed** — e.g. `model/GameEvent.java` documents every field. |

Earlier-stage feedback also addressed: Stage 2's "Event Processing querying User
Preference on every event will bottleneck at Super Bowl scale — use pub/sub
fan-out and cache preferences" is handled by the two-stage Kafka fan-out (Stage 1
finds the audience, Stage 2 delivers) plus the `sub:*` inverted index.

**Do not re-fix these.** If a future task sounds like one of them, verify first.

---

## Open problems (found by review, not by the professor)

Phase 2 and Phase 3.1 fixed most of the review's list (see `DESIGN.md`,
"Security and correctness still to fix"). Still true of the code on disk:

1. **Accounts live in Redis** — `make down` deletes them. Postgres, Phase 3.2.
2. **Team ids are bare abbreviations** — `sub:team:BOS` is the Celtics, the Red
   Sox, and the Bruins. League-qualified ids in 3.2.
3. **An early "final" from ESPN** becomes the bar for the rest of the game; the
   clock heals cover only the same period. Never observed.
4. **Network policies need an engine on EKS** — k3s enforces them; EKS ignores
   them unless a policy engine is enabled (Phase 5).

---

## Current state of the repo

- **Git:** private repo at `github.com/boboborenstein-collab/sports-notifier`, branch
  `main`. The first commit is the course code exactly as submitted; keep it
  that way. (The course version originally lived at
  `Yeshiva-University-CS/Borenstein_Jacob_800755097`.)
- **Layout:** everything at the repo root: `pom.xml`, `src/`, `Dockerfile`,
  `Makefile`, `scripts/`, `deploy/helm/sports-notifier/`, `.github/workflows/`.
  One shaded JAR; `app.Launcher` reads `SERVICE` and runs that service's main.
- **Test:** `make test`. It starts a throwaway Redis on `localhost:6379` (the
  tests use DB 1 and flush it) and removes it afterwards. Kafka tests start a
  broker through Testcontainers, so Docker must be running.
- **Run locally (k3d):** `make cluster` once, then `make up` (build image,
  import into k3d, Helm install). Open `http://localhost:8080`. Also
  `make simulate`, `make smoke` (end-to-end check), `make logs`, `make down`.
  `make down` deletes the cluster and every account with it until Phase 3.2.
- **CI:** GitHub Actions runs the tests, then `make cluster up smoke` on a
  throwaway k3d cluster, on every push and pull request.
- **Phase 2 (Correctness and security) is merged** (PR #2, 2026-10-02). Fixes
  from 60 hours of recorded ESPN data are in PR #4. The by-hand live-game
  check is still issue #3.
- **Phase 3 is split into 3.1–3.4** (`DESIGN.md` build order). **3.1 Kafka
  pipeline is built** on branch `phase-3-1-kafka` (PR #6): spec
  `docs/superpowers/specs/2026-10-06-phase-3-1-kafka-pipeline-design.md`, plan
  `docs/superpowers/plans/2026-10-06-phase-3-1-kafka-pipeline.md`. The API spec
  for the new UI is final and on main (`docs/api/`, PR #5).
- **Next step: Phase 3.2 (Postgres)**, starting with its brainstorm. The course
  UI is allowed to break there (no adapter endpoints); the new UI is 3b.
- **New in Phase 3.1:** Strimzi is installed by `make cluster`; the broker and
  topics are resources in the chart (`templates/kafka*.yaml`). The accept
  script answers `pending` while a game has an unsent play in `outbox:<gameId>`.
  Ids after the script are `<gameId>:v<version>`. Game ids are
  `<league>-<espnId>` (`feed/GameIds`). After switching between branches that
  change the pipeline, rebuild the laptop cluster (`make down && make cluster up`).
- **New in Phase 2:** events carry `seq` (game progress as one number;
  `feed/Seq.java`); the poller is `feed/Poller.java` behind `feed/ScoreFeed`
  (ESPN adapter `EspnScoreFeed`, HTTP in `EspnClient`); `GAME_SYNC` saves
  state without alerting; `make up HELM_ARGS="--set espnPoller.enabled=true"`
  turns the poller on; `scripts/espn-check.sh` checks ESPN reachability.
- **Study quizzes:** `docs/study/phase-<N>-quiz.md`, one per phase. Write it
  at the end of each phase; quiz the user only when they ask.

---

## Where this is going

The target is a redesign of the spring 2026 course project, not a rewrite and
not a new product. Full rationale is in `DESIGN.md`. Locked:

- **Apache Kafka via Strimzi.** One broker on the laptop, three in the cloud
  (one per availability zone, RF 3, `min.insync.replicas=2`). Two topics:
  `game-events` hashed by `gameId` (12 partitions), `notifications` hashed by
  `userId` (48). Not Redis Streams, not Redpanda, not MSK, not least-busy lane
  assignment.
- **Lua script stays the score authority and runs before the Kafka produce.**
  The topic contains only accepted plays.
- **Postgres** is the source of truth for accounts, password hashes,
  preferences, and notification history. **Redis** is sessions, live score,
  the `sub:*` cache, and pub/sub to gateways. Redis is rebuildable from
  Postgres.
- **No leader election in the Java services.** A dead worker is a Kafka
  rebalance. A dead cloud broker is Kafka's own leader election. A dead Redis
  or Postgres pod is a Kubernetes restart; no Sentinel, no replicas.
- **Kubernetes everywhere, one Helm chart.** k3d on the laptop, EKS on AWS.
  One Docker image; the `SERVICE` env var picks the main. Not Compose on a VM,
  not ECS, not Fly.io, Railway, Hetzner, Cloud Run, Lambda, or Cloudflare
  Workers.
- **AWS on demand, not always on.** Terraform `apply` / `destroy`. Postgres is
  dumped to S3 before destroy and restored after apply; Redis is rebuilt from
  Postgres; Kafka starts empty. Avoid NAT gateways.
- **KEDA scales workers on Kafka lag**, not CPU. Max replicas = partition count.
  Supporting feature, not the headline.
- **Notifications route to the gateway holding the socket** (`conn:<userId>` →
  `gw:<gatewayId>`), not a `notify:*` broadcast.
- **Proof before scale claims:** recorded-game replay, a correctness checker
  (every subscriber gets every accepted play exactly once, in order), a chaos
  suite, and OpenTelemetry traces through Kafka headers.
- **Notification catalog is decided** (`DESIGN.md` decision 15): NFL, NBA,
  MLB, NHL; alerts are moments, not every score (no per-basket NBA alerts);
  switches per follow with defaults, most specific follow wins; every in-game
  alert carries the score; a team follow covers every event in its team's
  games, opponent scores included; league follows get a daily summary; one
  push per moment, but the final is always its own push; deterministic ids; v1 is scoreboard-only,
  play-by-play and player alerts later. It replaces `GAME_START` /
  `SCORE_UPDATE` / `GAME_END`. Do not reopen the tables unless the user asks.
- **ESPN** is the only source for now, behind the `ScoreFeed` interface. No
  paid feed, no provider comparison (dropped 2026-10-02). Phase 2 checks
  whether ESPN answers from a data-center IP; another feed is a later version.
- **New UI is phase 3b**, frontend only, in `web/`, built against the API
  spec in `docs/api/`. It must not add its own database, auth, or server.
- Hybrid fan-out (deliver small audiences inside Stage 1) and Kafka
  transactions are later, only if measurement or the checker says so.

Build order is in `DESIGN.md`: lock the plan and git init, foundation on k3d,
correctness, the new pipeline (Postgres + Kafka + routing), proof tools, AWS,
evidence, push. Everything before AWS runs on the laptop.

### AI features
The next phase after this redesign, not part of it. **Ask before designing.**
Any LLM work here should load the `claude-api` skill first and use current
Claude models. It should be its own Kafka consumer group so it cannot slow
delivery.

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

## Workflow

- The user works with the Superpowers plugin. `DESIGN.md` is the spec. Per-phase
  specs in `docs/superpowers/specs/` and plans in `docs/superpowers/plans/` add
  detail and must not contradict it; if one needs to, update `DESIGN.md` first.
- Brainstorming should only ask about what `DESIGN.md` does not already decide.
- Core distributed code (Lua script, Kafka producers/consumers, dedup, gateway
  routing, the checker) is built inline in small steps the user reads.
  Boilerplate (Dockerfile, Helm, Terraform, CI) can go to subagents.
- The user is new to Kubernetes, Helm, Terraform, Strimzi, KEDA, and
  OpenTelemetry. Explain what a piece does in this project before using it as
  shorthand.
- The goal is a distributed-systems project the user can defend in an
  interview. After each phase, offer to quiz them on it.
- Phase 1 is packaging only; bugs found during packaging go to the "Deferred
  to Phase 2" list in
  `docs/superpowers/specs/2026-09-29-phase-1-foundation-design.md`, not fixed
  in place.

## Open decisions

None on architecture. `com3810/` was flattened to the repo root in Phase 1. Services
stay separate processes, deployed by one Helm chart. Postgres, Kafka,
Kubernetes, and on-demand AWS are decided. Do not propose Fly.io, Railway,
Redpanda, Redis Streams as the log, ECS, or always-on hosting unless the user
asks.
