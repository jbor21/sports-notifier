# Sports Notification App — Agent Context

Read `DESIGN.md` first. It is the decision record for the redesign: what the
semester project was, what we are changing it into, and every tradeoff already
made. This file is the short brief for working in the repo. If the two
disagree, `DESIGN.md` wins. Do not reopen a locked decision unless the user
asks. If `CLAUDE.local.md` exists (git-ignored), read it too: it holds the user's own context. Last updated 2026-10-26 (Phase 5 built and checked live on EKS, PR #58; Phase 6 next).

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
by one Helm chart on k3d (laptop) and on EKS (AWS, up only on demand: `make aws-up` / `make aws-down`),
with two copies each of the gateway, Stage 1, and Stage 2.
**Kafka** (Strimzi, one broker locally, three on AWS, one per zone) is the event log; **Postgres** (one pod)
holds accounts, follows, and notification history; **Redis** holds the live score,
the outbox, sessions, the `sub:*` sets (rebuilt from Postgres), the schedule cache
(`sched:`), the unread badge cache, and pub/sub to gateways.

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
| Replay / Checker | — | `simulator.ReplayMain`, `tools.Checker` | Recorded games through the real poller; the correctness checker (one Job per run) |
| Observability | 4318, 3000 | `grafana/otel-lgtm` pod (`lgtm`) | OpenTelemetry Collector, Prometheus, Tempo, Grafana; Strimzi's Kafka exporter for lag |

**Stack:** Java 17 · Maven · Javalin 6.1.6 · Jedis 4.4.7 · Gson · jbcrypt ·
kafka-clients 4.3.1 · Strimzi 1.2.0 (Kafka 4.3.1, KRaft) · PostgreSQL 17 · HikariCP ·
Flyway · Testcontainers 2.0.5 · web: Vite 8 · React 19 · TypeScript 5 · React Router ·
SWR · vite-plugin-pwa (Workbox) · Vitest · Playwright · pnpm 12 · Node 24 ·
OpenTelemetry Java 1.66.0 (manual SDK) · grafana/otel-lgtm 0.35.0 · AWS: Terraform ≥ 1.10 (providers
aws 6.70.0, cloudflare 5.28.1; modules vpc 6.7.3, eks 21.29.0, eks-pod-identity 2.10.0) · EKS 1.35 ·
Traefik chart 42.1.3 · amazon/aws-cli 2.37.10 (restore) · AWS CLI v2 with SSO on the Mac
**Size:** ~12,600 lines Java · ~4,000 lines TypeScript in `web/` (plus ~2,600 CSS from the v0
design) · 737 Java tests · 189 web unit tests · 2 PWA and 1 end-to-end browser tests

All Redis key prefixes live in `util/RedisKeys.java` — use them, don't inline strings.

---

## Course history (context only)

The professor's review flagged exactly two problems:

1. **A TOCTOU race** in dedup/staleness — under a 50-event concurrent burst,
   game score came out 43 instead of 50. Suggested fix: a Lua script making
   dedup + staleness + update atomic.
2. **Minor code structure** — `GatewayMain` was a ~200-line monolith, Redis key
   strings were inline, model classes lacked field-level Javadoc.

### Both have already been fixed in the current code

This is important, because the feedback describes an **older snapshot**
than what's on disk. Verified against the source:

| Problem | Status in current code |
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

---

## Current state of the repo

- **Git:** repo at `github.com/jbor21/sports-notifier`, branch
  `main`. The first commit is the course code exactly as submitted; keep it
  that way. (The course version originally lived in the course's private GitHub
  organization.)
- **Layout:** everything at the repo root: `pom.xml`, `src/`, `Dockerfile`,
  `Makefile`, `scripts/`, `deploy/helm/sports-notifier/`, `.github/workflows/`, `web/`
  (the Sideline UI). One shaded JAR; `app.Launcher` reads `SERVICE` and runs that
  service's main. The image also holds the built UI in `/app/web`, which the gateway
  serves at `/`.
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
  `make replay SET=evening SPEED=10` plays recorded games; `make check SET=ci SPEED=60`
  runs the correctness checker (`KEEP=1` keeps its users, `FAULT=srem|dup|follow`
  injects one fault), `make check-selftest` proves each fault is caught, and
  `make check-clean` removes leftover synthetic users. `make grafana` serves the Pipeline
  dashboard on `localhost:3000`; `make trace-check` proves one trace spans every service.
  `make chaos [SCENARIO=…]` runs
  the chaos suite (eight checked replays, each with one fault; reports in `target/chaos/`).
  `make aws-up TAG=pr<N>`, `make aws-down`, `make aws-status`, `make aws-backup`: the same app on
  AWS (Phase 5; `aws sso login --profile sports` first; run aws-up/aws-down under `caffeinate -i`).
  `make down` deletes the cluster but not accounts: Postgres keeps its data in
  `~/.sportsnotifier/postgres` on the Mac, mounted into k3d by `make cluster`
  (laptop password fixed in `values-local.yaml`). `scripts/persistence-check.sh`
  proves it. `make e2e` drives the UI in Chromium against the cluster.
- **UI dev:** in `web/`, `pnpm dev` (Vite on :5173, proxying `/api/v1` and the socket
  to the cluster at :8080) or `pnpm dev:mock` (Prism on :4010 behind the same proxy,
  with a `sid` cookie added; no socket). `pnpm test`, `pnpm typecheck`,
  `pnpm format:check`, `pnpm types:check` (types generated from `docs/api/openapi.yaml`;
  never edit `src/api/schema.d.ts` by hand), `pnpm test:pwa`.
- **CI:** GitHub Actions runs the Java tests and, in parallel, the `web` job (format,
  types, unit tests, build, PWA update test); then `make cluster up smoke check trace-check e2e`
  (the checker on the `ci` set at 60×, then the trace check) on a throwaway k3d cluster, on
  every push and pull request, after freeing the runner's disk (the observability image
  otherwise tips the node into eviction). A failed browser run uploads Playwright traces.
- **Phase 2 (Correctness and security) is merged** (PR #2, 2026-10-02). Fixes
  from 60 hours of recorded ESPN data are in PR #4. Issue #3 (live-game
  check) closed 2026-10-07 as covered by the recordings and the smoke test's
  WebSocket check; the full live check with the new UI is issue #36.
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
- **Phase 3b (new UI) is merged** (PR #32, 2026-10-09): spec
  `docs/superpowers/specs/2026-10-08-phase-3b-ui-design.md`, plan
  `docs/superpowers/plans/2026-10-08-phase-3b-ui.md`. The live-game check (from #3)
  was waived for the merge and is issue #36.
- **Phase 4 is split into 4.1–4.3** (`DESIGN.md` build order). **4.1 (replay and
  checker) is merged** (PR #46, 2026-10-09): spec `docs/superpowers/specs/2026-10-09-phase-4-1-replay-checker-design.md`,
  plan `docs/superpowers/plans/2026-10-09-phase-4-1-replay-checker.md`; closed #16 and #18.
  Alert waves 2 and 3 moved to Phase 7. Replayed pushes reaching real accounts is #47
  (decide before Phase 6).
- **Phase 4.2 (chaos suite on k3d) is merged** (PR #53, 2026-10-13): spec
  `docs/superpowers/specs/2026-10-11-phase-4-2-chaos-design.md`, plan
  `docs/superpowers/plans/2026-10-11-phase-4-2-chaos.md`; closes #9, #10, #12, #17, #31, #44,
  #48, #49. All eight scenarios passed on the laptop and in the Chaos workflow. A Redis crash
  losing dedup claims (a duplicate push) is #52, after Phase 6; the review's six deferred
  minors are #54.
- **Phase 4.3 (traces and metrics) is merged** (PR #55, 2026-10-14), so **Phase 4 is
  complete**: spec
  `docs/superpowers/specs/2026-10-13-phase-4-3-observability-design.md`, plan
  `docs/superpowers/plans/2026-10-13-phase-4-3-observability.md`; closed #54. Evidence
  (dashboards during `stage1-crash` and `broker-crash`, a crash trace) is in
  `docs/evidence/phase-4-3/`. The review's six deferred minors are #56.
- **Issue #36, the live check, passed and is closed** (2026-10-21; results in the issue):
  the UI against a real NHL game with real ESPN data, and the remaining by-hand steps
  (switch-off, sleep, live look) on simulated games. It found the hidden-tab socket
  cycling, fixed by PR #57 (the ping clock in a Web Worker).
- **Phase 5 (AWS) is built and checked live on EKS** (PR #58, 2026-10-26): spec
  `docs/superpowers/specs/2026-10-22-phase-5-aws-design.md`, plan
  `docs/superpowers/plans/2026-10-23-phase-5-aws.md`; closes #51.
- **Next step: Phase 6 (evidence):** the chaos suite on EKS, KEDA on lag, a load test. Its first
  EKS session also watches a live game through the poller on AWS (moved from Phase 5). Decide
  #47 (replayed pushes reach real accounts) first. Other deferred minors are issues #19–#27,
  #35, #37–#43, #45, #50, #56.
- **New in Phase 5:** two Terraform stacks. `infra/base` is never destroyed: ECR, the dump bucket,
  the ACM certificate for `sports.<domain>`, the GitHub OIDC role, a $25 budget. `infra/cluster`
  is what `make aws-up` / `make aws-down` cycle: a VPC with three public subnets and no NAT, EKS
  1.35 with 3 × t3.large (one per zone), a Network Load Balancer that ends TLS and passes the
  client address to Traefik (NodePorts 30080/30443) by proxy protocol, and the Cloudflare DNS
  record (DNS only). No load-balancer controller, so `destroy` removes the NLB. `make aws-up`
  (`scripts/aws-up.sh`): checks the SSO session and that the image tag is in ECR *before*
  `terraform apply` (the tag is pinned to its ECR digest, so a re-run after a new push rolls out),
  then the gp3 storage class, Strimzi, Traefik (`deploy/aws/`), an ESPN check
  Job from inside the cluster (refused → the poller is turned off with a WARNING), and the chart
  with `values-aws.yaml`; safe to re-run (`No changes`). `make aws-down`: `pg_dump` in the pod,
  checks (parses, has users), upload to `dumps/incoming/`, size check, then a copy over
  `latest.dump`, all before anything is destroyed (`SKIP_DUMP=1` is refused while
  `data-postgres-0` exists); delete the Kafka topics
  while their operator lives, uninstall, delete the claims, `terraform destroy`, then the leftover
  sweep (`make aws-status`, `scripts/aws-leftovers.sh`: exit 0 only on "nothing billing"). Postgres
  restores on first boot: init container `fetch-dump` (Pod Identity, S3 read) and
  `10-restore.sh`; the marker `restore-done` at the volume root means "done, never refetch"; no
  marker wipes PGDATA and retries; only a 404 starts empty (an empty dump file fails). The vpc-cni policy engine is on, so EKS
  enforces the network policies. `ck_` names need the checker's key (#51; Secret `checker-key`).
  CI pushes `pr<N>`, `pr<N>-<sha7>` and `<sha7>` on main to ECR through OIDC (no stored keys).
  kubectl context `sports-notifier-aws`; scripts pass it explicitly. `make smoke` and `make check`
  run against AWS with `BASE_URL=https://sports.<domain> KUBE_CONTEXT=sports-notifier-aws`.
  Setup is in `docs/aws-setup.md`. Live
  results (2026-10-24/26): smoke, round trip, corrupt-dump, and the checker (`ci`, 60×, 5,128
  pushes) pass on EKS; about 25 min up, 15 min down, about $0.45 an hour while up.
- **New in Phase 4.3:** one `grafana/otel-lgtm` pod (`observability.enabled`, on by default;
  data in the pod, lost on restart) and Strimzi's Kafka exporter (`kafka_consumergroup_lag`,
  scraped by the collector through the `kafka-exporter` Service). The `telemetry/` package wraps
  the OpenTelemetry SDK (manual, not the Java agent; off unless `OTEL_EXPORTER_OTLP_ENDPOINT` is
  set; each process's `service.instance.id` is its pod name). One trace per play: `poll` (root,
  starts at `detectedAt`) → HTTP header → `accept` → `traceparent` field in `outbox:<gameId>`
  (so the retry loop's `produce` after a broker outage stays in the trace) → `game-events`
  header → `fanout` (skipped on redelivery) → a header on every `notifications` message →
  `deliver` → a `traceparent` field in the `gw:` pub/sub JSON → the gateway's `send`.
  `pipeline.latency` (s, buckets to 120 s) records now − `detectedAt` at `accepted`, `stage1`
  (plays that sent pushes), `stage2`, `gateway`, inside the span so exemplars link to Tempo;
  counters `plays.accepted`, `pushes.produced`, `deliveries`, `socket.sends`. The Pipeline
  dashboard (uid `pipeline`) is in the chart; `chaos.sh` marks each scenario on it (run started,
  fault, healed, run ended) and prints a dashboard link per scenario. `make check` runs under
  `caffeinate`: a Mac that sleeps mid-run jumps Redis's clock and the `conn:` entries look expired.
- **New in Phase 4.2:** `make chaos` (`scripts/chaos.sh`, primitives in `scripts/chaos-lib.sh`)
  runs eight scenarios, each one checker run on `evening` at 60× with one fault at 40% of the
  replay: crashes of Stage 1, Stage 2, History, a gateway, the broker (SIGKILL through `crictl`
  on the k3d node, proven by exit 137; `kubectl delete` would be a polite SIGTERM), a gateway
  rolling restart under a request loop (no 5xx allowed), and Stage 1 or Stage 2 cut from Kafka
  for 60 s while keeping Redis (NetworkPolicy `chaos-cut` plus `conntrack -D` on the node,
  because k3s lets already-open connections through a new policy). A scenario passes only if
  the fault provably landed and the checker passes. The `Chaos` workflow runs it by hand
  (`workflow_dispatch`, once on main) or when a PR gets the `chaos` label. Stage 2's claim
  `notif:<msg>:<user>` holds the delivery time (Redis `TIME`, ms); the checker excuses a push
  missing from a socket only if that socket was down within 2 s of it, in Redis time
  (`Claims`, `RedisClock`; #48). Checker and replay Jobs have a time limit computed from set
  and speed, and `run-job.sh` passes the exit code through (#49). Workers log lane changes
  (`util/LaneLog`); Stage 1 drops poison counts of lanes it loses (#44). The fan-out refreshes
  its temporary sets every page and throws if one vanished (#17). The gateway stops renewals
  before removing `conn:` entries (`GatewayShutdown`, #10), re-adds a reloaded tab's entry (#9),
  and waits 5 s in `preStop` (native `sleep`; Kubernetes 1.34+) before SIGTERM (#31).
  `bash scripts/test-chaos-scripts.sh` tests the scripts' failure paths with a fake kubectl (a
  failed group read, a failed heal, an API blip during a Job, fractional `SPEED`); a fault step
  that cannot run fails its scenario, never the suite.
- **Fixed after 3.4:** #29: `Db.create` passed `socketTimeout` as a number, which the
  Postgres driver silently ignores, so no connection had a read timeout; now a string
  (`DbTest`), and `HistoryStore` aborts a statement past `HISTORY_INSERT_DEADLINE_MS`
  (10 s). #15: Stage 1 sends a play that fails 3 times for a non-I/O reason to
  `game-events-dlq` and moves on; Redis and Kafka outages are retried, never
  dead-lettered; `/health` shows `deadLettered`.
- **New in Phase 4.1:** replay sets are per-game recorded timelines in
  `src/main/resources/replay/<set>.jsonl.gz` (`ci`: 2 golden games; `evening`: 10 games, all
  four leagues), packed by `scripts/pack-replay.py` (gaps over 60 s cut to 60 s, per-game
  start offsets); `feed/RecordedScoreFeed` plays them through the real `Poller`
  (`simulator/Replay`): it primes each game's pre-game play first, then runs at `SPEED`.
  Replayed game ids are `<league>-rp<runId>-<espnId>` (run id = start time in base 36).
  The checker (`checker/`, `tools.Checker`) signs up `ck_<runId>_<n>` users through
  `/api/v1` from a seeded mix (`FollowMix`), freezes their follows (`follows.updated_at`,
  migration V2), holds a socket per user, waits for feed done + every game final + 10 s
  of zero lag in all three groups, then recomputes the expected pushes from
  `game-events` and the frozen Postgres follows (`Oracle`: same detectors and combining
  step as Stage 1, `service/impl/MomentPushes`; audience computed in Java, `Audience`, not
  read from Redis) and compares with history and every socket (`Verifier`; a push missing
  on a socket is allowed only inside a recorded disconnect and if history has it; null
  and absent fields are equal). Every check is scoped to the run's game ids; users are
  deleted afterwards (`Cleanup`). `sub:built` is a hash `{version, runId}`:
  `FollowSets.SHAPE_VERSION` (bump on any set-shape change) and Redis's `run_id`
  (#16, #18). Kafka's network policy admits `checker` (`networkPolicyPeers`).
- **New in Phase 3b:** the Sideline UI in `web/` (Vite + React), rebuilt from the
  user's v0 design (`~/projects/sports-notification-app-frontend-v0`, a
  read-only reference; its demo mode was dropped). A Dockerfile stage builds it; the
  gateway's `WebFiles` serves `WEB_DIR` from its 404 handler (app routes fall back to
  `index.html`, never under `/api/`; `/assets/*` immutable, the rest `no-cache`; a CSP
  whose `connect-src` names the request's validated host for older Safari). The
  course `index.html` and `/ui` are gone. Client core: `src/api/client.ts` (every error
  one `ApiError`, bare 500s included), `src/live/connection.ts` (websocket.md's close
  codes, backoff with jitter, ping every 25 s and on return to visible, re-subscribe
  and refetch after a reconnect; the ping's clock runs in a Web Worker, `live/ticker.ts`,
  because a hidden tab's own timers made the socket cycle, #36), `src/live/games.ts`
  (highest version wins, HTTP included), pushed notifications merged into history by
  id. Every screen needs sign-in. PWA: `src/sw.ts` precaches the app; a waiting worker
  shows "New version", and an unanswered one is applied on the next in-app navigation.
  A gateway rollout answered 504 for a few seconds (no drain delay before shutdown) until
  Phase 4.2's 5 s preStop (#31); the UI tolerates it either way.
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
Any LLM work here should load the `Codex-api` skill first and use current
Codex models. It should be its own Kafka consumer group so it cannot slow
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
