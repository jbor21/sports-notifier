# Phase 3.1: Kafka pipeline — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Redis Stream with Kafka (Strimzi, one broker on k3d), close the script-then-publish and dead-worker holes with a Redis outbox and consumer groups, deliver each notification exactly once in effect, and route each notification to the gateway holding the user's socket.

**Architecture:** The Lua script accepts a play and, in the same step, puts it in a per-game outbox slot; Event Processing produces it to `game-events` and clears the slot on Kafka's confirmation, with a retry loop for leftovers. Stage 1 (group `stage1`) turns each play into one `notifications` message per user; Stage 2 (group `stage2`) delivers each message through one Lua script that dedups, writes history, and publishes on the channel of each gateway in `conn:<userId>`.

**Tech Stack:** Java 17, Maven, Javalin 6.1.6, Jedis 4.4.7, Gson, Apache Kafka `kafka-clients` 4.x, Testcontainers (`org.testcontainers:kafka`, image `apache/kafka-native`), Strimzi (Helm chart `oci://quay.io/strimzi-helm/strimzi-kafka-operator`), Helm, k3d.

**Spec:** `docs/superpowers/specs/2026-10-06-phase-3-1-kafka-pipeline-design.md`. `DESIGN.md` is the parent; if they disagree, `DESIGN.md` wins.

## Global Constraints

- Branch `phase-3-1-kafka`, worktree `.worktrees/phase-3-1-kafka`. Main is merged into it, never rebased (the branch is pushed; no force-pushes). Done before Task 1: PR #4 and PR #5 are in.
- CI green after every task (`make test`, then `make cluster up smoke` in GitHub Actions). The old path keeps working until its replacement lands (spec section 7).
- Core distributed code (Tasks 1, 2, 4, 5, 6, 7, 8) is built inline in small steps the user reads, test first. Task 3 may go to a subagent and is then walked through with the user.
- Every Jedis handle in try-with-resources; `JedisPoolHolder.create()` for pools, `JedisPoolHolder.dedicated()` only for blocking subscribe. Redis key names only from `util/RedisKeys.java`. Config from env vars with defaults.
- Comment density matches the code: explain *why* at the Lua scripts, the outbox, the consumers, and the routing; stay quiet elsewhere.
- Kafka versions: `kafka-clients` and the Strimzi-run broker are the same major version (4.x), both pinned to exact versions, and the broker version is on the pinned Strimzi release's supported list. Pick current releases at Task 3 and write the versions into `pom.xml`, the `Makefile`, and `values.yaml`.
- Topic names and consumer group names are constructor arguments of every Kafka class; production values come from `util/Topics.java`. Tests use unique topic and group names per test (`"game-events-" + UUID`), so tests never read each other's records.
- Values copied from the spec:

| Setting | Value |
|---|---|
| `game-events` | 12 partitions, retention 7 days (604800000 ms), key = game id |
| `notifications` | 48 partitions, retention 1 day (86400000 ms), key = user id |
| Replication / `min.insync.replicas` | 1 / 1 locally (values file); 3 / 2 in the cloud (Phase 5) |
| Broker | KRaft, 1 node locally, persistent storage 2Gi, heap 512m, `auto.create.topics.enable=false` |
| Ingest producer | `acks=all`, `enable.idempotence=true`, `linger.ms=0`, `max.block.ms=2000` |
| Stage 1 producer | `acks=all`, `enable.idempotence=true`, `linger.ms=5` |
| Consumers | `enable.auto.commit=false`, `auto.offset.reset=earliest`, `partition.assignment.strategy=org.apache.kafka.clients.consumer.CooperativeStickyAssignor` |
| Groups | `stage1`, `stage2` |
| No-score heal | 30000 ms |
| Retry loop | every 1000 ms, entries older than 5000 ms |
| `processed:<eventId>` | TTL 3600 s (unchanged) |
| `s1:last:<gameId>` | TTL 8 days (691200 s), set if higher |
| `notif:<messageId>:<userId>` | TTL 2 days (172800 s), `SET NX` |
| `conn:<userId>` | entry expiry 90 s, renewed every 30 s, key TTL 90 s, times from Redis `TIME` |
| Graceful-shutdown close code | 4000 |
| Ids after the script | `<gameId>:v<version>` |
| Game ids | `<league lowercase>-<espnEventId>`, e.g. `nfl-401872948` |

## Review Focus

Failure modes the spec implies but its listed tests do not exercise. Each has a test in the owning task.

1. **A poison message** (unparseable JSON on `game-events` or `notifications`): the worker logs it, commits past it, and keeps going; it must not retry it forever and stall the lane. Tests in Task 5 and Task 6.
2. **A user with no open socket:** delivery writes history, publishes nothing, and does not fail. Test in Task 6.
3. **A large audience** (2,000 followers of one team): Stage 1 pages the audience and every follower gets exactly one message. Test in Task 5.
4. **A slow, not dead, Kafka:** the retry loop re-sends a play whose first send is still in flight. The play may land twice with the same `version`, never out of order, and the outbox is cleared once. Test in Task 4.
5. **A gateway message for a user who just closed their last socket:** dropped silently, no exception, no effect on other users. Test in Task 7.

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| `feed/EspnScoreFeed.java`, `gateway/EspnProxyHandler.java` | Build `<league>-<id>` game ids | 1 |
| `model/GameEvent.java` | Gains `long version` | 2 |
| `service/ProcessResult.java` | Gains `PENDING` | 2 |
| `service/impl/EventProcessingServiceImpl.java` | The accept script (pending, heals, version, outbox) and the publish-then-clear flow | 2, 4 |
| `service/PlayPublisher.java` | `CompletableFuture<Void> publish(GameEvent play)` | 2 |
| `service/impl/RedisStreamPublisher.java` | The course XADD, behind `PlayPublisher` (deleted in Task 8) | 2 |
| `service/impl/Outbox.java` | The clear script and the queries the retry loop needs | 2 |
| `feed/Poller.java` | `pending` logs at debug | 2 |
| `util/Topics.java`, `util/KafkaSettings.java` | Topic/group names; client configs from env; wait-for-Kafka | 3 |
| `deploy/helm/.../templates/kafka.yaml`, `kafka-topics.yaml`, values, `Makefile`, `pom.xml`, smoke | Broker, topics, network policy, operator install, dependencies | 3 |
| `src/test/java/testsupport/KafkaTestSupport.java` | One Testcontainers broker per test JVM; topic creation; pause/unpause; read-all helper | 3 |
| `service/impl/KafkaPlayPublisher.java` | Ingest producer | 4 |
| `service/impl/OutboxRelay.java` | The retry loop | 4 |
| `model/NotificationMessage.java` | One user's message on `notifications` | 5 |
| `service/impl/Audience.java` | Phase 2's set-operation audience, moved out of `PubSubWorkerImpl` | 5 |
| `service/impl/Stage1Progress.java` | `s1:last` read and set-if-higher | 5 |
| `service/impl/Stage1Worker.java`, `Stage1WorkerMain.java` | Stage 1 consumer | 5 |
| `service/impl/Delivery.java` | The deliver script | 6 |
| `service/impl/Stage2Worker.java`, `Stage2WorkerMain.java` | Stage 2 consumer | 6 |
| `gateway/ConnectionRegistry.java` | `conn:<userId>` add, renew, remove, remove-all | 7 |
| `gateway/GatewayChannel.java`, `gateway/ClientSocket.java` | Subscribe to `gw:<id>`; local sockets per user (replaces `NotificationFanOut`) | 7 |
| `scripts/ws-listen.py` | Smoke helper: open the WebSocket with a cookie, wait for a frame containing a string (Python standard library only) | 7 |

---

### Task 1: Game ids become `<league>-<espnId>`

**Files:**
- Modify: `src/main/java/feed/EspnScoreFeed.java` (the `"espn-" + id` at the snapshot and in the "score missing" log line)
- Modify: `src/main/java/gateway/EspnProxyHandler.java:91`
- Modify: `src/main/java/model/GameSnapshot.java` (javadoc example)
- Test: `src/test/java/feed/EspnScoreFeedTest.java`, `src/test/java/gateway/EspnProxyHandlerTest.java` (create if absent)

**Interfaces:**
- Produces: every snapshot's `gameId()` and every `/api/upcoming` `gameId` is `league.toLowerCase(Locale.ROOT) + "-" + espnEventId`.

- [ ] **Step 1: Update the expectations in `EspnScoreFeedTest`** (`nflLiveParsesPositionAndScore` → `"nfl-401872965"`, `brokenGameIsSkipped` → `"nhl-401891803"`) and add:

```java
@Test
void gameIdNamesTheLeagueNotTheProvider() throws IOException {
    assertEquals("mlb-401907990", only("mlb-live.json", "MLB").gameId());
}
```

- [ ] **Step 2: Add `upcomingGameIdNamesTheLeague`** to `EspnProxyHandlerTest`: feed the handler's game-parsing method a scheduled NFL event with id `401872948` and assert `gameId` is `"nfl-401872948"`. If the parsing method is private, make it package-private `static Map<String, Object> parseGame(JsonObject event, String league)`.
- [ ] **Step 3: Run** `mvn -q test -Dtest='EspnScoreFeedTest,EspnProxyHandlerTest'`. Expected: FAIL on the `espn-` ids.
- [ ] **Step 4: Change both builders.** One shared helper, `feed.GameIds.of(String league, String espnId)`, used by both, so the format lives in one place.
- [ ] **Step 5: Run** `make test`. Expected: all pass. Tests that build `GameSnapshot`s with `espn-1` literals are fixtures and stay as they are.
- [ ] **Step 6: Commit** "Game ids are <league>-<espnId>, built in one place".

---

### Task 2: The accept script: pending, heals, version, outbox

**Files:**
- Modify: `src/main/java/model/GameEvent.java` (add `long version` with javadoc, getter, `withVersion(long)` returning a copy)
- Modify: `src/main/java/service/ProcessResult.java` (add `PENDING`)
- Modify: `src/main/java/service/EventProcessingService.java` (remove `publishEvent`)
- Modify: `src/main/java/service/impl/EventProcessingServiceImpl.java`
- Create: `src/main/java/service/PlayPublisher.java`, `src/main/java/service/impl/RedisStreamPublisher.java`, `src/main/java/service/impl/Outbox.java`
- Modify: `src/main/java/service/impl/EventProcessingServiceMain.java` (wiring; `pending` → `200 {"result":"pending"}` needs no change, it is `name().toLowerCase()`)
- Modify: `src/main/java/util/RedisKeys.java` (`OUTBOX_PREFIX = "outbox:"`, `OUTBOX_PENDING = "outbox:pending"`)
- Modify: `src/main/java/feed/Poller.java` (`levelFor`: `pending` → `DEBUG`)
- Test: `src/test/java/service/impl/EventProcessingServiceImplTest.java`, `src/test/java/service/impl/EventProcessingHealTest.java` (new), `src/test/java/service/impl/OutboxTest.java` (new), `src/test/java/feed/PollLoggingTest.java`, `src/test/java/service/impl/EventProcessingBurstTest.java`

**Interfaces:**
- Produces:
  - `interface PlayPublisher { CompletableFuture<Void> publish(GameEvent play); }` — completes when the play is stored by the log; completes exceptionally on failure.
  - `EventProcessingServiceImpl(JedisPool pool, PlayPublisher publisher, LongSupplier nowMillis)`. `processEvent` runs the script; on accept it calls `publisher.publish(play.withVersion(v)).thenRun(() -> outbox.clear(gameId, v))` and returns `ACCEPTED` without waiting for the future.
  - `Outbox(JedisPool pool)`: `boolean clear(String gameId, long version)` (deletes `outbox:<gameId>` and its `outbox:pending` member only if the stored play's version equals `version`); `List<String> gamesWaitingSince(long olderThanMillis)` (members of `outbox:pending` with score ≤ the argument); `Optional<GameEvent> play(String gameId)`.
  - Game record fields added: `version` (counter), `staleSince` (millis, absent unless the no-score heal is counting).
- Script contract (`PROCESS_EVENT_SCRIPT`): KEYS = game hash, `processed:<eventId>`, `games:recent`, `outbox:<gameId>`, `outbox:pending`. ARGV adds `nowMillis`, heal window `30000`, and the play JSON without version (the script splices `version` in with `cjson`). Returns `0` duplicate, `-1` stale, `-2` pending, else the new `version` (≥ 1).

- [ ] **Step 1: Write `EventProcessingHealTest`** with a fake `PlayPublisher` that records plays and returns completed futures, and a mutable clock. Helper `nhl(String id, long seq, int home, int away)` for game `nhl-1`. Tests, with the spec's values:

```java
@Test void goalHealAcceptsMorePointsInSamePeriodAndLowersTheBar()
// accept seq 10202410000004 (p2, 4:01 left, 0-4) ; then seq 10202295000005 (p2, 11:45 left, 0-5)
// -> ACCEPTED, hget lastSeq == "10202295000005", homeScore/awayScore saved 0-5
@Test void goalHealDoesNotAcceptSameScoreOlder()
// after 10202410000004 (0-4): 10202295000004 (0-4) -> STALE
@Test void goalHealNeedsTheSamePeriod()
// after p3 snapshot 10302000000004 (0-4): p2 snapshot 10202295000005 (0-5) -> STALE
@Test void noScoreHealAcceptsAfterThirtySeconds()
// after 10202410000004: older same-score plays at t=0 and t=29_999 -> STALE; at t=30_000 -> ACCEPTED,
// lastSeq equals that play's seq
@Test void anyAcceptClearsStaleSince()
// start the heal timer, then accept a genuinely newer play -> hget staleSince == null
@Test void versionCountsAcceptsOnly()
// accept (v1), duplicate, stale, pending (outbox held), heal -> versions 1,1,1,1,2 in the record
@Test void healsIgnoreSimulatorSeqs()
// accept simulator-style seq 1790000000000000005 (score 5-0); a play from an older run,
// seq 1789999999000000009 (score 9-0) -> STALE, lastSeq unchanged (spec test 14)
```

- [ ] **Step 2: Write `OutboxTest`**:

```java
@Test void newerPlayIsPendingWhileOlderIsUnsent()
// publisher that never completes; accept seq 10 -> ACCEPTED; seq 11 -> PENDING; saved score still seq 10's
@Test void clearWithStaleVersionLeavesNewerEntry()
// outbox holds version 2; clear("g", 1) -> false, entry still there; clear("g", 2) -> true, gone from outbox:pending
@Test void acceptWritesPlayWithVersionToOutbox()
// after accept, outbox.play(gameId) has version 1 and the event id; outbox:pending has the game
@Test void confirmedPublishClearsOutbox()
// completing publisher -> after processEvent, outbox:<gameId> absent
```

- [ ] **Step 3: Update existing tests:** `EventProcessingServiceImplTest` constructs the service with a completing fake publisher; `EventProcessingBurstTest`: each thread retries on `PENDING` (1 ms pause between tries), like the poller; assert final `homeScore` 50, `lastSeq` 50, record `version` equals the count of `ACCEPTED` answers, and the published plays (the fake publisher's list) have `version` 1..N in order with strictly rising `seq` (spec test 13); `PollLoggingTest` adds `pendingIsDebug` for `SCORE_UPDATE` and `GAME_SYNC`.
- [ ] **Step 4: Run** `make test`. Expected: the new tests fail (no `PENDING`, no heals, no `version`); the rest compile after the constructor change.
- [ ] **Step 5: Rewrite `PROCESS_EVENT_SCRIPT`** in the spec's order (section 1.5): duplicate, pending, newer (3 ways), accept. The heals compare `math.floor(seq / 1e10)` (phase, period, half) between the play and `lastSeq`, and the play's `home + away` with the saved scores, and apply only when both `seq` values are below `1e14` (game positions; simulator values are above and use `seq > lastSeq` only). Keep the javadoc's step list and KEYS/ARGV table current, and say *why* the pending check comes before the newer check (it must not set `staleSince` for a play it will not consider).
- [ ] **Step 6: Implement `Outbox`** with a small `CLEAR_SCRIPT` (read `outbox:<gameId>`, `cjson.decode`, compare `version`, `DEL` and `ZREM` only on a match).
- [ ] **Step 7: Implement `RedisStreamPublisher`** with the existing `XADD … MAXLEN ~1000` and a completed future. `EventProcessingServiceMain` wires it with `System::currentTimeMillis`. This is the bridge: the stream publish runs **clear** until Task 4.
- [ ] **Step 8: Run** `make test`. Expected: all pass, including the 50-thread burst (final `homeScore` 50, `lastSeq` 50, `version` = number of accepted answers).
- [ ] **Step 9: Commit** in at least three commits the user can read: (a) `PENDING`, outbox, clear; (b) `version`; (c) the two heals.

---

### Task 3: Infrastructure: Strimzi, broker, topics, test broker

**Files:**
- Modify: `Makefile` (`cluster`: install Strimzi; `up`: wait for the Kafka resource)
- Create: `deploy/helm/sports-notifier/templates/kafka.yaml` (`KafkaNodePool` + `Kafka`), `templates/kafka-topics.yaml`
- Modify: `deploy/helm/sports-notifier/values.yaml`, `values-local.yaml`, `templates/_helpers.tpl` (a `kafkaEnv` helper giving `KAFKA_BOOTSTRAP`), `templates/java-services.yaml` (services with `kafka: true` get it)
- Modify: `pom.xml` (`org.apache.kafka:kafka-clients`; test scope `org.testcontainers:kafka` and `org.testcontainers:junit-jupiter`)
- Create: `src/main/java/util/Topics.java`, `src/main/java/util/KafkaSettings.java`
- Create: `src/test/java/testsupport/KafkaTestSupport.java`, `src/test/java/testsupport/KafkaTestSupportTest.java`
- Modify: `scripts/smoke-test.sh`

**Interfaces:**
- Produces:
  - `Topics.GAME_EVENTS = "game-events"`, `Topics.NOTIFICATIONS = "notifications"`, `Topics.STAGE1_GROUP = "stage1"`, `Topics.STAGE2_GROUP = "stage2"`.
  - `KafkaSettings.bootstrap()` (env `KAFKA_BOOTSTRAP`, default `localhost:9092`); `Properties ingestProducer(String bootstrap)`, `Properties fanoutProducer(String bootstrap)`, `Properties consumer(String bootstrap, String groupId)` with the Global Constraints values; `void awaitKafkaOrExit(String serviceName)` (AdminClient `describeCluster` every 2 s, exit after 60 s, same log line style as `JedisPoolHolder.awaitRedisOrExit`).
  - `KafkaTestSupport.bootstrap()` (starts one `apache/kafka-native` container per JVM on first call); `createTopic(String name, int partitions)`; `List<ConsumerRecord<String,String>> readAll(String topic, Duration wait)`; `pause()` / `unpause()` (Docker pause through `DockerClientFactory.instance().client()`).

- [ ] **Step 1: `KafkaTestSupportTest`:** create a topic with 12 partitions, produce one record with `KafkaSettings.ingestProducer`, `readAll` returns it; `pause()` then a send with `max.block.ms=2000` fails within 5 s; `unpause()` then a send succeeds. Run `make test`; expected FAIL (classes missing).
- [ ] **Step 2: Add the dependencies, `Topics`, `KafkaSettings`, and `KafkaTestSupport`.** Run `make test`; expected PASS.
- [ ] **Step 3: Strimzi in `make cluster`:** `helm upgrade --install strimzi <oci chart> --version <pinned> -n strimzi --create-namespace --set watchNamespaces='{default}' --wait`, idempotent like the rest of the target. One comment line saying what an operator is.
- [ ] **Step 4: The chart.** `kafka.yaml`: a dual-role (controller and broker) `KafkaNodePool` with `replicas: {{ .Values.kafka.replicas }}`, persistent-claim storage `{{ .Values.kafka.storage }}`, `jvmOptions` heap from values; a `Kafka` resource in KRaft mode with one internal plain listener on 9092 whose `networkPolicyPeers` are pods labelled `event-processing`, `stage1-worker`, `stage2-worker`, and the config from Global Constraints. `kafka-topics.yaml`: both `KafkaTopic`s from values. A comment at the top of each file in the chart's existing style.
- [ ] **Step 5: `make up`** adds `kubectl wait kafka/<name> --for=condition=Ready --timeout=5m` after `helm upgrade`. Services do not use Kafka yet.
- [ ] **Step 6: Smoke additions:** (a) `game-events` has 12 partitions and `notifications` 48, read from the `KafkaTopic` resources' status, both `Ready`; (b) a probe pod labelled `event-processing` reaches the bootstrap service on 9092 and a probe pod with any other label is refused (same 5-second wait and same failure hint as the user-pref probe).
- [ ] **Step 7: Run** `make cluster up smoke` locally. Expected: PASS. Then push and confirm CI is green.
- [ ] **Step 8: Commit** "Kafka on k3d through Strimzi; test broker through Testcontainers". If built by a subagent, walk the user through: what the operator does, the node pool, the listener's network policy, why the topics are resources in the chart, and why the operator is installed outside the chart.

---

### Task 4: Event Processing produces to Kafka; the retry loop

**Files:**
- Create: `src/main/java/service/impl/KafkaPlayPublisher.java`, `src/main/java/service/impl/OutboxRelay.java`
- Modify: `src/main/java/service/impl/EventProcessingServiceMain.java`, `deploy/helm/sports-notifier/values.yaml` (`event-processing` gets `kafka: true`)
- Test: `src/test/java/service/impl/KafkaPlayPublisherTest.java`, `src/test/java/service/impl/OutboxRelayTest.java`, `src/test/java/service/impl/KafkaOutageTest.java`

**Interfaces:**
- Consumes: `PlayPublisher`, `Outbox`, `EventProcessingServiceImpl(pool, publisher, nowMillis)` (Task 2); `KafkaSettings`, `KafkaTestSupport` (Task 3).
- Produces:
  - `KafkaPlayPublisher(Producer<String,String> producer, String topic) implements PlayPublisher, AutoCloseable`: key = game id, value = `GameEventParser.toJson(play)` (includes `version`).
  - `OutboxRelay(Outbox outbox, PlayPublisher publisher, LongSupplier nowMillis, long minAgeMillis)`: `void runOnce()` publishes each game from `outbox.gamesWaitingSince(now - minAgeMillis)` and clears it on confirmation. Main schedules `runOnce` every 1000 ms with `minAgeMillis = 5000`.
  - Main wiring: the service publishes through `KafkaPlayPublisher` (whose confirmation runs **clear**) and also calls `RedisStreamPublisher` without clearing, so the course workers still get plays. From this task on the stream publish never clears.

- [ ] **Step 1: Write the tests** (unique topic per test; real Redis DB 1; a completing or never-completing fake publisher where noted):

```java
// KafkaPlayPublisherTest
@Test void gameSyncIsProduced()                  // spec test 12
@Test void burstLandsInVersionOrder()            // spec test 13 on Kafka: 50 threads retrying on PENDING; Kafka's plays for the game have
                                                 // versions 1..N in order (N = accepted answers), seq strictly rising, final score 50
// OutboxRelayTest
@Test void crashAfterAcceptThenRelayKeepsOrder() // spec test 7: accept 10 with a never-completing publisher (the "crash");
                                                 // new service + relay with KafkaPlayPublisher; relay.runOnce(); accept 11;
                                                 // readAll: v1 (seq 10) before v2 (seq 11)
@Test void relaySendsLeftoverAndClears()         // spec test 8
@Test void twoRelaysNeverReorder()               // spec test 9: two relays run concurrently; versions for the game are non-decreasing
@Test void relayIgnoresFreshEntries()            // entry younger than minAgeMillis is not sent
@Test void slowSendThenRelayClearsOnce()         // Review Focus 4: first send held 6 s (publisher wrapper delays completion);
                                                 // relay sends again; Kafka has v1 once or twice, never after v2; outbox cleared
// KafkaOutageTest
@Test void outageMakesGamePendingAndRecoversInOrder() // spec test 11 and section 1.8: accept v1 and wait until its outbox entry
                                                      // is cleared; pause(); accept v2 -> ACCEPTED (unconfirmed, stays in outbox);
                                                      // next play -> PENDING, saved score is v2's; unpause(); relay.runOnce();
                                                      // wait for clear; next play ACCEPTED as v3; Kafka has v1, v2, v3 with
                                                      // non-decreasing versions (v2 may appear twice: the producer's own retry
                                                      // and the relay)
```

- [ ] **Step 2: Run** `make test`. Expected: FAIL (classes missing).
- [ ] **Step 3: Implement `KafkaPlayPublisher`** (bridge `producer.send` callback to the future) **and `OutboxRelay`**. The relay's javadoc explains why two relays cannot reorder a game (the outbox never holds two plays for one game) and what a double send costs (one duplicate with the same `version`, dropped by Stage 1).
- [ ] **Step 4: Wire `EventProcessingServiceMain`:** `KafkaSettings.awaitKafkaOrExit("event-processing")`, the producer from `KafkaSettings.ingestProducer`, a single-thread scheduler for the relay, the stream publish without clearing, and the shutdown hook closing the producer after the scheduler.
- [ ] **Step 5: Run** `make test`, then `make up smoke`. Expected: PASS; history still fills through the course workers.
- [ ] **Step 6: Commit** in two commits: the publisher with clear-on-confirm, then the retry loop with the outage test.

---

### Task 5: Stage 1

**Files:**
- Create: `src/main/java/model/NotificationMessage.java`, `src/main/java/service/impl/Audience.java`, `src/main/java/service/impl/Stage1Progress.java`, `src/main/java/service/impl/Stage1Worker.java`, `src/main/java/service/impl/Stage1WorkerMain.java`
- Modify: `src/main/java/service/impl/PubSubWorkerImpl.java` (delegate to `Audience`), `src/main/java/util/RedisKeys.java` (`STAGE1_LAST_PREFIX = "s1:last:"`), `src/main/java/app/Launcher.java` and `LauncherTest` (`stage1-worker`), `deploy/helm/sports-notifier/values.yaml` (`stage1-worker`: `replicas: 0`, `kafka: true`, `clusterService: false`, port 8085, comment "switched on in Task 6")
- Test: `src/test/java/service/impl/AudienceTest.java` (the fan-out assertions from `PubSubWorkerImplTest` that are about who is in the audience), `src/test/java/service/impl/Stage1WorkerTest.java`

**Interfaces:**
- Consumes: `GameEvent` with `version` (Task 2); `KafkaSettings`, `Topics`, `KafkaTestSupport` (Task 3).
- Produces:
  - `NotificationMessage` fields: `messageId` (`<gameId>:v<version>`), `userId`, `gameId`, `eventType`, `message`, `league`, `homeTeam`, `awayTeam`, `homeScore`, `awayScore`, `status`, `detectedAt` (the play's `timestamp`). Javadoc on every field.
  - `Audience(JedisPool pool)`: `void forEachPage(GameEvent play, Consumer<List<String>> page)`; the temporary set is `fanout:<gameId>:v<version>`.
  - `Stage1Progress(JedisPool pool)`: `long last(String gameId)` (0 if absent); `boolean recordIfHigher(String gameId, long version)` (Lua; sets TTL 691200 s on every write).
  - `Stage1Worker(JedisPool pool, Stage1Progress progress, Consumer<String,String> consumer, Producer<String,String> producer, String notificationsTopic)`: `void pollOnce(Duration timeout)`; `void run()`; `void stop()`. One thread; per play: skip if `version ≤ last`; `GAME_SYNC` records only; else send one message per user (key = user id), `producer.flush()`, then `recordIfHigher`; `commitSync()` after the batch. A record that does not parse is logged and skipped.

- [ ] **Step 1: Move the audience code** from `PubSubWorkerImpl.fanOutNotifications` into `Audience` unchanged in behavior; `PubSubWorkerImpl` calls it. `AudienceTest` carries over the audience assertions (both teams, game, league, intersect with event type, no duplicate for a user following both teams). Run `make test`; expected PASS. Commit "Move the audience into its own class for Stage 1".
- [ ] **Step 2: Write `Stage1WorkerTest`** (unique topics and groups; followers seeded in Redis `sub:*` sets):

```java
@Test void samePlayTwiceSendsOneMessagePerUser()        // spec 2.5 #1
@Test void crashBeforeRecordingResends()                // spec 2.5 #2, first row of the crash table: worker A's Stage1Progress throws
                                                        // on recordIfHigher (the "crash" after flush); A is closed without commit;
                                                        // worker B (same group) re-sends: each user has 2 records with the same
                                                        // messageId (one after Stage 2's dedup)
@Test void crashAfterRecordingSkips()                   // second row: A handles the play fully but is closed before commitSync;
                                                        // B re-reads the play and sends nothing (s1:last holds the version)
@Test void recordIfHigherNeverLowers()                  // spec 2.5 #3: record 7, then 5 -> last == 7, returns false
@Test void gameSyncSendsNothing()                       // spec 2.5 #4
@Test void newGroupReadsEarlierPlays()                  // spec 2.5 #7: produce, then start a worker with a never-used group -> it handles the play
@Test void rereadFromStartSendsNothingNew()             // spec 2.5 #8: handle 3 plays; seek to beginning; pollOnce -> no new messages
@Test void poisonRecordIsSkippedAndCommitted()          // Review Focus 1: "{not json" then a valid play -> valid play handled, offset past both
@Test void twoThousandFollowersGetOneMessageEach()      // Review Focus 3
@Test void messageCarriesVersionedIdAndScores()         // messageId "nhl-1:v3", both scores, detectedAt = play timestamp
```

- [ ] **Step 3: Run** `make test`. Expected: FAIL.
- [ ] **Step 4: Implement `NotificationMessage`, `Stage1Progress`, `Stage1Worker`.** Javadoc on `Stage1Worker` states the crash table from spec 2.2 and why the version is recorded only after `flush()`. A comment on the single thread says per-user order depends on it (spec 2.1).
- [ ] **Step 5: `Stage1WorkerMain`:** await Redis and Kafka, consumer from `KafkaSettings.consumer(bootstrap, Topics.STAGE1_GROUP)` subscribed to `Topics.GAME_EVENTS`, producer from `fanoutProducer`, a `/health` endpoint like the other workers, a shutdown hook that stops the loop and closes consumer then producer.
- [ ] **Step 6: Run** `make test`, then `make up smoke`. Expected: PASS (Stage 1 is deployed with zero replicas).
- [ ] **Step 7: Commit** "Stage 1: one notifications message per user, version-deduped, built and switched off".

---

### Task 6: Stage 2, the deliver script, and the switch-over

**Files:**
- Create: `src/main/java/service/impl/Delivery.java`, `src/main/java/service/impl/Stage2Worker.java`, `src/main/java/service/impl/Stage2WorkerMain.java`
- Modify: `src/main/java/util/RedisKeys.java` (`NOTIF_DEDUP_PREFIX = "notif:"`, `CONN_PREFIX = "conn:"`, `GATEWAY_CHANNEL_PREFIX = "gw:"`), `Launcher` and `LauncherTest` (`stage2-worker`), `values.yaml`, `EventProcessingServiceMain` (switch-over)
- Test: `src/test/java/service/impl/DeliveryTest.java`, `src/test/java/service/impl/Stage2WorkerTest.java`

**Interfaces:**
- Consumes: `NotificationMessage` (Task 5).
- Produces:
  - `Delivery(JedisPool pool)`: `boolean deliver(NotificationMessage m)` runs `DELIVER_SCRIPT` and returns false for a duplicate; `void deliverAll(List<NotificationMessage> batch)` pipelines the script per message in order. The script: `SET notif:<messageId>:<userId> 1 NX EX 172800` (stop if not set); `LPUSH`/`LTRIM 0 999` the history entry (the course `Notification` JSON shape, so the course UI's history still reads it); `ZREMRANGEBYSCORE conn:<userId> -inf <now from TIME>`, then `PUBLISH gw:<gatewayId>` for each member with `{"userId":…,"payload":<history JSON>}`; **bridge until Task 7:** also `PUBLISH notify:<userId> <history JSON>`.
  - `Stage2Worker(JedisPool pool, Consumer<String,String> consumer, Delivery delivery)`: `pollOnce(Duration)`, `run()`, `stop()`; one thread; `commitSync()` after `deliverAll`; unparseable records logged and skipped.

- [ ] **Step 1: Write the tests:**

```java
// DeliveryTest
@Test void sameMessageTwiceIsOneHistoryEntryAndOnePublish() // spec 2.5 #5 (count publishes with a test subscriber on gw:A and notify:<user>)
@Test void publishesToEveryLiveGatewayOfTheUser()           // conn:<user> has A (live) and B (expired score) -> gw:A only, B removed from the set
@Test void userWithNoSocketGetsHistoryOnly()                // Review Focus 2: no conn key -> history entry, zero publishes, returns true
@Test void dedupKeyLastsTwoDays()                           // TTL of notif:<id>:<user> is 172800
// Stage2WorkerTest
@Test void rebalanceMidBatchDeliversEachOnceInOrder()       // spec 2.5 #6: two workers, one closed mid-batch without commit;
                                                            // every user's history = each message once, in send order
@Test void poisonRecordIsSkippedAndCommitted()              // Review Focus 1
```

- [ ] **Step 2: Run** `make test`. Expected: FAIL.
- [ ] **Step 3: Implement `Delivery`** with the script's javadoc stating the lost-versus-duplicate table from spec 2.3 and why one script makes it exactly once in effect; **and `Stage2Worker`, `Stage2WorkerMain`** (like Stage 1's main).
- [ ] **Step 4: Run** `make test`. Expected: PASS. Commit "Stage 2: deliver exactly once in effect with one script".
- [ ] **Step 5: The switch-over commit.** `values.yaml`: `stage1-worker` and `stage2-worker` `replicas: 1`; `pubsub-worker` `replicas: 0`. `EventProcessingServiceMain` stops calling `RedisStreamPublisher`. The commit message says to rebuild the laptop cluster (`make down && make cluster up`), because Stage 1's first start reads `game-events` from the beginning and on a reused cluster would re-alert every play produced since Task 4. CI's cluster is always fresh.
- [ ] **Step 6: Run** `make down && make cluster up smoke`. Expected: PASS (history fills through Kafka, Stage 1, Stage 2; the gateway still receives on `notify:` through the bridge). Kill the `stage1-worker` pod during one `make simulate` and the `stage2-worker` pod during another, and confirm with a following user's history that nothing is missing or doubled (spec Done-when 3).
- [ ] **Step 7: Commit** "Switch delivery to Kafka: Stage 1 and Stage 2 on, Redis Stream off".

---

### Task 7: Gateway routing and graceful shutdown

**Files:**
- Create: `src/main/java/gateway/ConnectionRegistry.java`, `src/main/java/gateway/ClientSocket.java`, `src/main/java/gateway/GatewayChannel.java`, `scripts/ws-listen.py`
- Modify: `src/main/java/gateway/GatewayMain.java` (WebSocket handlers, renew scheduler, shutdown order), `src/main/java/service/impl/Delivery.java` (remove the `notify:` bridge in the same commit), `values.yaml` (gateway `podNameEnv: GATEWAY_ID`), `scripts/smoke-test.sh`
- Delete: `src/main/java/gateway/NotificationFanOut.java`
- Test: `src/test/java/gateway/ConnectionRegistryTest.java`, `src/test/java/gateway/GatewayChannelTest.java`

**Interfaces:**
- Produces:
  - `interface ClientSocket { boolean isOpen(); void send(String text); void close(int code, String reason); }` with a `WsContext` adapter in `GatewayMain`.
  - `ConnectionRegistry(JedisPool pool, String gatewayId, int entryTtlSeconds)`: `add(String userId)`, `remove(String userId)`, `renewAll(Collection<String> userIds)`, `removeAll(Collection<String> userIds)`. Add and renew use one Lua script reading `TIME`: `ZADD conn:<userId> <now+ttl> <gatewayId>` and `EXPIRE conn:<userId> <ttl>`. Production `entryTtlSeconds = 90`; renew every 30 s.
  - `GatewayChannel(Jedis blocking, String gatewayId)`: `register(String userId, ClientSocket s)`, `unregister(String userId, ClientSocket s)` returning true when it was the user's last socket here, `Set<String> users()`, `start()` (subscribes to `gw:<gatewayId>`, forwards `payload` to that user's open sockets), `closeAll(int code, String reason)`, `stop()`.
  - Shutdown order in `GatewayMain`: `registry.removeAll(channel.users())` → `channel.closeAll(4000, "server shutting down")` → stop the renew scheduler → `channel.stop()` → `app.stop()` → `pool.close()`.

- [ ] **Step 1: Write the tests:**

```java
// ConnectionRegistryTest
@Test void addSetsEntryAndKeyExpiry()               // ZSCORE ≈ TIME + 90, TTL conn:<u> in (85, 90]
@Test void twoGatewaysOneUserKeepsBoth()
@Test void removeOnlyRemovesThisGateway()           // spec 3.1 #4 (the registry half)
@Test void stoppedRenewalsExpireEntryAndKey()       // spec 3.1 #3 and #5, ttl = 2 s in the test
@Test void removeAllOnShutdownKeepsOtherGateways()  // spec 3.1 #6 (the registry half)
// GatewayChannelTest (two channels A and B on the same Redis; fake ClientSockets record what they receive)
@Test void userOnAGetsItAndBSeesNothing()           // spec 3.1 #1 (publish through Delivery)
@Test void userOnBothGatewaysGetsItOnBoth()         // spec 3.1 #2
@Test void lastSocketCloseReportsLast()             // spec 3.1 #4: unregister returns false for the first of two tabs, true for the last
@Test void closeAllSendsCode4000()                  // spec 3.1 #6 (the socket half)
@Test void messageForDisconnectedUserIsDropped()    // Review Focus 5: no exception, another user's message still arrives
```

- [ ] **Step 2: Run** `make test`. Expected: FAIL.
- [ ] **Step 3: Implement `ConnectionRegistry`, `ClientSocket`, `GatewayChannel`.** Javadoc on `ConnectionRegistry` explains the sorted set (a user on two gateways), the 30/90-second choice, Redis's clock, and the key expiry.
- [ ] **Step 4: Wire `GatewayMain`:** gateway id from `GATEWAY_ID` (default the hostname); on connect `channel.register` + `registry.add`; on close and error `if (channel.unregister(...)) registry.remove(userId)`; a scheduler calling `registry.renewAll(channel.users())` every 30 s; the shutdown order above. Remove the `notify:` publish from `DELIVER_SCRIPT` and delete `NotificationFanOut`.
- [ ] **Step 5: `scripts/ws-listen.py <url> <cookie-jar> <text> <timeout-seconds>`:** a standard-library WebSocket client (handshake with the `sid` cookie from the curl jar, read text frames, exit 0 when a frame contains `<text>`, 1 on timeout). The smoke test opens it for Bob before the simulator runs and requires `has started` on the socket. Fix spec section 6's wording in this commit: the course smoke test checked history, and this task adds the socket check.
- [ ] **Step 6: Run** `make test`, then `make up smoke`. Then by hand: delete the gateway pod while a browser tab is open, confirm the tab reconnects (close code 4000) and the next simulated notification arrives.
- [ ] **Step 7: Commit** in two commits: registry and channel with tests; the gateway wiring, bridge removal, and smoke socket check.

---

### Task 8: Remove the course path; plays sent before workers start

**Files:**
- Delete: `service/PubSubWorker.java`, `service/impl/PubSubWorkerImpl.java`, `service/impl/PubSubWorkerMain.java`, `service/impl/RedisStreamPublisher.java`, `PubSubWorkerImplTest.java`; `NotificationService.sendNotification` (both overloads), the `/notify/{userId}` endpoint, and the tests for them
- Modify: `util/RedisKeys.java` (remove `GAME_EVENTS_STREAM`, `WORKER_GROUP`, `NOTIFY_CHANNEL_PREFIX`), `Launcher` and `LauncherTest` (remove `pubsub-worker`), `values.yaml` (remove `pubsub-worker`), `templates/network-policies.yaml` if it names it, `scripts/smoke-test.sh`
- Modify: `CLAUDE.md` architecture diagram and service table only where Task 9 does not already cover them (prefer Task 9)

- [ ] **Step 1: Smoke addition:** scale `stage1-worker` and `stage2-worker` to 0, wait for their pods to go, run one simulator wave, scale both to 1, and require Bob's history to show the wave's game start within 60 s. Comment: on CI's fresh cluster this tests `auto.offset.reset=earliest`; on a reused cluster it tests that plays wait in Kafka while workers are down.
- [ ] **Step 2: Delete the course path** listed above. `grep -rn "game-events\"\|notify:\|pubsub" src deploy scripts` shows only Kafka topic uses and history comments.
- [ ] **Step 3: Run** `make test`, then `make down && make cluster up smoke`. Expected: PASS.
- [ ] **Step 4: Commit** "Remove the Redis Stream and the notify:* broadcast".

---

### Task 9: Docs and the quiz

**Files:**
- Modify: `DESIGN.md`, `CLAUDE.md`, `AGENTS.md`, `README.md`
- Create: `docs/study/phase-3-quiz.md`

- [ ] **Step 1: `DESIGN.md`:** build order step 3 lists 3.1–3.4 (the spec's table) and marks 3.1 built; decision 5's "How the window closes" names the outbox, its retry loop, and the one-unsent-play rule, and says why a lock was rejected; a short paragraph on the two heals and `version` (the API's `version`, ids after the script); "Security and correctness still to fix" moves the script-then-publish window, pending-entry recovery, and the `notify:*` broadcast to fixed; the ahead-of-reality bullet points at the heals. "Last updated" line.
- [ ] **Step 2: `CLAUDE.md` and `AGENTS.md`:** architecture diagram and service table (Stage 1, Stage 2, Kafka; no PubSub workers), "Open problems" (remove the closed ones), "Current state" (3.1 merged, next is 3.2), the test command now needs Docker for Testcontainers.
- [ ] **Step 3: `README.md`:** one paragraph on 3.1: the outbox and why a lock was not used, exactly once in effect, gateway routing, and the clock heals found from 60 hours of recordings.
- [ ] **Step 4: `docs/study/phase-3-quiz.md`** with a "3.1 Kafka pipeline" section in the Phase 1 and 2 format (questions, model answers in `<details>`, a summary table with every item **Not yet taken**). Cover at least: why Kafka and not Redis Streams; what a partition and a consumer group buy; the crash window and the outbox; why "one unsent play per game" instead of a lock; the two heals and why `version` is separate from `seq`; at-least-once plus the deliver script equals exactly once in effect; why `s1:last` is set-if-higher; `earliest` and dedup TTLs longer than retention; `conn:` as a sorted set with expiring members; what a Kafka outage does.
- [ ] **Step 5: Run** `make test`; push; confirm CI green.
- [ ] **Step 6: Commit** "Docs and quiz for Phase 3.1", then open the PR. Do not quiz the user; say the file exists.
