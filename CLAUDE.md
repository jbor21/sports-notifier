# Sports Notification App — Agent Context

Read `DESIGN.md` first. It is the decision record for the redesign: what the
semester project was, what we are changing it into, and every tradeoff already
made. This file is the short brief for working in the repo. If the two
disagree, `DESIGN.md` wins. Do not reopen a locked decision unless the user
asks. Last updated 2026-10-08.

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
leagues / specific games, turn alert switches on or off per follow (the catalog of
`DESIGN.md` decision 15), and receive live push notifications over WebSocket as
those moments occur. Stage 1's detectors turn each game's snapshots into decision 15's
wave 1 alerts (start, scores with the NFL try, period ends, lead change, close game
late, overtime, shootout, delays, overturns, final), one push per user per moment.
The same socket carries live scores for the games a tab shows (`game_update`) and the
unread badge (`unread_count`); `/games` lists yesterday through 7 days ahead.

## Architecture

Separate JVM processes, one Docker image (`SERVICE` picks the main), deployed
by one Helm chart on k3d. **Kafka** (Strimzi, one broker locally) is the event
log; **Postgres** (one pod) holds accounts, follows, and notification history;
**Redis** holds the live score, the outbox, sessions, the `sub:*` sets (rebuilt
from Postgres), the schedule cache (`sched:`), the unread badge cache, and pub/sub
to gateways.

```
Simulator / ESPN Poller
      │ POST /events
      ▼
Event Processing (8083) ──► atomic Lua: duplicate · pending · newer (seq, two heals)
      │                     → state write + version + outbox:<gameId> + games:start
      │                     → PUBLISH live:<gameId> {version, play} (score cards)
      │ produce game-events (key = gameId, 12 lanes); confirmation clears the outbox;
      │ a retry loop re-sends leftovers
      ▼
Stage 1 (8085, group stage1) ──► detectors over det:<gameId> (version dedup + memory);
      │                          waits while sub:built is missing; audience: most
      │                          specific follow wins (sub:<target>:<ALERT>, :ANY)
      │ produce notifications (key = userId, 48 lanes), one per user per moment, final last
      ├──────────────────────────────────────────────┐
      ▼                                              ▼
Stage 2 (8086, group stage2) ──► one Lua script:   History (8088, group history)
      │   notif:<msg>:<user> claim + PUBLISH        ──► batched INSERT into Postgres
      │   gw:<gatewayId> per conn:<userId>,             (created_at = Kafka timestamp)
      │   + unread:<userId> INCR → unread_count
      ▼
Browser ◄── /api/v1/ws ◄── Gateway (8080) ◄── SUBSCRIBE gw:<its pod name>
                                          ◄── SUBSCRIBE live:<gameId> while a tab shows it
```

| Service | Port | Entry point | Role |
|---|---|---|---|
| Gateway | 8080 | `gateway.GatewayMain` | HTTP API, `/api/v1/ws` (`LiveSocket`), reference data, sessions, `conn:` registry, per-game subscriptions |
| User Preference | 8081 | `service.impl.UserPreferenceServiceMain` | Accounts and follows (Postgres), the `sub:*` sets, their rebuild, game-follow expiry |
| Game State | 8082 | `service.impl.GameStateServiceMain` | `/games`, `/games/{id}`: reads `game:` + `sched:` **only** |
| Event Processing | 8083 | `service.impl.EventProcessingServiceMain` | Validate, Lua accept, outbox, produce, retry loop |
| Stage 1 | 8085 | `service.impl.Stage1WorkerMain` | game-events → one message per user |
| Stage 2 | 8086 | `service.impl.Stage2WorkerMain` | notifications → the user's gateways |
| Notification | 8087 | `service.impl.NotificationServiceMain` | History reads, seen, clear (resets the badge), 30-day cleanup (Postgres) |
| History | 8088 | `service.impl.HistoryWriterMain` | notifications → Postgres history, batched |
| Simulator / ESPN Poller | — | `simulator.*` | Event sources; the ESPN poller also caches the schedule |

**Stack:** Java 17 · Maven · Javalin 6.1.6 · Jedis 4.4.7 · Gson · jbcrypt ·
kafka-clients 4.3.1 · Strimzi 1.2.0 (Kafka 4.3.1, KRaft) · PostgreSQL 17 · HikariCP ·
Flyway · Testcontainers 2.0.5
**Size:** ~9,000 lines Java · the course's 1,851-line `index.html` (broken since 3.2; the new UI is 3b) · 529 tests

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
| `GatewayMain` monolith | **Fixed** — now ~125 lines of wiring; routes live in `ApiV1Routes`, `GameRoutes`, `ReferenceRoutes`, and `LiveSocket`. |
| Inline Redis key strings | **Fixed** — `util/RedisKeys.java` centralizes them (the last inline `user:` / `username:` keys moved there in Phase 2). |
| Model field Javadoc | **Fixed** — e.g. `model/GameEvent.java` documents every field. |

Earlier-stage feedback also addressed: Stage 2's "Event Processing querying User
Preference on every event will bottleneck at Super Bowl scale — use pub/sub
fan-out and cache preferences" is handled by the two-stage Kafka fan-out (Stage 1
finds the audience, Stage 2 delivers) plus the `sub:*` inverted index.

**Do not re-fix these.** If a future task sounds like one of them, verify first.

---

## Open problems (found by review, not by the professor)

Phases 2 and 3.1–3.3 fixed most of the review's list (see `DESIGN.md`,
"Security and correctness still to fix"). Still true of the code on disk:

1. **An early "final" from ESPN** becomes the bar for the rest of the game; the
   clock heals cover only the same period. Never observed.
2. **Network policies need an engine on EKS** — k3s enforces them; EKS ignores
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
  broker through Testcontainers, so Docker must be running; stopping the laptop
  cluster first (`k3d cluster stop sports-notifier`) keeps the run fast.
  `KafkaTestSupport.createTopic` returns only when every partition's leader
  answers: writing to a just-created topic used to need retries, which could
  deadlock the idempotent producer (`OutOfOrderSequenceException`, CI on PR #13).
  A failed Kafka test saves the broker's log and `docker stats` to
  `target/kafka-diagnostics/`; CI uploads them.
- **Run locally (k3d):** `make cluster` once, then `make up` (build image,
  import into k3d, Helm install). Open `http://localhost:8080`. Also
  `make simulate`, `make smoke` (end-to-end check), `make logs`, `make down`.
  `make down` deletes the cluster but not accounts: Postgres keeps its data in
  `~/.sportsnotifier/postgres` on the Mac, mounted into k3d by `make cluster`
  (laptop password fixed in `values-local.yaml`). `scripts/persistence-check.sh`
  proves it.
- **CI:** GitHub Actions runs the tests, then `make cluster up smoke` on a
  throwaway k3d cluster, on every push and pull request.
- **Phase 2 (Correctness and security) is merged** (PR #2, 2026-10-02). Fixes
  from 60 hours of recorded ESPN data are in PR #4. Issue #3 (live-game
  check) closed 2026-10-07 as covered by the recordings and the smoke test's
  WebSocket check; the full live check happens with the new UI (3b).
- **Phase 3 is split into 3.1–3.4** (`DESIGN.md` build order). **3.1 Kafka
  pipeline is merged** (PR #6, 2026-10-07): spec
  `docs/superpowers/specs/2026-10-06-phase-3-1-kafka-pipeline-design.md`, plan
  `docs/superpowers/plans/2026-10-06-phase-3-1-kafka-pipeline.md`. The API spec
  for the new UI is final and on main (`docs/api/`, PR #5).
- **Phase 3.2 (Postgres) is merged** (PR #13, 2026-10-07): spec
  `docs/superpowers/specs/2026-10-07-phase-3-2-postgres-design.md`, plan
  `docs/superpowers/plans/2026-10-07-phase-3-2-postgres.md`. The course UI is
  broken from here (no adapter endpoints); the new UI is 3b.
- **Phase 3.3 (alerts, wave 1) is merged** (PR #14, 2026-10-08): spec
  `docs/superpowers/specs/2026-10-07-phase-3-3-alerts-wave-1-design.md`, plan
  `docs/superpowers/plans/2026-10-07-phase-3-3-alerts-wave-1.md`.
- **Phase 3.4 (live API) is merged** (PR #28, 2026-10-08): spec
  `docs/superpowers/specs/2026-10-08-phase-3-4-live-api-design.md`, plan
  `docs/superpowers/plans/2026-10-08-phase-3-4-live-api.md`.
- **Next step: Phase 3b (new UI)**, against the now fully served `docs/api/`.
  Deferred minors are issues #15–#27 (#15 must be fixed before Phase 4, #16 before
  Phase 5). CI: #29, HistoryWriterTest can hang while Postgres is paused (a watchdog
  dumps threads after 12 min; suspected unbounded socket write).
- **New in Phase 3.4:** `/api/v1/ws` (`gateway/LiveSocket`): `hello` with the unread
  count, 4401 without a session, ping/pong, idle close after 60 s, notifications in the
  API shape, `unread_count`, `game_update`. The accept script saves start time and line
  score, indexes `games:start`, and publishes each accepted play on `live:<gameId>`;
  gateways `SUBSCRIBE` per game (`GameSubscriptions`), wait for Redis's confirmation,
  then send the tab the current state. `/games` (Game State, through `GameRoutes`)
  serves yesterday to +7 days in the user's timezone from `game:` + `sched:`; the ESPN
  poller's `ScheduleLoop` writes `sched:` every 15 min (44 requests). One builder,
  `games/GameSummaries`, makes every `GameSummary`. `unread:<userId>` is raised by
  Stage 2's delivery script (only if present) and reset by seen/clear. Labels read
  `Final`, `Final/OT`, `End Q1`. The no-score heal waits 10 s, not 30 (ESPN showed
  0:00 for 90 s mid-period). The course's `/ws`, `/api/games`, `/api/upcoming` are gone.
- **New in Phase 3.3:** detectors in `src/main/java/alerts/` (pure functions of
  memory + play; `Detectors.standard`). Stage 1 keeps each game's memory in
  `det:<gameId>` (replaces `s1:last:`), written set-if-higher after the messages are
  confirmed. Alert ids are `<gameId>:<TYPE>[:...]`; a score's number is a counter that
  never goes down; a push's id is its headline alert's id. The audience is
  `MomentFanout` + `AlertAudience`: game ∪ (teams − game followers) ∪ (league −
  game/team followers), using `sub:<kind>:<target>:ANY`; chosen innings live in
  `…:END_OF_INNING:<n>` sets. The poller sends a play on every position change; plays
  carry period, clock, half, outs, stage, season type. A score drop is an overturn only
  after 60 s of game clock (ESPN glitches come back sooner). The simulator is a scripted
  `ScoreFeed` through the real poller, with new game ids every run
  (`<league>-sim<start>-<slot>`). `make replay-detectors` runs the ESPN recordings
  through the detectors; four golden timelines are in `src/test/resources/espn/replay/`.
- **New in Phase 3.2:** `/api/v1/auth`, `/me`, `/follows`, `/notifications`
  (`gateway/ApiV1Routes`). Follows carry decision-15 switches (catalog in
  `src/main/resources/catalog/alerts.json`, teams in `teams.json`, team ids
  `<league>-<abbr>` via `feed/TeamIds`). A follow save commits to Postgres, then
  writes `sub:<kind>:<target>:<ALERT>`; a failed Redis write deletes `sub:built`,
  and user-pref rebuilds the sets (every 30 s check, and daily). Stage 1 pauses
  (not stops) while `sub:built` is missing. History is its own consumer group
  (`history`). Schema migrations are Flyway files in `src/main/resources/db/migration/`.
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
