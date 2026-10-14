# Phase 4.3: Traces and Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One trace per play from the poller to the gateway's socket write, a latency histogram ("time since the poller saw it") at four stages with exemplars, Kafka lag from Strimzi's exporter, and a Grafana "Pipeline" dashboard with chaos fault marks — all in one `grafana/otel-lgtm` pod, proven in CI by `make trace-check`; plus #54's six minors.

**Architecture:** A small `telemetry/` package wraps the OpenTelemetry SDK (off unless `OTEL_EXPORTER_OTLP_ENDPOINT` is set). Each hop starts a span from the context it received and passes its own context on: HTTP header → outbox field (written by the accept script) → Kafka header → Kafka headers on every notification → pub/sub JSON field. `PipelineMetrics.latency` is recorded inside each span, so the SDK attaches the trace id as an exemplar. The chart adds the `lgtm` pod, the Kafka exporter, its Service, two network policies, and the dashboard.

**Tech Stack:** Java 17, OpenTelemetry Java 1.66.0 (api, sdk, sdk-extension-autoconfigure, exporter-otlp with exporter-sender-jdk, sdk-testing), kafka-clients 4.3.1, Jedis 4.4.7, Javalin 6.1.6, JUnit 5, Testcontainers; Helm, Strimzi 1.2.0 (`kafkaExporter`), `grafana/otel-lgtm:0.35.0`; bash, curl, jq; GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-13-phase-4-3-observability-design.md`

## Global Constraints

- Image `grafana/otel-lgtm:0.35.0`; Deployment and Service `lgtm`, ports 4318, 3000, 3200, 9090; request 1 GiB, limit 2 GiB; `emptyDir` at `/data`.
- `observability.enabled` default `true` (laptop, CI, Chaos workflow); `observability.sampleRate` default `"1.0"`.
- Env on every Java container and the checker, replay, simulator Jobs and the ESPN poller, only when enabled: `OTEL_SERVICE_NAME=<service key>`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://lgtm:4318`, `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`, `OTEL_TRACES_SAMPLER=parentbased_traceidratio`, `OTEL_TRACES_SAMPLER_ARG=<sampleRate>`, `OTEL_METRIC_EXPORT_INTERVAL=5000`, `OTEL_BSP_MAX_QUEUE_SIZE=8192`, `OTEL_LOGS_EXPORTER=none`.
- Telemetry is off (no-op, no `traceparent` written anywhere) unless `OTEL_EXPORTER_OTLP_ENDPOINT` is non-blank.
- Carrier name everywhere: `traceparent` (HTTP header, outbox hash field, Kafka header, pub/sub JSON field).
- Span names and kinds: `poll` (CLIENT, root, starts at `detectedAt`), `accept` (SERVER), `produce` (PRODUCER), `fanout` (CONSUMER), `deliver` (CONSUMER), `send` (CONSUMER).
- Span attributes: `accept.result` ∈ `accepted|duplicate|pending|stale|invalid`; `produce.retry` (bool); `fanout.skipped` (bool), `fanout.alerts`, `fanout.pushes` (long); `deliver.result` ∈ `delivered|duplicate`; `send.sockets` (long); `poll.result` (the POST's `result`).
- Histogram `pipeline.latency`, unit `s`, attribute `stage` ∈ `accepted|stage1|stage2|gateway`, buckets (s): `0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 30, 45, 60, 90, 120`.
- Counters: `plays.accepted{result}`, `pushes.produced`, `deliveries{result}`, `socket.sends`.
- Kafka exporter: `groupRegex: "stage1|stage2|history"`, `topicRegex: "game-events|notifications"`; Service `kafka-exporter` on 9404; collector scrapes it every 5 s.
- Dashboard uid `pipeline`; every panel `interval: "5s"`; range 15 m; refresh 5 s; annotations tagged `chaos`.
- Grafana is reached only by port-forward (`make grafana` → `localhost:3000`), never through Traefik.
- Redis keys only via `util/RedisKeys.java`; every Jedis handle in try-with-resources; config by env var; comment density as in the repo (explain *why* at the outbox field, the span-around-the-record for exemplars, the Stage 2 batch spans, the 120 s buckets).
- `make test` green at the end of every Java task.

## Review Focus

1. **Telemetry off** (no endpoint: `make test`, a cluster with `observability.enabled=false`) → no `traceparent` header, outbox field, Kafka header, or JSON field is written; the accept script gets `""` and stores nothing (Task 3 `telemetryOffStoresNoTraceparent`, Task 2 `offWritesNothing`).
2. **An outbox slot written by older code** (no `traceparent` field) during a rolling deploy → `Outbox.waiting` returns a null traceparent and the relay sends with a new root span, no exception (Task 3 `slotWithoutTraceparentRelaysAsNewTrace`).
3. **A malformed `traceparent`** (`"abc"`, an all-zero trace id) in an HTTP header, Kafka header, or pub/sub field → treated as missing: a root span, and the push is still delivered (Task 2 `malformedIsRoot`, Task 5 `garbageTraceparentStillSends`).
4. **`detectedAt` in the future** (poller clock ahead of the gateway's) → latency recorded as 0, never a negative value (the SDK drops negative histogram values with a warning) (Task 2 `negativeLatencyRecordsZero`).
5. **`lgtm` down while telemetry is on** → spans and metrics are dropped in the background; no call on the push path throws or blocks (Task 2 `unreachableCollectorNeverBlocks`).

---

### Task 1: The observability pod, by hand

**Files:**
- Create: `deploy/helm/sports-notifier/templates/observability.yaml` (Deployment, Service `lgtm`, ConfigMaps `lgtm-collector`, `lgtm-dashboards`; Service `kafka-exporter`; NetworkPolicies `lgtm-from-app`, `kafka-exporter-from-lgtm`; all inside `{{- if .Values.observability.enabled }}`)
- Create: `deploy/helm/sports-notifier/files/otelcol-config.yaml`, `files/grafana-provider.yaml`, `files/pipeline.json` (a placeholder dashboard with one empty panel; Task 6 fills it)
- Modify: `deploy/helm/sports-notifier/values.yaml` (`observability:` block), `templates/kafka.yaml` (`kafkaExporter`), `Makefile` (`grafana` target)

**Interfaces:**
- Produces: Service `lgtm` (4318 OTLP HTTP, 3000 Grafana, 3200 Tempo, 9090 Prometheus); Service `kafka-exporter:9404`; `make grafana`; the **pinned Prometheus names** (recorded in this task's commit message and used by Tasks 6–8): expected `pipeline_latency_seconds_bucket{stage=…}`, `plays_accepted_total`, `pushes_produced_total`, `deliveries_total`, `socket_sends_total`, `kafka_consumergroup_lag{consumergroup=…}`, and the service label (`job` = `OTEL_SERVICE_NAME`).

- [ ] **Step 1: Copy the collector config** from the pinned image: `docker run --rm --entrypoint cat grafana/otel-lgtm:0.35.0 /otel-lgtm/otelcol-config.yaml > deploy/helm/sports-notifier/files/otelcol-config.yaml`. Add a first-line comment naming the image version, a receiver `prometheus/kafka` (scrape_configs: job `kafka-exporter`, `scrape_interval: 5s`, target `kafka-exporter:9404`), and add it to `service.pipelines.metrics.receivers`.
- [ ] **Step 2: Write the chart pieces.** Mount `lgtm-collector` over `/otel-lgtm/otelcol-config.yaml` (`subPath`); mount `grafana-provider.yaml` at `/otel-lgtm/grafana/conf/provisioning/dashboards/sports-notifier.yaml` (`subPath`) and `pipeline.json` at the path the provider names (`/otel-lgtm/sports-notifier/pipeline.json`). Readiness probe: `httpGet /api/health` on 3000. `kafka-exporter` Service selects `strimzi.io/name: <release>-kafka-exporter`. `lgtm-from-app` admits 4318 from pods labelled `app.kubernetes.io/part-of: sports-notifier`; `kafka-exporter-from-lgtm` admits 9404 from `app.kubernetes.io/name: lgtm` (give the lgtm pod the chart's labels helper with name `lgtm`). `make grafana`: `kubectl port-forward svc/lgtm 3000:3000` after printing `http://localhost:3000/d/pipeline`.
- [ ] **Step 3: Render and deploy.** `helm template deploy/helm/sports-notifier | grep -c 'name: lgtm'` → ≥ 3; `helm template … --set observability.enabled=false | grep -c lgtm` → 0. Then `make up` (start OrbStack and `make cluster` first if stopped). Expected: `kubectl get pods -l app.kubernetes.io/name=lgtm` Ready within 3 minutes; `kubectl get pods -l strimzi.io/name=sports-notifier-kafka-exporter` Running (if the label differs, fix the Service selector).
- [ ] **Step 4: Probe the pod** with port-forwards to 4318, 9090, 3200. Post one span (OTLP JSON to `/v1/traces`, service `probe`) and one histogram point named `pipeline.latency`, unit `s`, attribute `stage=gateway`, with one exemplar carrying that span's trace id (OTLP JSON to `/v1/metrics`). Expected within 30 s: Prometheus `/api/v1/query?query=pipeline_latency_seconds_bucket` returns series with `stage="gateway"` (pin the exact name and the job label); `/api/v1/query_exemplars?query=pipeline_latency_seconds_bucket` returns the exemplar with label `trace_id`; Tempo `/api/traces/<id>` returns 200; `kafka_consumergroup_lag{consumergroup="stage1"}` has samples (after `make simulate`). If the collector rejects `prometheus/kafka` (receiver not in this build), move the scrape job into a Prometheus config override (`/otel-lgtm/prometheus.yaml`, copied the same way) and note it in the commit.
- [ ] **Step 5: Confirm the exporter reaches the broker** (`kubectl logs` of the exporter shows no connection errors) with no change to Kafka's policy; if it is refused, add the exporter to `networkPolicyPeers`.
- [ ] **Step 6: Commit** — message lists the pinned Prometheus names and the exporter label.

### Task 2: The `telemetry` package

**Files:**
- Create: `src/main/java/telemetry/Telemetry.java`, `Traces.java`, `PipelineMetrics.java`
- Create: `src/test/java/testsupport/TelemetryTestSupport.java`, `src/test/java/telemetry/TracesTest.java`, `PipelineMetricsTest.java`, `TelemetryTest.java`
- Modify: `pom.xml` (`opentelemetry-bom` 1.66.0 import; `opentelemetry-api`, `-sdk`, `-sdk-extension-autoconfigure`, `-exporter-otlp` excluding `opentelemetry-exporter-sender-okhttp`, `opentelemetry-exporter-sender-jdk`; `opentelemetry-sdk-testing` test scope), `src/main/java/app/Launcher.java`

**Interfaces:**
- Produces:
  - `Telemetry.init(String serviceName)` — reads `System.getenv()`; package-private `init(String serviceName, Map<String,String> env)` for tests. No-op unless `OTEL_EXPORTER_OTLP_ENDPOINT` is non-blank; otherwise builds via `AutoConfiguredOpenTelemetrySdk` with `otel.service.name` defaulting to `serviceName`, then `install`.
  - `Telemetry.install(OpenTelemetry otel)` — makes it current and rebuilds the tracer and `PipelineMetrics`' instruments from it; `Telemetry.get()`; `Telemetry.close()` (shutdown, waits ≤ 5 s; no-op if never initialised).
  - `Traces.HEADER = "traceparent"`; `Span start(String name, SpanKind kind, Context parent)`; `Span startAt(String name, SpanKind kind, Context parent, long epochMillis)`; `String traceparent(Span span)` (null if the span context is invalid); `Context fromTraceparent(String value)` (root context with a remote parent, or `Context.root()` if null, blank, or invalid); `void inject(Span span, org.apache.kafka.common.header.Headers headers)` (adds the header only if valid); `Context extract(Headers headers)` (last `traceparent` header, else root). W3C propagator underneath.
  - `PipelineMetrics.Stage { ACCEPTED, STAGE1, STAGE2, GATEWAY }` (attribute values `accepted`, `stage1`, `stage2`, `gateway`); `latency(Stage stage, long detectedAtMillis, long nowMillis)` records `max(0, now − detectedAt) / 1000.0` with the **current** context; `playAccepted(String result)`, `pushProduced()`, `delivery(String result)`, `socketSend()`; `LATENCY_BUCKETS_SECONDS` (Global Constraints) set as the histogram's explicit-bucket advice.
  - Test support: `TelemetryTestSupport.install()` → installs an SDK with `AlwaysOn` sampling, a `SimpleSpanProcessor` over `InMemorySpanExporter`, and an `InMemoryMetricReader`; `spans()`, `metrics()`, `close()` (installs `OpenTelemetry.noop()` again). `AutoCloseable`.
  - `Launcher.main`: `Telemetry.init(service)` before running the main; one shutdown hook calling `Telemetry.close()`.

- [ ] **Step 1: Write the failing tests.**
  - `TracesTest.stringRoundTrip`: start a span, `fromTraceparent(traceparent(span))` → same trace id, parent span id = the span's id, remote.
  - `TracesTest.kafkaHeadersRoundTrip`: `inject` into `RecordHeaders`, `extract` → same trace id and span id.
  - `TracesTest.malformedIsRoot`: `fromTraceparent` of `null`, `""`, `"abc"`, `"00-00000000000000000000000000000000-0000000000000000-01"` → `Span.fromContext(ctx).getSpanContext().isValid()` false; `extract` of empty headers → same.
  - `TracesTest.offWritesNothing`: with `OpenTelemetry.noop()` installed, `traceparent(start(...))` is null and `inject` leaves the headers empty.
  - `TracesTest.startAtUsesGivenTime`: `startAt(..., 1_700_000_000_123L)` → exported span's start epoch nanos = `1_700_000_000_123_000_000`.
  - `PipelineMetricsTest.bucketsAreTheSpecs`: recorded histogram's boundaries equal `LATENCY_BUCKETS_SECONDS`.
  - `PipelineMetricsTest.latencyInSecondsWithExemplar`: inside a current span, `latency(GATEWAY, 1000, 1250)` → one point, `stage=gateway`, sum `0.25`, one exemplar whose trace id is the span's.
  - `PipelineMetricsTest.negativeLatencyRecordsZero`: `latency(STAGE1, 2000, 1000)` → count 1, sum 0.
  - `PipelineMetricsTest.countersCount`: `playAccepted("accepted")` ×2, `delivery("duplicate")` → `plays.accepted{result=accepted}` = 2, `deliveries{result=duplicate}` = 1.
  - `TelemetryTest.offWithoutEndpoint`: `init("x", Map.of())` → `Telemetry.get()` produces invalid spans.
  - `TelemetryTest.unreachableCollectorNeverBlocks`: `init("x", Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:9", "OTEL_METRIC_EXPORT_INTERVAL", "100"))`; 10,000 spans started and ended plus 10,000 `latency` records finish in under 2 s with no exception; `close()` returns within 6 s.
- [ ] **Step 2: Run** `mvn -q test -Dtest='TracesTest,PipelineMetricsTest,TelemetryTest'` → compile failure (classes missing).
- [ ] **Step 3: Implement** the three classes, the POM changes, and `Launcher`. Instruments are rebuilt in `install` and read through `Telemetry` at each call, so a test's `install` takes effect even after a class has already recorded.
- [ ] **Step 4: Run** the three tests → PASS; `make test` → PASS (telemetry is off, so nothing else changes); `mvn -q package -DskipTests && jar tf target/sports-notifier.jar | grep -c okhttp3` → 0.
- [ ] **Step 5: Commit.**

### Task 3: Poller → Event Processing → Kafka

**Files:**
- Modify: `src/main/java/feed/Poller.java` (`send`), `feed/HttpEventSink.java`, `util/SimpleHttp.java` (overload `postJson(String url, String json, Map<String,String> headers)`)
- Modify: `service/impl/EventProcessingServiceMain.java` (route), `EventProcessingServiceImpl.java` (accept span, `ARGV[29]`, latency, counter), `Outbox.java`, `OutboxRelay.java`, `service/PlayPublisher.java`, `KafkaPlayPublisher.java`, `src/test/java/testsupport/TestPublisher.java`
- Test: `feed/PollerTest`, new `feed/HttpEventSinkTest`, `service/impl/EventProcessingServiceImplTest`, `OutboxTest`, `OutboxRelayTest`, `KafkaPlayPublisherTest`

**Interfaces:**
- Consumes: Task 2's `Traces`, `PipelineMetrics`, `TelemetryTestSupport`.
- Produces:
  - `PlayPublisher.publish(GameEvent play, String traceparent)` (nullable → no header); `KafkaPlayPublisher` adds header `traceparent`.
  - `Outbox.Waiting(GameEvent play, String traceparent)` record; `Optional<Outbox.Waiting> waiting(String gameId)` replaces `play(String)` (traceparent null when the field is absent or empty).
  - `Outbox.send(PlayPublisher publisher, GameEvent play, String parentTraceparent, boolean retry)`: starts `produce` (parent `fromTraceparent(parentTraceparent)`, `produce.retry`), publishes with **its own** traceparent, ends the span when the future completes (status ERROR on failure).
  - Accept script: `ARGV[29]` = the `accept` span's traceparent or `""`; after the outbox `HSET`: `if ARGV[29] ~= '' then redis.call('HSET', KEYS[4], 'traceparent', ARGV[29]) end`. Update the script's ARGV comment.
  - `TestPublisher.traceparents()` → the traceparent of each publish, in order.

- [ ] **Step 1: Write the failing tests** (each installs `TelemetryTestSupport` unless it says off).
  - `PollerTest.pollSpanStartsAtDetectedAt`: fake clock 5000, fake sink records `Traces.traceparent(Span.current())` → non-null; exported `poll` span is a root, CLIENT, starts at 5000 ms, `poll.result` = the sink's result.
  - `HttpEventSinkTest.sendsTraceparentHeader`: a Javalin app on port 0 captures the header; posting inside a span → header equals the span's traceparent; outside any span (or telemetry off) → no header.
  - `EventProcessingServiceImplTest.acceptStoresTraceparentInOutbox`: process an event inside a span → `outbox:<gameId>` field `traceparent` has the `accept` span's trace id; `accept` is the child of the outer span; `accept.result=accepted`; `pipeline.latency{stage=accepted}` count 1; `plays.accepted{result=accepted}` 1.
  - `EventProcessingServiceImplTest.telemetryOffStoresNoTraceparent`: telemetry off → the slot has no `traceparent` field and `TestPublisher.traceparents()` is `[null]`.
  - `EventProcessingServiceImplTest.duplicateCountsButNoLatency`: the same event twice → `plays.accepted{result=duplicate}` 1 and still one `accepted` latency.
  - `OutboxRelayTest.relayJoinsTheOriginalTrace`: accept with a publisher that fails; run the relay with a working one → its `produce` span has `produce.retry=true` and the `accept` span's trace id; the published traceparent is that `produce` span's.
  - `OutboxRelayTest.slotWithoutTraceparentRelaysAsNewTrace`: `HSET outbox:<g> version 1 play <json>` by hand (no field) → relay publishes, `produce` is a root span, no exception.
  - `OutboxTest.waitingReadsTraceparent`: field present → returned; absent or `""` → null.
  - `KafkaPlayPublisherTest.headerCarriesTraceparent` (existing Testcontainers setup): consumed record's `traceparent` header equals what was passed; `null` → no header.
- [ ] **Step 2: Run** those tests → FAIL (compile errors on the new signatures).
- [ ] **Step 3: Implement.** The route makes `Traces.fromTraceparent(ctx.header(Traces.HEADER))` current around `processJson`. `processEvent` starts `accept` as a child of `Context.current()` and makes it current for the script call, the counter, and the `accepted` latency (`event.getTimestamp()` as `detectedAt`, the service's `nowMillis`), then `outbox.send(publisher, play, Traces.traceparent(accept), false)`. The relay passes `w.traceparent()` and `retry=true`. Update every `publish(play)` / `outbox.play` caller and test.
- [ ] **Step 4: Run** the tests → PASS; `make test` → PASS (including `EventProcessingBurstTest`, `KafkaOutageTest`).
- [ ] **Step 5: Commit.**

### Task 4: Stage 1's `fanout`

**Files:**
- Modify: `src/main/java/service/impl/Stage1Worker.java` (`handle`)
- Test: `src/test/java/service/impl/Stage1WorkerTest.java`

**Interfaces:**
- Consumes: `Traces.extract/inject/start`, `PipelineMetrics.latency(STAGE1, …)`, `pushProduced()`.
- Produces: every `notifications` record carries header `traceparent` = the `fanout` span's context.

- [ ] **Step 1: Write the failing tests** (existing Kafka setup, `TelemetryTestSupport`).
  - `fanoutHeadersCarryTheSpan`: produce one scoring play with a `traceparent` header from a test span; after `pollOnce`, every consumed `notifications` record's header extracts to the `fanout` span; `fanout` is CONSUMER, child of the test span, `fanout.pushes` = records produced, `fanout.alerts` = alerts in the result; `pipeline.latency{stage=stage1}` count 1; `pushes.produced` = records produced.
  - `skippedPlayRecordsNoLatency`: deliver the same play again (version ≤ `det:` version) → a second `fanout` span in the same trace with `fanout.skipped=true`, no new `stage1` latency, no new records.
  - `playWithoutHeaderIsRoot`: no header → `fanout` is a root span; messages still sent.
- [ ] **Step 2: Run** `mvn -q test -Dtest=Stage1WorkerTest` → the three FAIL.
- [ ] **Step 3: Implement** in `handle`: start `fanout` from `Traces.extract(r.headers())`, current for the whole method; `inject` into each `ProducerRecord`'s headers before `send`; `pushProduced()` per record; after a successful `flush`, `latency(STAGE1, play.getTimestamp(), System.currentTimeMillis())`; record the exception and status ERROR on failure; end in `finally`.
- [ ] **Step 4: Run** `Stage1WorkerTest` → PASS; `make test` → PASS.
- [ ] **Step 5: Commit.**

### Task 5: Stage 2's `deliver` and the gateway's `send`

**Files:**
- Modify: `src/main/java/model/NotificationMessage.java` (`transient String traceparent` with getter and setter), `service/impl/Stage2Worker.java` (`pollOnce`), `service/impl/Delivery.java` (`deliverAll` returns results; `args` adds the field), `gateway/GatewayChannel.java` (`deliverLocally`)
- Test: `service/impl/Stage2WorkerTest`, `DeliveryTest`, `gateway/GatewayChannelTest`

**Interfaces:**
- Consumes: Task 2 and Task 4's header.
- Produces:
  - `List<Boolean> Delivery.deliverAll(List<NotificationMessage> batch)` — `true` delivered, `false` duplicate, one per message in order (from the pipeline's responses).
  - Gateway message JSON: `{"userId", "payload", "traceparent"}`; `traceparent` present only when the message has one.

- [ ] **Step 1: Write the failing tests.**
  - `DeliveryTest.deliverAllReportsEachResult`: a batch of [new, same again, another new] → `[true, false, true]`.
  - `DeliveryTest.gatewayMessageCarriesTraceparent`: a message with `traceparent` set → the published JSON (subscribe to `gw:<id>` in the test) has that field; without → no field.
  - `Stage2WorkerTest.deliverSpanPerMessage` (`TelemetryTestSupport`): records with headers from two different test spans → two `deliver` spans, CONSUMER, each the child of its record's span, `deliver.result=delivered`; `pipeline.latency{stage=stage2}` count 2; `deliveries{result=delivered}` 2; the published JSON carries each `deliver` span's traceparent.
  - `Stage2WorkerTest.redeliveryIsDuplicate`: the same record again → `deliver.result=duplicate`, no new `stage2` latency.
  - `GatewayChannelTest.sendSpanAndLatency` (`TelemetryTestSupport`): publish `{"userId":u,"payload":{"type":"notification","notification":{…,"detectedAt":"<iso>"}},"traceparent":<tp>}` with a socket for `u` → a `send` span, child of `<tp>`, `send.sockets=1`; `pipeline.latency{stage=gateway}` count 1 with an exemplar carrying `<tp>`'s trace id; `socket.sends` 1.
  - `GatewayChannelTest.garbageTraceparentStillSends`: `"traceparent":"abc"` → the socket gets the payload; `send` is a root span; latency still recorded.
  - `GatewayChannelTest.unreadCountHasNoSpan`: an `unread_count` payload → delivered, no span, no latency, no `socket.sends`.
  - `GatewayChannelTest.noOpenSocketNoLatency`: user known but every socket closed → no latency, no `socket.sends`.
- [ ] **Step 2: Run** `mvn -q test -Dtest='DeliveryTest,Stage2WorkerTest,GatewayChannelTest'` → FAIL.
- [ ] **Step 3: Implement.** Stage 2: for each readable record, start `deliver` from `Traces.extract(r.headers())` and set `m.setTraceparent(Traces.traceparent(span))`; call `deliverAll`; then per message, with its span current, set `deliver.result`, record `delivery(result)` and (delivered only) `latency(STAGE2, m.getDetectedAt(), now)`, end the span. If `deliverAll` throws, end every span with status ERROR before rethrowing. Comment that a batch's spans share the pipeline's round trip (spec 3.2). Gateway: only for `payload.type == "notification"`, start `send` from `fromTraceparent` of the field, current while writing; if at least one open socket was written, `socketSend()` and `latency(GATEWAY, detectedAt, now)` with `detectedAt` parsed from `payload.notification.detectedAt` (skip the latency if it is missing or unparsable).
- [ ] **Step 4: Run** those tests → PASS; `make test` → PASS.
- [ ] **Step 5: Commit.**

### Task 6: Chart wiring and the dashboard

**Files:**
- Modify: `deploy/helm/sports-notifier/templates/_helpers.tpl` (new `sports-notifier.otelEnv`, arg `dict "root" $ "name" <service>`, empty when disabled), `java-services.yaml`, `checker-cronjob.yaml`, `replay-cronjob.yaml`, `simulator-cronjob.yaml`, `espn-poller.yaml` (include it beside `baseEnv`)
- Modify: `deploy/helm/sports-notifier/files/pipeline.json` (the real dashboard)

**Interfaces:**
- Consumes: Task 1's Prometheus names.
- Produces: dashboard uid `pipeline`, panel titles exactly: `Push latency, poller → gateway`, `Time since the poller saw it, per stage`, `Kafka lag`, `Throughput`.

- [ ] **Step 1: Add `otelEnv`** with the Global Constraints' variables; `OTEL_SERVICE_NAME` is the service key (`checker`, `replay`, `simulator`, `espn-poller` for the Jobs and the poller). Check: `helm template … | grep -A1 'OTEL_SERVICE_NAME' | grep -c value` equals the number of Java workloads; with `--set observability.enabled=false` it is 0.
- [ ] **Step 2: Write `pipeline.json`.** Row 1: p50, p99, p99.9 of `histogram_quantile(q, sum by (le) (rate(pipeline_latency_seconds_bucket{stage="gateway"}[30s])))`, unit seconds, target `"exemplar": true`. Row 2: p99 `by (le, stage)`. Row 3: `sum by (consumergroup) (kafka_consumergroup_lag{consumergroup=~"stage1|stage2|history"})`. Row 4: `sum(rate(plays_accepted_total{result="accepted"}[30s]))` and `sum(rate(socket_sends_total[30s]))`. Every panel `"interval": "5s"`; `time.from: "now-15m"`; `refresh: "5s"`; an annotation query on the built-in Grafana source, `type: "tags"`, `tags: ["chaos"]`, enabled. Use the names pinned in Task 1 if they differ.
- [ ] **Step 3: Look at it.** `make up`, then `make check SET=ci SPEED=60` while `make grafana` runs. Expected: all four rows have data; row 2 shows four lines in order accepted ≤ stage1 ≤ stage2 ≤ gateway; clicking an exemplar dot in row 1 opens a Tempo trace with `poll`, `accept`, `produce`, `fanout`, `deliver`, `send`.
- [ ] **Step 4: Commit.**

### Task 7: `make trace-check` and CI

**Files:**
- Create: `scripts/trace-check.sh`
- Modify: `Makefile` (`trace-check`), `.github/workflows/ci.yml` (`make cluster up smoke check trace-check e2e SET=ci SPEED=60`), `.github/workflows/chaos.yml` (`trace-check` after `chaos`)

**Interfaces:**
- Produces: `scripts/trace-check.sh` — exit 0 with `trace-check: PASS` lines, or exit 1 naming every missing piece.

- [ ] **Step 1: Write the script.** Port-forwards to `svc/lgtm` 3200 and 9090 on free local ports (killed on exit); window = last 15 minutes; retries the whole check for up to 30 s. Checks:
  1. Tempo `GET /api/search?q={ resource.service.name = "gateway" && name = "send" }&start=&end=&limit=20`; for each trace id, `GET /api/traces/<id>`; collect every `service.name` value and span name with jq (`[.. | objects | select(.key? == "service.name") | .value.stringValue] | unique`). PASS if some trace has `event-processing`, `stage1-worker`, `stage2-worker`, `gateway`, one of `checker|replay|simulator|espn-poller`, and a span named `poll`.
  2. Prometheus: `count by (stage) (pipeline_latency_seconds_bucket)` has all four stages; `count by (consumergroup) (kafka_consumergroup_lag)` has `stage1`, `stage2`, `history`.
  3. `GET /api/v1/query_exemplars?query=pipeline_latency_seconds_bucket{stage="gateway"}` has ≥ 1 exemplar with `trace_id`, and Tempo answers 200 for that id.
- [ ] **Step 2: Prove it fails.** On the laptop with nothing run since `make up`: `make trace-check` → exit 1 naming the missing trace and stages. Then `make check SET=ci SPEED=60 trace-check` → PASS.
- [ ] **Step 3: CI.** Push the branch and open a draft PR. Expected: the cluster job is green. If it fails for memory (pods `OOMKilled`, `Evicted`, or `Pending` for `Insufficient memory` in `make logs`), **stop and ask the user** (the fallback is CI without the pod and `trace-check` only on the laptop and in the Chaos workflow).
- [ ] **Step 4: Commit** (with any CI fixes).

### Task 8: Chaos marks, links, and #54 items 1–4

**Files:**
- Modify: `scripts/chaos-lib.sh`, `scripts/chaos.sh`, `scripts/test-chaos-scripts.sh`

**Interfaces:**
- Produces (in `chaos-lib.sh`): `chaos_grafana_start` (if `svc/lgtm` exists, port-forward it to `CHAOS_GRAFANA_PORT`, default 3300, and set `CHAOS_GRAFANA=1`; otherwise `CHAOS_GRAFANA=0`); `chaos_mark <scenario> <text>` (`POST /api/annotations` with `{"time": <epoch ms>, "tags": ["chaos", "<scenario>"], "text": "<scenario>: <text>"}`; on failure one log line, return 0; no-op when `CHAOS_GRAFANA=0`); `chaos_grafana_stop`; `chaos_now_ms`.

- [ ] **Step 1: Write the failing script tests** in `test-chaos-scripts.sh` (fake `kubectl`, fake `curl` on the PATH):
  - `mark_failure_is_not_fatal`: `curl` exits 7 → `chaos_mark` returns 0 and prints one line.
  - `no_lgtm_skips_marks`: fake `kubectl get svc lgtm` fails → `CHAOS_GRAFANA=0`, `chaos_mark` calls no `curl`.
  - `cut_restart_note_uses_count_before`: restart count 1 before and after the cut → no "was restarted during the cut" line (#54.1).
  - `rollout_ignores_terminating`: the pod listing includes a pod with a `deletionTimestamp` → it is not counted as new (#54.4).
  - `missing_run_started_deletes_job`: a checker log that never prints `run started` while the Job stays alive → `chaos.sh` deletes the Job and reports `the checker never started its run` without waiting for the Job (#54.3; use `CHAOS_START_WAIT=2` to shorten the 300 s wait).
- [ ] **Step 2: Run** `bash scripts/test-chaos-scripts.sh` → the five FAIL.
- [ ] **Step 3: Implement.** #54.1: record `chaos_restarts` before the cut, compare with that. #54.2: `left` and `back` measured from `t_run`, like `fault_at`. #54.3: `CHAOS_START_WAIT` (default 300); if the line never came and the Job is alive, delete the Job as `cleanup` does, then `wait`. #54.4: new pods = gateway pods without `metadata.deletionTimestamp`. Marks at `run started`, `fault`, `healed` (cut heal, rollout done), `run ended`, each with `chaos_now_ms` at that instant. After each scenario's line, print `  dashboard: http://localhost:3000/d/pipeline?from=<run started ms − 30000>&to=<run ended ms + 30000>` to the summary (skipped when `CHAOS_GRAFANA=0`). `chaos_grafana_start` at the top, `chaos_grafana_stop` in `cleanup`.
- [ ] **Step 4: Run** `bash scripts/test-chaos-scripts.sh` → all ok.
- [ ] **Step 5: Run the suite.** `make chaos` with `make grafana` open. Expected: `8 of 8 passed`; every scenario has a dashboard line; the marks show on the dashboard. Take the screenshots for the PR: row 1–3 of `stage1-crash` and `broker-crash` with their marks, and the Tempo trace opened from an exemplar in the `stage1-crash` spike (it should show a second `fanout` with `fanout.skipped=true` or a long gap before `fanout`). Save them under `target/chaos/<run>/` (not committed).
- [ ] **Step 6: Commit.**

### Task 9: #54 items 5–6 and the checker's latency line

**Files:**
- Modify: `src/main/java/gateway/GatewayChannel.java` (`boolean holds(String userId)`), `gateway/LiveSocket.java` (`released`), `checker/Verifier.java`, `checker/Report.java`
- Test: `gateway/GatewayChannelTest`, `checker/VerifierTest`, `checker/ReportTest`

**Interfaces:**
- Produces: `GatewayChannel.holds(String userId)` — `socketsByUser.containsKey(userId)`.

- [ ] **Step 1: Write the failing tests.**
  - `GatewayChannelTest.holdsTracksSockets`: true after `register`, still true with one of two sockets unregistered, false after the last.
  - `VerifierTest.legacyClaimMessage`: a missing push whose claim holds `'1'` → finding text contains `no delivery time in its claim` and not `never claimed`.
  - `ReportTest.latencyLine`: the line reads `latency detectedAt → socket (checker): p50 … p99 … max …` and contains no `preview`.
- [ ] **Step 2: Run** them → FAIL.
- [ ] **Step 3: Implement**; `released` uses `channel.holds(userId)`.
- [ ] **Step 4: Run** them → PASS; `make test` → PASS; `bash scripts/test-chaos-scripts.sh` still ok (`chaos.sh` parses `max … ms` from the latency line).
- [ ] **Step 5: Commit.**

### Task 10: Overhead, the other checks, docs, PR

**Files:**
- Modify: `DESIGN.md`, `CLAUDE.md`, `AGENTS.md`, `docs/study/phase-4-quiz.md`

- [ ] **Step 1: Overhead.** From the main checkout: `make down && make cluster up && make check SET=evening SPEED=60`; save the latency line. From this worktree: `make down && make cluster up && make check SET=evening SPEED=60`; save the line. Both go in the PR.
- [ ] **Step 2: The other checks** on the laptop: `make check-selftest`, `make replay-detectors` (golden timelines unchanged), `scripts/persistence-check.sh` → all pass.
- [ ] **Step 3: Docs.** `DESIGN.md` build order 4.3: what was built and **merged** once it is. `CLAUDE.md` and `AGENTS.md`: "New in Phase 4.3" (the pod, `make grafana`, `make trace-check`, the trace's carriers, `pipeline.latency` and exemplars, the dashboard and chaos marks), `lgtm` and OpenTelemetry in the architecture table and stack line, test counts, next step (#36 before Phase 5, then Phase 5). `docs/study/phase-4-quiz.md`: a 4.3 section (why the outbox carries `traceparent`; why one histogram of "time since detectedAt"; what an exemplar is; why lag is scraped and not reported by workers; why 120 s buckets; how a redelivery shows in a trace).
- [ ] **Step 4: Commit and mark the PR ready** with: the chaos summary, the screenshots, the overhead lines, and `Closes #54`. Then the final whole-branch review; fix its findings; merge when CI is green.

---

## Self-review notes

- Spec 1.1–1.5 → Task 1 (+ Task 6 for the dashboard content). 2.1–2.2 → Tasks 2, 6. 3.1–3.3 → Tasks 3–5. 3.4 is out of scope by design. 4.1–4.4 → Tasks 1, 2, 3–5. 5 → Task 6. 6.1–6.3 → Task 8. 7 → Task 9. 8.1–8.4 → Tasks 2–9. Done-when 1–8 → Tasks 4–10.
- `Telemetry.close()` runs from one hook in `Launcher` (spec 2.1, revised with this plan).
