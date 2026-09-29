# Phase 1: Foundation — design

Status: draft for review, 2026-09-29. Parent document: `DESIGN.md` (build order step 1). If this spec and `DESIGN.md` disagree, `DESIGN.md` wins and this spec gets fixed.

## Goal

Run the course services, unchanged in behavior, as containers on a local Kubernetes cluster, started with one command, with tests and a real deploy check running in CI on every push.

**Phase 1 is packaging only.** No Java behavior changes. No bug fixes, even obvious ones. Every known bug is listed under "Deferred" at the end, and stays there.

### Done when

1. At the repo root, `mvn -B test` passes: the 31 course tests plus the new `Launcher` tests.
2. `make cluster up smoke` passes on the laptop, starting from no cluster.
3. By hand: open `http://localhost:8080`, sign up, follow LAL, run `make simulate`, and see a notification arrive live in the browser (WebSocket). The smoke test checks history, not the live socket, so this one is a manual check in Phase 1.
4. The GitHub Actions workflow is green on GitHub for the branch.
5. `com3810/` is gone. Course material is under `docs/course/`. The tag `course-final` points at `f6e74a1` and is pushed.

## Repo layout after Phase 1

The repo is the product, so everything lives at the root. No `sports-notifier/` subfolder.

```
/
├── DESIGN.md  CLAUDE.md  AGENTS.md  README.md
├── pom.xml
├── src/main/java/...            copied from com3810/code/src/main/java, plus app/Launcher.java
├── src/main/resources/public/   index.html, copied
├── src/test/java/...            copied tests, plus app/LauncherTest.java
├── Dockerfile  .dockerignore
├── Makefile
├── scripts/                     smoke-test.sh, simulate.sh
├── deploy/helm/sports-notifier/ the Helm chart
├── .github/workflows/ci.yml
└── docs/
    ├── course/                  course README, documents/, diagrams/, old scripts/, quickstart.sh
    └── superpowers/specs/       this file
```

`docs/course/` is a holding area. The user decides later whether to keep it. The new README and docs do not link to it.

Files are **copied** into the new layout, then `com3810/` is removed in a separate commit. The course material is **moved** with `git mv` so git records it as a move. The `course-final` tag keeps the original tree reachable either way.

## 1. Build and image

### Maven

- One `pom.xml` at the root. Java 17, exact same dependency versions as the course `pom.xml`. Nothing is upgraded in this phase.
- `groupId` `dev.sportsnotifier`, `artifactId` `sports-notifier`. Java package names do not change.
- The eight copy-pasted shade executions become one, producing `target/sports-notifier.jar` with `Main-Class: app.Launcher`.

### Launcher

`src/main/java/app/Launcher.java` reads the `SERVICE` environment variable and calls the matching `main`. The table uses method references (`GatewayMain::main`), so a renamed class is a compile error, not a runtime surprise.

| `SERVICE` | Main class |
|---|---|
| `gateway` | `gateway.GatewayMain` |
| `user-pref` | `service.impl.UserPreferenceServiceMain` |
| `game-state` | `service.impl.GameStateServiceMain` |
| `event-processing` | `service.impl.EventProcessingServiceMain` |
| `pubsub-worker` | `service.impl.PubSubWorkerMain` |
| `notification` | `service.impl.NotificationServiceMain` |
| `simulator` | `simulator.EventSimulatorMain` |
| `espn-poller` | `simulator.EspnPollerMain` |

Missing or unknown `SERVICE`: print the valid names to stderr and exit with status 1. `LauncherTest` covers three things: the set of names is exactly the eight above, an unknown name is rejected, and a missing one is rejected. The lookup and the exit are separate methods so the test never calls `System.exit`.

### Dockerfile

Two stages. A stage is a temporary image used during the build; only the last one is kept.

1. **builder** (`maven:3.9-eclipse-temurin-17`): copy `pom.xml`, download dependencies (`mvn -B dependency:go-offline`), copy `src/`, run `mvn -B package -DskipTests`. Copying `pom.xml` first means a code-only change reuses the cached dependency layer.
2. **runtime** (`eclipse-temurin:17-jre`): create a non-root user, copy the JAR to `/app/app.jar`, and set `ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]`.

`-XX:MaxRAMPercentage=75` makes the JVM size its heap to the container's memory limit instead of the host's. Tests are skipped in the image build because they need a live Redis. CI runs them as a separate step first.

`.dockerignore` excludes `target/`, `.git/`, `docs/`, `deploy/`, and `com3810/`.

## 2. Kubernetes: the Helm chart on k3d

Terms, as used here:
- A **Deployment** keeps N copies (pods) of a service running and restarts any that die.
- A **Service** is a fixed name inside the cluster, such as `http://user-pref:8081`.
- A **StatefulSet** is a Deployment for something with its own disk.
- An **Ingress** is the front door from outside the cluster.

### Cluster

`make cluster` runs `k3d cluster create sports-notifier -p "8080:80@loadbalancer"`. k3d ships with Traefik as its ingress controller. The port mapping sends `localhost:8080` on the laptop to Traefik, and Traefik sends it to the gateway. WebSockets pass through Traefik without extra configuration. `make cluster` does nothing if the cluster already exists.

### What the chart creates

| Name | Kind | Copies | Port | Reachable from outside |
|---|---|---|---|---|
| `gateway` | Deployment + Service + Ingress | 1 (setting) | 8080 | yes, via Ingress |
| `user-pref` | Deployment + Service | 1 (setting) | 8081 | no |
| `game-state` | Deployment + Service | 1 (setting) | 8082 | no |
| `event-processing` | Deployment + Service | 1 (setting) | 8083 | no |
| `notification` | Deployment + Service | 1 (setting) | 8087 | no |
| `pubsub-worker` | Deployment | 2 (setting) | 8084 | no |
| `redis` | StatefulSet + Service | always 1 | 6379 | no |
| `espn-poller` | Deployment | 0 or 1 (`espnPoller.enabled`, off locally) | none | no |
| `simulator` | suspended CronJob, used as a template | runs on demand | none | no |

Every Java workload runs the same image with a different `SERVICE`.

**Copy counts.** Copy counts are settings in the values file, not fixed numbers. Nothing scales automatically in Phase 1; KEDA arrives in Phase 5. Redis and the poller have **no copy-count setting at all**. Two Redis pods would be two unrelated databases. Two pollers send every play twice until Phase 2 fixes poller ids. The chart comments say why.

**Poller update strategy.** The poller uses `strategy: Recreate`, so an upgrade stops the old pod before starting the new one. Two pollers never overlap.

**Simulator.** The chart defines a CronJob with `suspend: true`. It never fires on its own; it holds the image and env settings in one place. `scripts/simulate.sh` stamps a one-off Job from it (`kubectl create job --from=cronjob/simulator`). Optional env overrides such as `SIM_WAVES=1` are applied with `kubectl set env --local` before creating the Job. `make simulate` calls the script with no overrides.

### Configuration wiring

The code already reads every setting from environment variables. The chart fills them in. No Java changes are needed.

| Workload | Env set by the chart |
|---|---|
| all Java | `SERVICE`, `REDIS_HOST=redis`, `REDIS_PORT=6379` |
| gateway | `GATEWAY_PORT`, `USER_PREF_URL=http://user-pref:8081`, `GAME_STATE_URL=http://game-state:8082`, `NOTIFICATION_URL=http://notification:8087` |
| user-pref / game-state / event-processing / notification | their `*_PORT` |
| pubsub-worker | `PUBSUB_WORKER_PORT=8084`, `PUBSUB_CONSUMER_NAME` = the pod's own name (Kubernetes downward API) |
| simulator, espn-poller | `EPS_URL=http://event-processing:8083`; simulator also `SIM_INTERVAL_MS`, `SIM_WAVES`; poller `ESPN_POLL_INTERVAL_MS` |

Workers write notifications through `NotificationServiceImpl` in-process, straight to Redis, so they need no notification URL.

The pod name as consumer name gives each worker copy a unique name in the Redis consumer group. Without it, every copy defaults to `worker-8084`.

### Health checks

Every HTTP service already serves `/health`. Each gets three probes against it:

| Probe | Question | Period | Failures allowed | Effect of failing |
|---|---|---|---|---|
| startup | finished booting? | 5 s | 24 (2 minutes) | pod restarted |
| readiness | can take traffic now? | 10 s | 3 | removed from its Service until it passes |
| liveness | stuck? | 20 s | 3 | pod restarted |

Readiness and liveness only start after startup passes, so a slow JVM boot is never killed as "stuck." The startup window is a values setting. The poller and simulator have no HTTP port and no probes. Redis uses `redis-cli ping` for readiness and liveness.

### Redis

A StatefulSet written in this chart using the official `redis:7.4` image, not a third-party Redis chart. Bitnami's chart is more than this needs, and its free image catalog changed in 2025. It runs `redis-server --appendonly yes` on a 1 GiB PersistentVolumeClaim. k3d's built-in `local-path` storage provides the disk.

Data survives a Redis pod restart. **Data does not survive `make down`**, because deleting the cluster deletes the disk. Accounts are lost on cluster delete until Postgres arrives in Phase 3. This is expected.

### Resources

Each Java container requests 256 MiB of memory and is limited to 512 MiB. CPU is requested at 50m with no CPU limit. All of these are values settings. Eight JVMs at the limit is about 4 GiB, which fits on the laptop.

### Values files

- `values.yaml` holds chart defaults.
- `values-local.yaml` holds the laptop settings: poller off and small resources. Phase 5 adds `values-aws.yaml`; the chart templates stay the same.

The image is `sports-notifier:<tag>` with `imagePullPolicy: IfNotPresent`. The image is imported straight into k3d, so Kubernetes must not try to pull it from a registry.

## 3. Everyday commands and CI

### One-time laptop setup

1. Open OrbStack once so the `docker` command is on the PATH.
2. Run `brew install k3d helm kubectl`.

The README lists both steps.

### Makefile

| Target | Does |
|---|---|
| `make cluster` | Creates the k3d cluster if it does not exist |
| `make up` | Builds the image, imports it into k3d, runs `helm upgrade --install ... --wait --timeout 5m` |
| `make simulate` | Runs `scripts/simulate.sh` |
| `make smoke` | Runs `scripts/smoke-test.sh` |
| `make test` | Starts a throwaway `redis:7.4` container on port 6379, runs `mvn -B test`, removes the container even if tests fail. Fails clearly if 6379 is already taken. |
| `make logs` | Prints pod status and recent logs for every workload |
| `make down` | Deletes the k3d cluster |

**Image tag.** The tag is `git rev-parse --short HEAD`. When the working tree has uncommitted changes, it gets a `-dirty-<unix time>` suffix. Every build then has a new tag, so `helm upgrade` always rolls pods onto the new code. A fixed `latest` tag can leave old code running.

### Smoke test: `scripts/smoke-test.sh`

1. Wait until every Deployment and the Redis StatefulSet report ready. Give up after 3 minutes.
2. Through `http://localhost:8080`, `POST /api/signup` a fresh user (random name) and keep the session cookie.
3. `PUT /api/preferences`: follow team `LAL`, with notification types `["GAME_START"]`.
4. Run `scripts/simulate.sh` with `SIM_WAVES=1`.
5. Poll `GET /api/history` once a second for up to 60 seconds. Pass when an entry with `gameId` `game-LAL-BOS` and a message containing `has started` appears. History entries have no event-type field. The user enabled only `GAME_START`, so any entry for that game must be the game start, and the message check confirms it.
6. On any failure, run `make logs` and exit non-zero.

**Why LAL and GAME_START.** `game-LAL-BOS` is the first game of wave 1, and LAL is its home team. The simulator tags `GAME_START` with the home team. `SCORE_UPDATE` is tagged with a randomly chosen scorer, and today's fan-out matches only the tagged team (`PubSubWorkerImpl.java:106`). A test that relied on score events would pass or fail on a coin flip. `GAME_START` is also the first event sent, so the test finishes without waiting for the game.

### CI: `.github/workflows/ci.yml`

Runs on every push and every pull request, on `ubuntu-latest`.

- **Job `test`:**
  - Java 17 (Temurin) with Maven cache, and a `redis:7.4` service container on port 6379.
  - Runs `mvn -B test`.
- **Job `deploy-check`** (`needs: test`):
  - Installs a pinned k3d and Helm. `kubectl` is preinstalled.
  - Runs `make cluster up smoke`, the same commands used on the laptop.
  - On failure, runs `make logs` so the reason is in the CI output.

Expected run time is about 5 to 6 minutes. Nothing is pushed to a registry. Pushing to ECR is Phase 5.

## 4. Order of work

The app works at the end of every step.

1. Tag `f6e74a1` as `course-final` and push the tag.
2. Create the root `pom.xml`, copy `src/` in, add `Launcher` with its test, and start the `Makefile` with the `test` target. `make test` passes.
3. Add `Dockerfile` and `.dockerignore`. The image builds. Running it with a bad `SERVICE` exits 1 with the list of names.
4. Add the Helm chart, the remaining `Makefile` targets, and the scripts. `make cluster up smoke` passes, and the manual browser check passes.
5. Add the CI workflow. It is green on GitHub.
6. Move the course material into `docs/course/` with `git mv`, then delete the rest of `com3810/`. Both happen in one commit, separate from everything else.
7. Update the docs:
   - `CLAUDE.md` and `AGENTS.md`: new paths, the `make` commands, the root build command, and the rule "Phase 1 is packaging only; bugs go to the Phase 2 list."
   - A short root `README.md`: what the project is and how to run it locally.
   - `DESIGN.md`: one line marking Phase 1 done. No decisions change.

Boilerplate (Dockerfile, chart, Makefile, CI) may be delegated to subagents per `CLAUDE.md`. The user reads each piece with an explanation of what it does.

## Known gaps accepted in Phase 1

- **Worker restarts.** A restarted worker gets a new pod name, so entries the old worker had claimed stay in the Redis pending list forever. This is the existing "no pending-entry recovery" bug. Kafka replaces this mechanism in Phase 3.
- **Accounts and cluster delete.** Accounts and preferences are lost when the cluster is deleted. Fixed by Postgres in Phase 3.
- **No network policies.** Nothing stops one pod from calling another's internal port. Internal services are not reachable from outside the cluster, only from inside it.
- **Smoke test coverage.** The smoke test does not cover the WebSocket. That check is manual in Phase 1, and the Phase 4 checker covers delivery properly.

## Deferred to Phase 2

Recorded here so they are not lost. Phase 1 fixes none of them.

1. **Fan-out matches only the scoring team.** This is the first Phase 2 task, done test-first.
   - First failing test: a user following BOS is notified when LAL scores in the LAL–BOS game.
   - The fix matches home and away teams, not just the tagged team.
   - After the fix, tighten the smoke test to also require a score notification. The test user follows BOS as well, turns on `SCORE_UPDATE`, and the test waits for a `game-LAL-BOS` entry whose message contains `scores!`. This works because history entries carry no type field.
2. **Notification catalog brainstorm, held before Phase 2 starts.** This is a separate design session.
   - Each sport gets its own set of alert types that users switch on and off. The sports are NBA, NFL, MLB, NHL, and possibly more.
   - Alerts are based on moments, not every score. For example, NBA gets halftime, end of quarter, and close games instead of every basket. NFL can alert on every touchdown because scores are rare.
   - Player-specific alerts are a possible later addition.
   - The catalog replaces today's three generic types (`GAME_START`, `SCORE_UPDATE`, `GAME_END`), so Phase 2's task order may shift once it is decided.
3. **The existing bug list**, unchanged from `CLAUDE.md` and `DESIGN.md`:
   - Signup get-then-set race (needs `SETNX`).
   - Fan-out `SISMEMBER` round trip per subscriber.
   - No pending-entry recovery.
   - Session cookie missing `Secure` and `SameSite`.
   - 4-character password minimum; login unthrottled.
   - Poller random UUID ids and local clock timestamps; snapshot saved before the POST succeeds; `HashMap` snapshots.
   - Game keys never expire.
   - `GameStateService.updateGameState` used only by tests.
   - No service-to-service auth or network policies.
   - Inline `user:` / `username:` keys in `UserPreferenceServiceMain`.
4. **Services crash at startup if Redis is not reachable yet** (found in Phase 1). On a fresh deploy, pubsub-worker exits with `UnknownHostException: redis` and Kubernetes restarts it until Redis is up. This heals itself, but it's noisy. Fold it into Phase 2's retry work: connect with backoff at startup instead of crashing.
