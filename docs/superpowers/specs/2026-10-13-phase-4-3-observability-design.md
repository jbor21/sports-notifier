# Phase 4.3: Traces and metrics — design

Status: draft for review, 2026-10-13. Parent document: `DESIGN.md` (decisions 12, 14). If this spec and `DESIGN.md` disagree, `DESIGN.md` wins and this spec gets fixed. `DESIGN.md`'s build order (step 4.3) and decision 14 are updated with this spec.

Phase 4 is three sub-phases: 4.1 replay and checker (merged, PR #46), 4.2 chaos suite on k3d (merged, PR #53), **4.3 traces and metrics (this spec)**. Each gets its own spec, plan, and PR.

## Goal

Show where a play's time goes, and what a fault does to it. One play is one trace from the poller to the gateway's socket write; Grafana shows p50 and p99 of "poller saw it → gateway sent it" and the lag of every consumer group; and during a `make chaos` run the dashboard shows the latency spike, the backlog, and the recovery, with the fault marked on the graph. A screenshot of that dashboard is the evidence decision 14 promises.

Also in 4.3: issue #54 (4.2's six deferred minors), because four of them are in `scripts/chaos.sh`, which this phase edits, and one (mixed time baselines) would put the fault marks in the wrong place.

Not in 4.3: logs in Loki; traces for History and for the `game_update` score cards (section 3.4); KEDA and the cloud (Phase 5); #36, the live check (before Phase 5); #47 and #51 (before Phase 6); #52 (after Phase 6).

### Decided before this spec (brainstorm, 2026-10-13)

- One `grafana/otel-lgtm` pod (OpenTelemetry Collector, Prometheus, Tempo, Grafana), plus Strimzi's Kafka exporter for lag.
- The trace id is carried in the Kafka headers and in the Redis pub/sub body (and, found in the brainstorm, in the outbox).
- **Manual instrumentation** with the OpenTelemetry SDK, not the Java agent: every place the trace id crosses a boundary is code in this repo.
- **CI runs the pod** and a `make trace-check` that proves one trace spans every service.
- Grafana shows p50 and p99 from "poller saw it" to "gateway sent it", plus Kafka lag; `chaos.sh` marks each fault on the graph.
- **Exemplars** on the latency histogram, so a point in the spike opens that play's trace (added after the design was approved; section 4.2).

### Done when

1. `make test` passes, including every test in section 8.
2. CI is green: `make cluster up smoke check trace-check e2e`, with the observability pod running.
3. `make chaos` passes all eight scenarios on the laptop; its summary, with a dashboard link per scenario, is pasted in the PR.
4. Two dashboard screenshots are in the PR, one from `stage1-crash` and one from `broker-crash`, each showing the latency spike, the lag, and the fault marks; and one trace screenshot from Tempo opened through an exemplar in the spike.
5. The overhead line (section 8.4) is in the PR: `make check SET=evening SPEED=60` p50 and p99 on main and on this branch.
6. `make check-selftest`, `make replay-detectors` (golden timelines unchanged), and `scripts/persistence-check.sh` pass on the laptop.
7. Docs updated (section 10), and `docs/study/phase-4-quiz.md` has a 4.3 section. The user is quizzed only on request.
8. The PR from `phase-4-3-observability` is merged; it closes #54.

### Branch

`phase-4-3-observability`, worktree `.worktrees/phase-4-3-observability`, from main at `4496f3a`.

---

## 1. The observability pod

### 1.1 What runs

One Deployment, `lgtm`, from `grafana/otel-lgtm:0.35.0` (pinned; released 2026-10-02). It holds four programs:

| Program | Job here | Port |
|---|---|---|
| OpenTelemetry Collector | receives traces and metrics from our services (OTLP over HTTP); scrapes the Kafka exporter; forwards traces to Tempo and metrics to Prometheus | 4318 |
| Prometheus | stores metrics, with exemplars (the image starts it with `--enable-feature=exemplar-storage`) | 9090 |
| Tempo | stores traces | 3200 |
| Grafana | dashboards; its Prometheus data source already links an exemplar's `trace_id` to Tempo | 3000 |

The image also runs Loki and Pyroscope; we send them nothing.

- **Resources.** Request 1 GiB, limit 2 GiB (the image's own guidance is 2 GB). OrbStack has 24 GiB on the laptop.
- **Data** lives in an `emptyDir` at `/data`: lost when the pod restarts and on `make down`. A run's graphs are screenshots, not history.
- **One Service**, `lgtm`, ports 4318, 3000, 3200, 9090.
- **Switch.** `observability.enabled` in `values.yaml`, default `true` (laptop, CI, the Chaos workflow). When `false`, no pod, and the services get no endpoint, so their telemetry is off (section 2.1).

### 1.2 Kafka lag

**Problem.** Lag ("messages written that no worker has read yet") is the backlog a fault builds. Our services cannot report it themselves: a crashed worker reports nothing, and lag is a property of the group, not of one member.

**Fix.** Strimzi's Kafka exporter, a small Deployment Strimzi runs beside the broker that reads every group's committed offsets and every partition's end offset and exposes the difference as Prometheus metrics. Turned on in the Kafka resource (`templates/kafka.yaml`):

```yaml
kafkaExporter:
  groupRegex: "stage1|stage2|history"
  topicRegex: "game-events|notifications"
```

It exposes `kafka_consumergroup_lag{consumergroup, topic, partition}` on port 9404. The chart adds a Service, `kafka-exporter`, selecting the exporter's pod by its Strimzi label (`strimzi.io/name: <cluster>-kafka-exporter`), because Strimzi does not create one (it expects a PodMonitor, which needs the Prometheus Operator). Plan task 1 confirms the label and that Strimzi's own network policy lets its exporter reach the broker.

### 1.3 The collector's config

The image reads its collector config from `/otel-lgtm/otelcol-config.yaml`. The chart mounts a ConfigMap over that file: the image's own config for 0.35.0, unchanged, plus one `prometheus` receiver that scrapes `kafka-exporter:9404` every 5 s, added to the metrics pipeline. The file is kept in the chart (`files/otelcol-config.yaml`) with a comment naming the image version it was copied from; bumping the image means re-copying it.

### 1.4 The dashboard file

The image loads Grafana dashboard providers from `/otel-lgtm/grafana/conf/provisioning/dashboards/`. The chart mounts two files from a ConfigMap into the pod: a provider, `sports-notifier.yaml`, in that directory (by `subPath`, so the image's own providers stay), and the dashboard, `pipeline.json` (uid `pipeline`), at the path the provider names. Section 5 describes it.

### 1.5 Access

- **`make grafana`** runs `kubectl port-forward svc/lgtm 3000:3000` and prints `http://localhost:3000/d/pipeline`. Grafana in the image allows anonymous users as Admin; it is reachable only through the port-forward, never through Traefik.
- **Network policies.** The chart's `default-deny-ingress` covers every pod in the namespace, the new ones included, so the chart adds two policies: `lgtm-from-app` admits port 4318 into `lgtm` from our pods (every pod of the release, including the checker, replay, and simulator Jobs), and `kafka-exporter-from-lgtm` admits port 9404 into the exporter from `lgtm` only. Nothing else needs to reach `lgtm`: a port-forward enters through the kubelet, not the pod network. The exporter's own connection to the broker uses Strimzi's internal listener, which Strimzi's generated policy already admits for its own pods (plan task 1 confirms).

## 2. Telemetry in the services

### 2.1 The `telemetry` package

A small package, `telemetry/`, owns OpenTelemetry for every process:

- **`Telemetry.init(serviceName)`**, called once by `app.Launcher` before the service's `main`. It builds the SDK from the standard OpenTelemetry environment variables (the SDK's autoconfigure module), so all settings come from the chart. **Telemetry is off unless `OTEL_EXPORTER_OTLP_ENDPOINT` is set**: tests and `make test` export nothing and log nothing. Off means the no-op implementation: spans and metrics cost nothing, and no `traceparent` is written anywhere.
- **`Telemetry.close()`**, from one shutdown hook that `app.Launcher` registers: flush what is queued, at most 5 s. (Shutdown hooks run concurrently, so the spans of the last instant before exit may be lost; nothing on the push path depends on them.)
- **`Traces`**: the W3C trace-context propagator and three carriers, each a pair of "write the context in" and "read it out": an HTTP request's headers, a Kafka record's headers, and a plain string (`traceparent`, about 55 characters, for the outbox and the pub/sub JSON).

**Dependencies** (Maven, through `opentelemetry-bom` 1.66.0): `opentelemetry-api`, `-sdk`, `-sdk-extension-autoconfigure`, `-exporter-otlp` with the JDK HTTP sender (`opentelemetry-exporter-sender-jdk`) instead of the default OkHttp one, which would pull Kotlin into the JAR; `opentelemetry-sdk-testing` for tests.

### 2.2 Settings from the chart

Every Java container, and the checker, replay, and simulator Jobs, get these when `observability.enabled`:

| Variable | Value | Why |
|---|---|---|
| `OTEL_SERVICE_NAME` | the service's key (`gateway`, `stage1-worker`, …, `checker`) | names the spans' and metrics' source |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://lgtm:4318` | turns telemetry on (section 2.1) |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` | |
| `OTEL_TRACES_SAMPLER` / `_ARG` | `parentbased_traceidratio` / `observability.sampleRate` (default `1.0`) | every play traced on the laptop; Phase 6's load test can lower it, and a play's whole fan-out is kept or dropped together (the decision is made once, at the root) |
| `OTEL_METRIC_EXPORT_INTERVAL` | `5000` | a 60× `evening` run lasts about 3 minutes; the default 60 s would draw three points |
| `OTEL_BSP_MAX_QUEUE_SIZE` | `8192` | one touchdown with 500 followers is over 1,000 spans at once; the default 2,048 could drop some |
| `OTEL_LOGS_EXPORTER` | `none` | logs stay in `kubectl logs` |

**Exporting never slows a push.** Spans and metrics are queued and sent by a background thread. A dead or slow `lgtm` pod means dropped telemetry, never a blocked worker.

## 3. The trace

### 3.1 One play, one trace

| Span | Process | Kind | Starts / ends | Carries the context onward in |
|---|---|---|---|---|
| `poll` (root) | the poller (in `espn-poller`, `simulator`, `replay`, or `checker`) | client | starts **at `detectedAt`**; ends when the POST answers | HTTP header `traceparent` on `POST /events` |
| `accept` | `event-processing` | server | around the accept script; attribute `result` (`accepted`, `duplicate`, `pending`, `stale`) | `outbox:<gameId>`'s new field `traceparent`, written by the accept script in the same step as the play (one more `ARGV`) |
| `produce` | `event-processing` (or its `OutboxRelay`) | producer | from `send` until Kafka confirms; attribute `retry` (true from the relay) | Kafka header `traceparent` on the `game-events` record |
| `fanout` | `stage1-worker` | consumer | one per play, around detectors, audience, and sends; attributes `alerts`, `pushes`, `skipped` (the `det:` version says it is done) | Kafka header `traceparent` on every `notifications` message, the `fanout` span's context |
| `deliver` | `stage2-worker` | consumer | one per message; attribute `result` (`delivered`, `duplicate`) | field `traceparent` in the gateway message JSON: `{"userId", "payload", "traceparent"}` |
| `send` | `gateway` | consumer | around the socket writes for that user; attribute `sockets` | — |

`poll` starts at `detectedAt`, the poller's clock right after the fetch (already in every play), so the trace and the latency metric (section 4) start from the same instant.

### 3.2 The hops that are not obvious

- **The outbox.** An accepted play waits in `outbox:<gameId>` until Kafka confirms it. When the broker is down, `OutboxRelay` sends it later, from another thread, reading the play back from Redis. If the slot did not keep `traceparent`, the relay's `produce` would start a new trace and the wait would vanish from the picture. Kept, `broker-crash` shows as `accept` … a gap of the outage … `produce (retry=true)`.
- **Redelivery.** After a Stage 1 crash Kafka hands the same play to the other copy. Its second `fanout` lands in the same trace with `skipped=true`. After a Stage 2 crash the redelivered message's second `deliver` lands with `result=duplicate`. At-least-once delivery and the dedup that absorbs it are visible in one trace.
- **Stage 2 batches.** Stage 2 sends one batch's delivery scripts as one Redis pipeline. Each message still gets its own `deliver` span, but all spans of a batch start before the pipeline and end after it, so a `deliver` span's length is its batch's round trip, not one script's.
- **Two gateways.** A user with sockets on both gateways gets the push published on both channels; each gateway records its own `send` span, both children of the same `deliver`.

### 3.3 When the context is missing

A message with no `traceparent` (written before this phase, during a rolling upgrade, or by a process with telemetry off) is not an error: the reading side starts a new root span and carries on. A `traceparent` that does not parse is treated as missing. Nothing on the push path branches on telemetry.

### 3.4 Not traced

- **History.** It reads `notifications` too, but it is not on the push path, and a batch insert of 500 rows would be 500 links. Its lag is on the dashboard (section 5).
- **`game_update`.** The accept script's `live:<gameId>` publish (score cards) is a second path; adding it would change the script's publish payload for no question we are asking now.
- **Logs.** Loki is in the pod, but shipping logs needs a log appender in every service. `kubectl logs` and `make logs` stay.

## 4. Metrics

### 4.1 One latency histogram, measured at every stage

**`pipeline.latency`** (seconds), attribute `stage`, recorded as *now − `detectedAt`* at four points:

| `stage` | Recorded by | When |
|---|---|---|
| `accepted` | `event-processing` | the script answered `accepted` |
| `stage1` | `stage1-worker` | a play's `fanout` finished its sends (not for `skipped`) |
| `stage2` | `stage2-worker` | a message was `delivered` |
| `gateway` | `gateway` | the push was written to the user's sockets: **"poller saw it → gateway sent it"** |

**Why one histogram, not one per hop:** every value is "time since the poller saw it", so the four p99 lines stack, and the space between two lines is that hop's cost. A per-hop histogram would need each hop's start time carried along; this needs only `detectedAt`, which every message already has.

**Buckets.** Explicit boundaries, set as the instrument's advice in code: 5, 10, 25, 50, 100, 250, 500 ms; 1, 2.5, 5, 10, 20, 30, 45, 60, 90, 120 s. The SDK's default stops at 10 s; a crashed worker stalls pushes for about 45 s (the session timeout), and the spike must show as 45 s, not "over 10 s".

**Clocks.** `detectedAt` is the poller's clock; the other end is each service's clock. On the laptop every pod shares one node's clock. On EKS the nodes are synced by the Amazon Time Sync Service (well under a millisecond); noted for Phase 6.

### 4.2 Exemplars

**Problem.** A p99 line says "something was slow at 14:03:17". It does not say which play, or which hop.

**Fix.** An exemplar is one real sample stored beside a histogram bucket together with the trace id of the span that was current when it was recorded. The SDK records them by default (`trace_based` filter: only when a sampled span is current). So each `pipeline.latency` record is made **inside its span** (`accept`, `fanout`, `deliver`, `send`, made current), and the exemplars flow through the collector into Prometheus, which stores them. The dashboard's latency panel turns exemplars on (section 5); clicking a dot in the spike opens that play's trace in Tempo through the data source's existing `trace_id` link.

**Cost:** making the span current around the record, and one flag per panel. No new component.

### 4.3 Counters

| OpenTelemetry name | Attributes | Recorded by |
|---|---|---|
| `plays.accepted` | `result` | `event-processing`, one per script answer |
| `pushes.produced` | — | `stage1-worker`, one per `notifications` message sent |
| `deliveries` | `result` | `stage2-worker`, one per message |
| `socket.sends` | — | `gateway`, one per push written (per user, not per tab) |

### 4.4 Names in Prometheus

The collector forwards metrics to Prometheus over OTLP, and Prometheus turns names into its own form: dots become underscores, the unit is added, counters end in `_total`, and the service name becomes the `job` label. So `pipeline.latency` is `pipeline_latency_seconds_bucket` (plus `_sum`, `_count`), and `plays.accepted` is `plays_accepted_total`. Plan task 1 sends one sample of each and pins the exact names the dashboard uses.

## 5. The "Pipeline" dashboard

One dashboard, uid `pipeline`, in the chart as JSON (section 1.4). Time range: last 15 minutes, refresh every 5 s. Every panel sets its own minimum interval to 5 s (the image's Prometheus data source says 60 s, which would hide a 3-minute run).

| Row | Panel | Query (in words) |
|---|---|---|
| 1 | **Push latency, poller → gateway**: p50, p99, p99.9, exemplars on | `histogram_quantile` over `pipeline_latency_seconds_bucket{stage="gateway"}`, `rate` over 30 s |
| 2 | **Time since the poller saw it, per stage**: p99 of each `stage` | the same, `by (stage)` |
| 3 | **Kafka lag** per group (`stage1`, `stage2`, `history`) | `sum by (consumergroup) (kafka_consumergroup_lag)` |
| 4 | **Throughput**: plays accepted per second, pushes sent per second | `rate` of `plays_accepted_total{result="accepted"}` and `socket_sends_total` |

Row 1 is the screenshot.

**No "max" line.** Prometheus does not keep a histogram's maximum when it receives it over OTLP; p99.9 stands in for it. The true maximum per run is already in the checker's report (`max`, Phase 4.2).

**Annotations.** The dashboard shows Grafana annotations tagged `chaos` (section 6.1) as vertical marks with their text.

## 6. Chaos and the dashboard

### 6.1 Fault marks

`chaos.sh` opens its own port-forward to Grafana when it starts (closed on exit) and posts annotations with Grafana's HTTP API (`POST /api/annotations`, tags `chaos` and the scenario's name), at four moments of each scenario:

| Mark | When |
|---|---|
| `<scenario>: run started` | the checker's `run started` line |
| `<scenario>: fault` | the fault landed (crash proven, cut applied, rollout begun) |
| `<scenario>: healed` | a cut healed, or a rollout finished; crashes have no heal mark (Kubernetes restarts the container on its own) |
| `<scenario>: run ended` | the checker exited |

An annotation that fails to post logs one line and never fails a scenario. When the cluster has no `svc/lgtm` (`observability.enabled=false`), `chaos.sh` skips the marks and the links.

### 6.2 A link per scenario

The summary gains one line per scenario: `http://localhost:3000/d/pipeline?from=<run started − 30 s>&to=<run ended + 30 s>` (epoch milliseconds). With `make grafana` running, one click shows that scenario's window, ready for a screenshot.

### 6.3 #54, items 1–4 (in `chaos.sh`)

1. **The cut's liveness note.** Record the pod's restart count before the cut and compare with that, not with 0.
2. **One time baseline.** Every proof time in the summary is measured from the checker's `run started` line (`t_run`), crashes and cuts alike. The fault marks use the same instants.
3. **A missing `run started`.** If the 300 s wait for the line runs out, delete the checker Job (as `cleanup` does) before waiting on it, so the scenario fails at once instead of after the Job's time limit.
4. **The rollout's new pods.** Leave out pods with a `deletionTimestamp` when listing the gateway's pods after a rollout.

## 7. Other fixes

- **#54 item 5.** `LiveSocket.released` asks `GatewayChannel.holds(userId)` (a `containsKey`), instead of copying the whole user set on every last-socket close.
- **#54 item 6.** For a claim written as `'1'` before 4.2, `Verifier` says "no delivery time in its claim", not "Stage 2 never claimed it".
- **The checker's latency line** drops "(preview; 4.3 measures it properly)" and reads `latency detectedAt → socket (checker): p50 … p99 … max …`. The checker's number is measured at the socket; Grafana's at the gateway's write. Both stay.

## 8. Tests

### 8.1 Unit (in-memory exporters from `opentelemetry-sdk-testing`)

- `Traces`: a context written into each carrier (HTTP headers, Kafka headers, string) reads back with the same trace id and parent span id; a missing or malformed `traceparent` reads back as no parent.
- Telemetry off (no endpoint): no `traceparent` is written into any carrier, and nothing throws.
- The poller: the `POST` carries `traceparent`; the `poll` span starts at the play's `detectedAt` (fake sink, fake clock).
- Stage 1: every `notifications` message of one play carries the `fanout` span's context, which is a child of the context in the `game-events` header (Kafka `MockProducer`); a `skipped` play records no `stage1` latency.
- The gateway: a pub/sub message with `traceparent` gives a `send` span with that parent, a `pipeline.latency{stage=gateway}` sample of now − `detectedAt`, and an exemplar carrying the span's trace id; a message without `traceparent` gives a root span and still the sample.
- The histogram's bucket boundaries are the ones in section 4.1.
- #54 item 6's message text.

### 8.2 With Redis or Kafka

- The accept script stores `traceparent` in the outbox slot; `Outbox.play` returns it; a slot written without the field (older code) reads back as none.
- `OutboxRelay`'s `produce` span for a waiting play has the stored context as its parent: same trace id as the `accept` span.
- Stage 2: the gateway message published by the delivery script contains the `traceparent` of the `deliver` span; a duplicate records `result=duplicate` and no `stage2` latency.
- Event Processing end to end (Testcontainers Kafka, as today): the `game-events` record's `traceparent` header has the `produce` span's context.
- #54 item 5: `holds` is true while a user has a socket and false after the last one closes.

### 8.3 Scripts

- `scripts/test-chaos-scripts.sh` (4.2's fake-`kubectl` test) gains: items 1–4 of #54, an annotation post that fails does not fail the scenario, and the summary's link has the right time range.

### 8.4 Cluster

- **`make trace-check`** (`scripts/trace-check.sh`), run after `make check` in CI and on the laptop. Through a port-forward to `lgtm` it checks, for the last 15 minutes:
  - Tempo has at least one trace containing spans from `event-processing`, `stage1-worker`, `stage2-worker`, and `gateway`, whose root span is `poll` (from `checker`, `replay`, `simulator`, or `espn-poller`);
  - Prometheus has samples of `pipeline_latency_seconds_bucket` for all four stages, and `kafka_consumergroup_lag` for `stage1`, `stage2`, and `history`;
  - Prometheus returns at least one exemplar for `stage="gateway"` whose `trace_id` Tempo can open.

  Any missing piece fails with what was missing. It waits up to 30 s for the last export (the 5 s metric interval and the span batch delay).
- **CI** runs `make cluster up smoke check trace-check e2e SET=ci SPEED=60` with the pod on. If the runner runs out of memory with the pod added, the plan stops and asks the user (the brainstorm's fallback was: CI without the pod, `trace-check` on the laptop and in the Chaos workflow only).
- **The Chaos workflow** gains `trace-check` after the scenarios.
- **Overhead.** `make check SET=evening SPEED=60` on main and on this branch, same laptop; both latency lines go in the PR.
- `make chaos`: all eight pass.

## 9. Order of work

1. The pod by hand, before any Java: deploy `lgtm` from the chart; confirm the collector override, the dashboard provider path, the Kafka exporter's label, Service, and network policy; send one span and one histogram with an exemplar from a throwaway client; pin the Prometheus names (section 4.4).
2. The `telemetry` package, its dependencies, `Launcher` and shutdown hooks; telemetry off by default in tests.
3. The poller's `poll` span and HTTP header; Event Processing's `accept`, the outbox field in the accept script, `produce` and the relay.
4. Stage 1's `fanout` and its headers.
5. Stage 2's `deliver` and the gateway message field; the gateway's `send`.
6. The latency histogram, its buckets and exemplars; the counters.
7. The dashboard JSON and `make grafana`; look at one `make check` run in it.
8. `make trace-check`; CI with the pod.
9. `chaos.sh`: #54 items 1–4, the annotations, the links; run all eight; take the screenshots.
10. #54 items 5–6; the checker's latency line.
11. The overhead runs; docs and the quiz.

## 10. Docs to update

- `DESIGN.md`: build order 4.3 says what was built (the pod, the trace through the poller's HTTP header, the outbox, Kafka headers, and the pub/sub body, one latency histogram per stage with exemplars, the lag exporter, the chaos marks), **merged** when it is; decision 14's tracing paragraph says the p50 and p99 come from the gateway's `pipeline.latency` histogram, and the traces show where the time went.
- `CLAUDE.md` and `AGENTS.md`: "New in Phase 4.3", `make grafana`, `make trace-check`, the pod in the architecture table and the stack line, the next step (Phase 5, after #36).
- `docs/study/phase-4-quiz.md`: a 4.3 section.
- Close #54 with the PR.

## Known gaps accepted in 4.3

- **One observability pod, no saved data.** A pod restart loses the graphs and traces so far. That is fine for runs that are screenshots; Phase 5's cloud uses the same pod and the same rule.
- **Latency crosses clocks.** `detectedAt` is the poller's clock and the gateway's sample is its own; on the laptop they are one clock, on EKS they are synced to well under a millisecond, small next to the numbers measured.
- **`deliver` spans show their batch's time** (section 3.2), not one script's.
- **No max on the dashboard** (section 5); the checker reports it.
- **History, `game_update`, and logs are not traced** (section 3.4).
- **100% sampling.** Fine on the laptop and in CI. Phase 6's load test sets `observability.sampleRate` lower; the latency histogram counts every push whatever the rate, and only exemplars and traces thin out.
- **The collector config is a copy of the image's.** Bumping `otel-lgtm` means re-copying it (section 1.3).
