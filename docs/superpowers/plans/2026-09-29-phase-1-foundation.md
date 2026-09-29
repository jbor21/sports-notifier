# Phase 1: Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the unchanged course services as one Docker image on a local k3d cluster via one Helm chart, with a smoke test and CI that deploys to a throwaway k3d cluster on every push.

**Architecture:**
- The Java code is copied to the repo root.
- One shaded JAR has `app.Launcher` as its entry point. Launcher reads `SERVICE` and calls the matching `*Main`.
- A two-stage Dockerfile builds that JAR into one image.
- A Helm chart runs every service from that image, plus a Redis StatefulSet, behind k3d's built-in Traefik ingress.
- A Makefile wraps the everyday commands, and CI calls the same Makefile.

**Tech Stack:** Java 17, Maven, Javalin 6.1.6, Jedis 4.4.7 (unchanged), Docker (OrbStack engine), k3d, Helm 3, kubectl, Traefik (bundled with k3d), GitHub Actions, bash, jq.

**Spec:** `docs/superpowers/specs/2026-09-29-phase-1-foundation-design.md` (parent: `DESIGN.md`, build order step 1)

## Global Constraints

- **Phase 1 is packaging only.** No Java behavior changes and no bug fixes. The only new Java is `app.Launcher` and its test. If you spot a bug, add it to the spec's "Deferred to Phase 2" list instead of fixing it.
- Java 17. Keep the exact dependency and plugin versions from `com3810/code/pom.xml`.
- `groupId` `dev.sportsnotifier`, `artifactId` `sports-notifier`. Java package names unchanged.
- Everything lives at the repo root: `pom.xml`, `src/`, `Dockerfile`, `Makefile`, `scripts/`, `deploy/helm/sports-notifier/`, `.github/workflows/`. No `sports-notifier/` subfolder.
- Image name `sports-notifier:<tag>`, `imagePullPolicy: IfNotPresent`. The tag is `git rev-parse --short HEAD`, plus `-dirty-<unix time>` when `git status --porcelain` is non-empty.
- k3d cluster name `sports-notifier`, created with `-p "8080:80@loadbalancer"`. Helm release name `sports-notifier`.
- Kubernetes Service names are exactly `gateway`, `user-pref`, `game-state`, `event-processing`, `notification`, `redis`. The env URLs depend on these names, so they have no release prefix.
- Redis image `redis:7.4`, both in the chart and for tests.
- Redis and the ESPN poller have no copy-count setting. The poller uses `strategy: Recreate`.
- Every commit message ends with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Ask the user before any `git push` (the tag in Task 1, the branch in Task 7) and before installing tools (`brew install`).
- The user is new to Docker, Kubernetes, Helm, and k3d. When a task finishes, explain in plain words what each new file does in this project.

## Review Focus

1. **Re-running on a live cluster.** `make smoke` or `make simulate` run a second time, while an earlier simulator Job is still running, should pass or run again. Tests are in Task 5 (unique Job names) and Task 6 (second smoke run).
2. **Code change on a running cluster.** `make up` after a change should roll every pod onto the new image, not leave old code running. Test is in Task 4.
3. **`make test` failures.** When port 6379 is already taken, or a test fails, `make test` should say why and must not leave a Redis container behind. Test is in Task 1.
4. **Commands in the wrong order.** `make up` before `make cluster` should print a clear message, and `make cluster` twice should be a no-op. Test is in Task 4.
5. **Browser path through Traefik.** `/` should redirect to `/ui/`, and the WebSocket upgrade on `/ws` should work, so the browser gets live notifications. The smoke test does not cover this. It is a manual check in Task 6.

---

### Task 1: Root Maven project, copied sources, `make test`

**Files:**
- Create: `pom.xml` (from `com3810/code/pom.xml`)
- Create: `src/` (copy of `com3810/code/src/`)
- Create: `Makefile` (first target: `test`)

**Interfaces:**
- Produces:
  - `mvn -B package` writes `target/sports-notifier.jar` with `Main-Class: app.Launcher`. The class arrives in Task 2; until then the manifest points at a missing class, which is fine.
  - `make test` runs the full test suite against a throwaway Redis.

- [ ] **Step 1: Tag the course commit**

Run: `git tag course-final f6e74a1 && git show --no-patch --oneline course-final`
Expected: `f6e74a1 Course version: COM3810 sports notification system, spring 2026`. Ask the user, then `git push origin course-final`.

- [ ] **Step 2: Copy the sources**

Run: `cp -R com3810/code/src ./src && find src -name '*.java' | wc -l && ls src/main/resources/public`
Expected: `38` Java files and `index.html`.

- [ ] **Step 3: Write `pom.xml`**

Copy `com3810/code/pom.xml` to the root, then make exactly these changes:
- `groupId` becomes `dev.sportsnotifier` and `artifactId` becomes `sports-notifier`.
- Replace the eight `maven-shade-plugin` executions with one:
  - `<id>app</id>`
  - `outputFile` `${project.build.directory}/sports-notifier.jar`
  - `ManifestResourceTransformer` `mainClass` `app.Launcher`
  - Keep the same `ServicesResourceTransformer` and the same `META-INF/*.SF|DSA|RSA` excludes.

Everything else stays the same.

- [ ] **Step 4: Add the `test` target to `Makefile`**

Behavior of `make test`:
1. If `nc -z localhost 6379` succeeds, print `Port 6379 is in use (local Redis?). Stop it and re-run make test.` and exit 1.
2. Otherwise, `docker run -d --rm --name sports-notifier-test-redis -p 6379:6379 redis:7.4`.
3. Run `mvn -B test`.
4. `docker rm -f sports-notifier-test-redis`. This must happen whether tests pass or fail: use a `trap` in a single-shell recipe.
5. Exit with Maven's exit status.

Declare `.PHONY` targets.

- [ ] **Step 5: Run the suite**

Run: `make test`
Expected: `Tests run: 31, Failures: 0, Errors: 0`, `BUILD SUCCESS`, and afterwards `docker ps -a --filter name=sports-notifier-test-redis -q` prints nothing.

- [ ] **Step 6: Check both failure paths (Review Focus 3)**

1. Port taken: run `docker run -d --rm --name blocker -p 6379:6379 redis:7.4 && make test; echo "exit=$?"; docker rm -f blocker`. Expected: the port message and `exit=1`.
2. Failing test: temporarily add a failing `assertTrue(false)` to one test, run `make test; echo "exit=$?"`, then revert the test. Expected: a non-zero exit, and `docker ps -a --filter name=sports-notifier-test-redis -q` prints nothing.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src Makefile
git commit -m "Copy course code to repo root with a single shaded JAR and make test"
```

---

### Task 2: `app.Launcher`

**Files:**
- Create: `src/main/java/app/Launcher.java`
- Test: `src/test/java/app/LauncherTest.java`

**Interfaces:**
- Consumes: the eight existing `main(String[])` methods.
- Produces:
  - `@FunctionalInterface interface Launcher.MainMethod { void run(String[] args) throws Exception; }`
  - `static final Map<String, Launcher.MainMethod> SERVICES`: a `LinkedHashMap`, insertion order as below, built from method references.
  - `static Launcher.MainMethod resolve(String service)`: throws `IllegalArgumentException` for null, blank, or unknown. Matching is case-sensitive and does not trim.
  - `public static void main(String[] args) throws Exception`: `resolve(System.getenv("SERVICE"))`. On `IllegalArgumentException`, print the message to stderr and `System.exit(1)`. Otherwise run it with `args`.

Name → class, in this order:
- `gateway` → `gateway.GatewayMain`
- `user-pref` → `service.impl.UserPreferenceServiceMain`
- `game-state` → `service.impl.GameStateServiceMain`
- `event-processing` → `service.impl.EventProcessingServiceMain`
- `pubsub-worker` → `service.impl.PubSubWorkerMain`
- `notification` → `service.impl.NotificationServiceMain`
- `simulator` → `simulator.EventSimulatorMain`
- `espn-poller` → `simulator.EspnPollerMain`

Exact messages (`<list>` is `gateway, user-pref, game-state, event-processing, pubsub-worker, notification, simulator, espn-poller`):
- Null or blank: `SERVICE is not set. Valid values: <list>`
- Unknown: `Unknown SERVICE "<value>". Valid values: <list>`

- [ ] **Step 1: Write the failing tests**

```java
package app;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LauncherTest {
    private static final List<String> NAMES = List.of("gateway", "user-pref", "game-state",
        "event-processing", "pubsub-worker", "notification", "simulator", "espn-poller");
    private static final String VALID = "Valid values: " + String.join(", ", NAMES);

    @Test void serviceNamesAreExactlyTheEight() {
        assertEquals(NAMES, List.copyOf(Launcher.SERVICES.keySet()));
    }

    @Test void resolvesEveryKnownName() {
        for (String n : NAMES) assertNotNull(Launcher.resolve(n), n);
    }

    @Test void rejectsUnknownNameAndListsValidOnes() {
        var e = assertThrows(IllegalArgumentException.class, () -> Launcher.resolve("Gateway"));
        assertEquals("Unknown SERVICE \"Gateway\". " + VALID, e.getMessage());
    }

    @Test void rejectsMissingOrBlank() {
        for (String s : new String[] {null, "", "   "}) {
            var e = assertThrows(IllegalArgumentException.class, () -> Launcher.resolve(s));
            assertEquals("SERVICE is not set. " + VALID, e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -B -q -Dtest=LauncherTest test`
Expected: compilation failure, because `app.Launcher` does not exist yet. This test needs no Redis.

- [ ] **Step 3: Implement `Launcher` per the Interfaces block**

Add a one-line class comment explaining that one image runs every service and `SERVICE` picks which.

- [ ] **Step 4: Run it and watch it pass, then run the full suite**

Run: `mvn -B -q -Dtest=LauncherTest test`, then `make test`
Expected: `LauncherTest` passes 4/4, and the full suite reports `Tests run: 35, Failures: 0`.

- [ ] **Step 5: Check the packaged JAR**

Run: `mvn -B -q package -DskipTests && java -jar target/sports-notifier.jar; echo "exit=$?"`
Expected: `SERVICE is not set. Valid values: gateway, …, espn-poller` and `exit=1`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/app src/test/java/app
git commit -m "Add Launcher: SERVICE env var picks which service main runs"
```

---

### Task 3: Docker image

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`

**Interfaces:**
- Consumes: `target/sports-notifier.jar` from `mvn package`, built inside the image.
- Produces: image `sports-notifier:<tag>`. Its entrypoint is `java -XX:MaxRAMPercentage=75 -jar /app/app.jar`, and `SERVICE` selects the program.

- [ ] **Step 1: Prerequisite**

Run: `docker version --format '{{.Server.Version}}'`
Expected: a version number. If `docker` is not found, ask the user to open OrbStack once, which installs the CLI, then retry.

- [ ] **Step 2: Write the Dockerfile**

**Stage `builder`** (`FROM maven:3.9-eclipse-temurin-17 AS builder`):
1. `WORKDIR /build`
2. Copy `pom.xml`, then run `mvn -B -q dependency:go-offline`.
3. Copy `src`, then run `mvn -B -q package -DskipTests`.

**Stage runtime** (`FROM eclipse-temurin:17-jre`):
1. Create a system user and group `app`.
2. `WORKDIR /app`
3. `COPY --from=builder /build/target/sports-notifier.jar /app/app.jar`
4. `USER app`
5. `ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]`

Add short comments on two points:
- Why `pom.xml` is copied before `src` (layer caching).
- Why tests are skipped (they need Redis; CI runs them separately).

`.dockerignore` lists: `target/`, `.git/`, `docs/`, `deploy/`, `com3810/`.

- [ ] **Step 3: Build and check the image**

Run each command and compare:

| Command | Expected |
|---|---|
| `docker build -t sports-notifier:check .` | Succeeds |
| `docker run --rm sports-notifier:check; echo "exit=$?"` | `SERVICE is not set. …`, `exit=1` |
| `docker run --rm -e SERVICE=bogus sports-notifier:check; echo "exit=$?"` | `Unknown SERVICE "bogus". …`, `exit=1` |
| `docker run --rm --entrypoint id sports-notifier:check -u` | A number other than `0` (not root) |
| `docker run --rm --entrypoint sh sports-notifier:check -c 'command -v mvn javac || echo none'` | `none` |

- [ ] **Step 4: Commit**

```bash
git add Dockerfile .dockerignore
git commit -m "Add two-stage Dockerfile: one image for every service"
```

---

### Task 4: Helm chart (Redis, Java services, ingress) and cluster targets

**Files:**
- Create: `deploy/helm/sports-notifier/Chart.yaml` (`apiVersion: v2`, `name: sports-notifier`, `version: 0.1.0`, `appVersion: "phase-1"`)
- Create: `deploy/helm/sports-notifier/values.yaml`
- Create: `deploy/helm/sports-notifier/values-local.yaml`
- Create: `deploy/helm/sports-notifier/templates/_helpers.tpl`
- Create: `deploy/helm/sports-notifier/templates/java-services.yaml`
- Create: `deploy/helm/sports-notifier/templates/gateway-ingress.yaml`
- Create: `deploy/helm/sports-notifier/templates/redis.yaml`
- Modify: `Makefile` (add `cluster`, `up`, `logs`, `down`)

**Interfaces:**
- Consumes: the image from Task 3.
- Produces:
  - The Makefile targets below.
  - Label `app.kubernetes.io/part-of: sports-notifier` on every pod, and `app.kubernetes.io/name: <service>` as the selector label.
  - Helpers in `_helpers.tpl`, used by Task 5:
    - `sports-notifier.labels` (arg: name)
    - `sports-notifier.image` (arg: root context)
    - `sports-notifier.baseEnv` (arg: root; emits `REDIS_HOST=redis`, `REDIS_PORT=6379`)
    - `sports-notifier.httpProbes` (arg: dict with `port` and `probes`)
  - The `.Values.simulator` and `.Values.espnPoller` keys.

- [ ] **Step 1: Prerequisite**

Run: `k3d version && helm version --short && kubectl version --client`
Expected: all three print versions. If not, ask the user to run `brew install k3d helm kubectl`.

- [ ] **Step 2: Write `values.yaml`**

Write it exactly as below; templates read these keys.

```yaml
image:
  repository: sports-notifier
  tag: dev            # make up always overrides with the git-based tag
  pullPolicy: IfNotPresent

javaResources:
  requests: { cpu: 50m, memory: 256Mi }
  limits: { memory: 512Mi }

probes:
  startup:   { periodSeconds: 5,  failureThreshold: 24 }  # up to 2 minutes to boot
  readiness: { periodSeconds: 10, failureThreshold: 3 }
  liveness:  { periodSeconds: 20, failureThreshold: 3 }

# One Deployment per entry, named after the key. clusterService: false means no Service object.
services:
  gateway:
    replicas: 1
    port: 8080
    portEnv: GATEWAY_PORT
    clusterService: true
    env:
      USER_PREF_URL: http://user-pref:8081
      GAME_STATE_URL: http://game-state:8082
      NOTIFICATION_URL: http://notification:8087
  user-pref:        { replicas: 1, port: 8081, portEnv: USER_PREF_PORT,        clusterService: true }
  game-state:       { replicas: 1, port: 8082, portEnv: GAME_STATE_PORT,       clusterService: true }
  event-processing: { replicas: 1, port: 8083, portEnv: EVENT_PROCESSING_PORT, clusterService: true }
  notification:     { replicas: 1, port: 8087, portEnv: NOTIFICATION_PORT,     clusterService: true }
  pubsub-worker:
    replicas: 2
    port: 8084
    portEnv: PUBSUB_WORKER_PORT
    clusterService: false
    podNameEnv: PUBSUB_CONSUMER_NAME   # unique consumer name per pod in the Redis consumer group

# Always exactly one pod: two Redis pods would be two unrelated databases.
redis:
  image: redis:7.4
  storage: 1Gi

# At most one pod: two pollers send every play twice until Phase 2 fixes poller ids.
espnPoller:
  enabled: false
  pollIntervalMs: 30000

simulator:
  intervalMs: 2000
  waves: 3
```

`values-local.yaml` holds the laptop settings, explicitly: `espnPoller.enabled: false` and the same `javaResources`. Add a header comment saying Phase 5 adds `values-aws.yaml`.

- [ ] **Step 3: Write the templates**

**`java-services.yaml`:** a `range $name, $svc := .Values.services` that emits:
- A Deployment named `$name` with `replicas: $svc.replicas`.
- Container env:
  - `SERVICE=$name`
  - the `baseEnv` helper
  - `$svc.portEnv=$svc.port`
  - every `$svc.env` pair
  - if `podNameEnv` is set, that env var from `valueFrom.fieldRef.fieldPath: metadata.name`
- `containerPort: $svc.port`
- The `httpProbes` helper: startup, readiness, and liveness, all `httpGet /health` on the port.
- `resources` from `.Values.javaResources`.

When `clusterService` is true, it also emits a ClusterIP Service named `$name` on port `$svc.port`.

**`gateway-ingress.yaml`:** an Ingress with `ingressClassName: traefik` and path `/` (`Prefix`) → Service `gateway:8080`.

**`redis.yaml`:**
- Service `redis:6379`.
- StatefulSet `redis`: `replicas: 1` hard-coded, with a comment saying why. Container `redis:7.4` with args `["redis-server", "--appendonly", "yes"]`, data mounted at `/data`.
- `volumeClaimTemplates`: 1Gi, `ReadWriteOnce`. k3d's `local-path` is the default storage class, so none is named.
- Readiness and liveness probes: `exec redis-cli ping`.

- [ ] **Step 4: Static checks**

| Command | Expected |
|---|---|
| `helm lint deploy/helm/sports-notifier -f deploy/helm/sports-notifier/values-local.yaml` | `0 chart(s) failed` |
| `helm template t deploy/helm/sports-notifier -f deploy/helm/sports-notifier/values-local.yaml \| grep '^kind:' \| sort \| uniq -c` | `6 Deployment`, `1 Ingress`, `6 Service`, `1 StatefulSet` |

- [ ] **Step 5: Add the Makefile targets**

Variables:
- `CLUSTER := sports-notifier`
- `RELEASE := sports-notifier`
- `CHART := deploy/helm/sports-notifier`
- `TAG :=` short SHA, plus `-dirty-<unix time>` if the tree is dirty. Evaluate once with `:=`.

Targets:
- **`cluster`:** if `k3d cluster get $(CLUSTER)` succeeds, print `Cluster $(CLUSTER) already exists` and stop. Otherwise run `k3d cluster create $(CLUSTER) -p "8080:80@loadbalancer" --wait`.
- **`up`:**
  1. If the cluster does not exist, print `Cluster $(CLUSTER) not found. Run: make cluster` and exit 1.
  2. `docker build -t sports-notifier:$(TAG) .`
  3. `k3d image import sports-notifier:$(TAG) -c $(CLUSTER)`
  4. `helm upgrade --install $(RELEASE) $(CHART) -f $(CHART)/values-local.yaml --set image.tag=$(TAG) --wait --timeout 5m`
- **`logs`:**
  1. `kubectl get pods -o wide`
  2. `kubectl logs -l app.kubernetes.io/part-of=sports-notifier --all-containers --prefix --tail=50 --max-log-requests=20`
  3. The last 30 events: `kubectl get events --sort-by=.lastTimestamp | tail -30`
- **`down`:** `k3d cluster delete $(CLUSTER)`.

- [ ] **Step 6: Wrong-order checks (Review Focus 4)**

Run: `make up; echo "exit=$?"` before any cluster exists. Expected: `Cluster sports-notifier not found. Run: make cluster` and `exit=1`.
Then run `make cluster` twice. Expected: the second run prints `Cluster sports-notifier already exists`.

- [ ] **Step 7: Deploy and check**

Run `make up`, then:

| Check | Expected |
|---|---|
| `kubectl get pods` | 8 pods `Running` and `READY 1/1` (7 Java, including 2 pubsub-workers, plus `redis-0`) |
| `curl -fsS localhost:8080/health` (retry for up to 60 s while Traefik picks up the route) | `{"status":"ok"}` |
| `curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' localhost:8080/` | `302 http://localhost:8080/ui/` |
| For each pubsub-worker pod: `kubectl exec <pod> -- printenv PUBSUB_CONSUMER_NAME` | The pod's own name; the two values differ |

- [ ] **Step 8: Redis keeps data across a pod restart**

Run:
1. `kubectl exec redis-0 -- redis-cli SET phase1:probe ok`
2. `kubectl delete pod redis-0 && kubectl rollout status statefulset/redis`
3. `kubectl exec redis-0 -- redis-cli GET phase1:probe`

Expected: `ok`.

- [ ] **Step 9: A code change rolls the pods (Review Focus 2)**

1. Note the tag: `kubectl get deploy gateway -o jsonpath='{.spec.template.spec.containers[0].image}'`.
2. `touch rollcheck.tmp && make up`
3. Read the tag again. Expected: it now ends in `-dirty-<time>`, and `kubectl get pods` shows fresh ages for every Java pod.
4. `rm rollcheck.tmp`.

- [ ] **Step 10: Commit**

```bash
git add deploy Makefile
git commit -m "Add Helm chart for Redis and the Java services, plus cluster make targets"
```

---

### Task 5: Event sources (simulator on demand, poller switchable)

**Files:**
- Create: `deploy/helm/sports-notifier/templates/simulator-cronjob.yaml`
- Create: `deploy/helm/sports-notifier/templates/espn-poller.yaml`
- Create: `scripts/simulate.sh` (executable)
- Modify: `Makefile` (add `simulate`)

**Interfaces:**
- Consumes: the Task 4 helpers and the `.Values.simulator` / `.Values.espnPoller` keys.
- Produces: `scripts/simulate.sh [KEY=VALUE ...]` creates a one-off Job from `cronjob/simulator` with the given env overrides, prints the Job name on stdout, and exits 0. `make simulate` runs it with no arguments.

- [ ] **Step 1: Write the templates**

**`simulator-cronjob.yaml`:** a CronJob `simulator` with:
- `suspend: true`, and a comment: it never fires; it is a template for `simulate.sh`.
- Any valid `schedule`, e.g. `"0 0 1 1 *"`.
- Job settings `backoffLimit: 0` and `ttlSecondsAfterFinished: 600`.
- Pod `restartPolicy: Never`.
- Env:
  - `SERVICE=simulator`
  - the `baseEnv` helper
  - `EPS_URL=http://event-processing:8083`
  - `SIM_INTERVAL_MS` and `SIM_WAVES` from `.Values.simulator`
- No probes.

**`espn-poller.yaml`:** wrapped in `if .Values.espnPoller.enabled`. A Deployment `espn-poller` with:
- `replicas: 1` hard-coded, with the Global Constraints reason as a comment.
- `strategy: {type: Recreate}`.
- Env: `SERVICE=espn-poller`, the `baseEnv` helper, `EPS_URL=http://event-processing:8083`, and `ESPN_POLL_INTERVAL_MS`.
- No probes.

- [ ] **Step 2: Static checks**

| Command | Expected |
|---|---|
| `helm template t deploy/helm/sports-notifier -f deploy/helm/sports-notifier/values-local.yaml \| grep -c 'name: espn-poller'` | `0` |
| Same with `--set espnPoller.enabled=true`, grepping for `kind: Deployment` | 7 Deployments |
| Same, looking at the espn-poller Deployment | Shows `type: Recreate` and `replicas: 1` |

- [ ] **Step 3: Write `scripts/simulate.sh`**

- `set -euo pipefail`.
- Every argument must match `^[A-Z_][A-Z0-9_]*=`; otherwise print `usage: simulate.sh [KEY=VALUE ...]` to stderr and exit 2.
- Job name: `simulator-$(date +%s)-$RANDOM`.
- With arguments: `kubectl create job "$name" --from=cronjob/simulator --dry-run=client -o yaml | kubectl set env --local -f - "$@" -o yaml | kubectl create -f -`.
- Without arguments: `kubectl create job "$name" --from=cronjob/simulator`.
- Print only `$name` on stdout; send kubectl output to stderr.

Add a `simulate` Makefile target that runs `scripts/simulate.sh`.

- [ ] **Step 4: Run it**

Run: `make up && job=$(scripts/simulate.sh SIM_WAVES=1) && kubectl logs -f "job/$job" | head -3`
Expected: lines like `[Simulator] GAME_START game-LAL-BOS :: LAL vs BOS has started`. Then confirm `kubectl get job "$job" -o jsonpath='{..env}'` shows `SIM_WAVES` = `1`.

- [ ] **Step 5: Overlapping runs and bad input (Review Focus 1)**

Run: `make simulate && make simulate && kubectl get jobs`
Expected: two distinct `simulator-…` Jobs, both created.

Run: `scripts/simulate.sh waves=1; echo "exit=$?"`
Expected: the usage line and `exit=2`.

- [ ] **Step 6: Commit**

```bash
git add deploy scripts/simulate.sh Makefile
git commit -m "Add on-demand simulator Job and switchable ESPN poller"
```

---

### Task 6: Smoke test

**Files:**
- Create: `scripts/smoke-test.sh` (executable)
- Modify: `Makefile` (add `smoke`)

**Interfaces:**
- Consumes:
  - `scripts/simulate.sh` and `make logs`.
  - Gateway API:
    - `POST /api/signup` with `{"username","password"}`, which sets a session cookie.
    - `PUT /api/preferences` with `{"followedTeams","followedGames","followedLeagues","notificationTypes"}` (send all four lists).
    - `GET /api/history`, which returns a JSON array of `{gameId, message, …}` with no type field.
- Produces: exit 0 and the final line `SMOKE PASS`; on failure, `make logs` output and a non-zero exit.

- [ ] **Step 1: Write `scripts/smoke-test.sh`**

`set -euo pipefail`; `BASE_URL=${BASE_URL:-http://localhost:8080}`; a cookie jar from `mktemp`. An `ERR` trap and every explicit failure run `make -C "$repo_root" logs` and then exit 1.

1. **Wait for the cluster.** Each wait has a 180 s timeout:
   - `kubectl -n kube-system rollout status deployment/traefik`
   - `kubectl wait --for=condition=available deployment -l app.kubernetes.io/part-of=sports-notifier`
   - `kubectl rollout status statefulset/redis`
   - Then retry `curl -fsS $BASE_URL/health` once a second, up to 60 s.
2. **Sign up.** Username `smoke-$(date +%s)-$RANDOM`, password `smoke-password`, via `POST /api/signup`. Require HTTP 200.
3. **Follow LAL.** `PUT /api/preferences` with `{"followedTeams":["LAL"],"followedGames":[],"followedLeagues":[],"notificationTypes":["GAME_START"]}`. Require HTTP 200.
4. **Run the simulator.** `scripts/simulate.sh SIM_WAVES=1`.
5. **Wait for the notification.** Once a second, for up to 60 s, fetch `GET /api/history`. Pass when `jq -e 'any(.[]; .gameId == "game-LAL-BOS" and (.message | contains("has started")))'` succeeds. Print `SMOKE PASS` and exit 0. On timeout, print `No GAME_START for game-LAL-BOS in history after 60s` and fail.

Add a comment above step 3 on why it's LAL with GAME_START only: game start is tagged with the home team, while score events use a random scorer and fan-out matches only the tagged team, so relying on scores would be flaky. The comment also notes this tightens in Phase 2.

Add a `smoke` Makefile target that runs the script.

- [ ] **Step 2: It passes, and passes again (Review Focus 1)**

Run: `make smoke && make smoke`
Expected: `SMOKE PASS` twice. The second run starts while the first run's simulator Job is still going.

- [ ] **Step 3: It fails loudly when delivery is broken**

1. `kubectl scale deploy pubsub-worker --replicas=0`
2. `make smoke; echo "exit=$?"`. Expected: after about 60 s, the timeout message, pod and log output, and a non-zero exit.
3. Restore with `kubectl scale deploy pubsub-worker --replicas=2`.

- [ ] **Step 4: Manual browser check (Review Focus 5)**

Ask the user to:
1. Open `http://localhost:8080`, sign up, and follow LAL with Game Start on.
2. Run `make simulate`.
3. Confirm a notification appears without reloading the page.

In DevTools → Network, `/ws` should show status `101`. Record the result in the task report.

- [ ] **Step 5: Commit**

```bash
git add scripts/smoke-test.sh Makefile
git commit -m "Add end-to-end smoke test: signup, follow LAL, simulate, check history"
```

---

### Task 7: CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `make cluster up smoke logs` and `mvn -B test`.

- [ ] **Step 1: Write `ci.yml`**

- Name `CI`. Triggers: `push` (all branches) and `pull_request`.
- `concurrency: {group: ci-${{ github.ref }}, cancel-in-progress: true}`.

**Job `test`** (`ubuntu-latest`):
- Service `redis`: image `redis:7.4`, `ports: ["6379:6379"]`, health options `--health-cmd "redis-cli ping" --health-interval 5s --health-timeout 3s --health-retries 10`.
- Steps:
  1. `actions/checkout`
  2. `actions/setup-java` with `distribution: temurin`, `java-version: 17`, `cache: maven`
  3. `mvn -B test`

**Job `deploy-check`** (`needs: test`, `ubuntu-latest`):
1. Checkout.
2. Install k3d with the official install script, pinned via `TAG=<version>`, and set the version as workflow env `K3D_VERSION`.
3. Install Helm with `azure/setup-helm`, pinned via `HELM_VERSION`.
4. `make cluster up smoke`.
5. `if: failure()`: `make logs`.

Use the current major version of each action, and the latest stable k3d v5 and Helm v3 releases at implementation time.

- [ ] **Step 2: Lint locally if available**

Run: `command -v actionlint && actionlint .github/workflows/ci.yml`
Expected: no output. Skip this step if actionlint is not installed.

- [ ] **Step 3: Commit, push, and watch**

```bash
git add .github/workflows/ci.yml
git commit -m "Add CI: tests, then deploy to a throwaway k3d cluster and smoke test"
```

Ask the user, then `git push -u origin phase-1-foundation`, then `gh run watch --exit-status`.
Expected: both jobs are green; `deploy-check` log ends with `SMOKE PASS`. If it fails, use superpowers:systematic-debugging on the job log before changing anything.

---

### Task 8: Retire `com3810/` and update docs

**Files:**
- Move: `com3810/README.md`, `com3810/documents/`, `com3810/diagrams/`, `com3810/scripts/`, `com3810/quickstart.sh` → `docs/course/`
- Delete: the rest of `com3810/` (`code/`, `.gitignore`)
- Modify: `CLAUDE.md`, `AGENTS.md`, `DESIGN.md`
- Create: `README.md`

- [ ] **Step 1: Move and delete in one commit**

```bash
mkdir -p docs/course
git mv com3810/README.md com3810/documents com3810/diagrams com3810/scripts com3810/quickstart.sh docs/course/
git rm -r -q com3810
test ! -e com3810 && git status --short | head
git commit -m "Retire com3810/: code now lives at the root, course material held in docs/course"
```

Expected: `git status` shows the moves (`R`) and the deletes before the commit.

- [ ] **Step 2: Nothing still points at the old path**

Run: `grep -rn 'com3810/' --exclude-dir=.git --exclude-dir=docs . | grep -v -e '^./DESIGN.md' -e '^./CLAUDE.md' -e '^./AGENTS.md'`
Expected: no output. Then run `make test` and `make up smoke`, and both pass.

- [ ] **Step 3: Update `CLAUDE.md` and `AGENTS.md`**

Apply the same edits to both files, keeping AGENTS.md's existing wording differences.

- **"Current state of the repo":** replace the "Not built" and "No deployment infrastructure" bullets. The new bullets cover:
  - Layout at the root.
  - Build and test with `make test`.
  - Run locally with `make cluster up`, then `make simulate`, `make smoke`, `make logs`, `make down`.
  - CI runs tests plus a k3d deploy check.
  - The next step is the notification-catalog brainstorm, then Phase 2 (see the Phase 1 spec's "Deferred to Phase 2").
- Replace the line saying tests need Redis on `localhost:6379` with a note that `make test` starts a throwaway one.
- **"Workflow":** add `Phase 1 is packaging only; bugs found during packaging go to the "Deferred to Phase 2" list in docs/superpowers/specs/2026-09-29-phase-1-foundation-design.md, not fixed in place.`
- **"Open decisions":** change "Flatten `com3810/` during the foundation step" to say it is done.
- Do not mention `docs/course/`.

- [ ] **Step 4: Write root `README.md`**

Short: one paragraph on what the system is, with a pointer to `DESIGN.md` for the decisions. Then:
- **Prerequisites:** OrbStack (Docker), `brew install k3d helm kubectl jq`, and Java 17 + Maven for `make test`.
- **Run it:** `make cluster`, `make up`, open `http://localhost:8080`, `make simulate`.
- **Check it:** `make test`, `make smoke`.
- **Stop it:** `make down`. Note that this deletes accounts until Postgres lands (Phase 3).

No mention of `docs/course/` and no scale claims.

- [ ] **Step 5: Mark Phase 1 done in `DESIGN.md`**

Append ` **Done.**` to build-order item 1 only. No other edits.

- [ ] **Step 6: Final verification from nothing**

Run: `make down; make cluster up smoke`
Expected: `SMOKE PASS` on a brand-new cluster.

- [ ] **Step 7: Commit, then ask to push**

```bash
git add README.md CLAUDE.md AGENTS.md DESIGN.md
git commit -m "Document the root layout and make commands; mark Phase 1 done"
```

Ask the user before pushing. After the push, confirm CI is green with `gh run watch --exit-status`. Then offer the user a short quiz on Phase 1, per CLAUDE.md.
