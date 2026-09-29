# Sports Notification System

This repository contains the design and implementation for a Sports Notification System.

**All Stages**
https://docs.google.com/document/d/1QMQ-lpEXgMieMsvWzDD8U86PTVbnQvFXkUAPZyIv6EA/edit?usp=sharing

**Stage 0: Design and Architecture**
https://docs.google.com/document/d/1_x7GRlhPCNJw6NyxyadpFtKS82-1R9bDkxV2ykeciJE/edit?usp=sharing

**Stage 1: Functional Requirements**
https://docs.google.com/document/d/1WusmZzUnD8BWwOyyRQ4nMUxsdmHJdVltcObS9QdNxSw/edit?usp=sharing

**Stage 2: System design - high level**
https://docs.google.com/document/d/1qDCVhWJTQTZ1SpS3xmphFtRF8LqzzzY7-FgWV351cGE/edit?usp=sharing

**Stage 3: System design - deeper level**
https://docs.google.com/document/d/1TElISMDIyoWkLYSh13OnFQQwVltiT9lnXPOqqr21XqI/edit?usp=sharing

**Diagrams**
- [Diagram 1 - System Context Diagram](https://github.com/Yeshiva-University-CS/Borenstein_Jacob_800755097/blob/main/com3810/diagrams/Diagram-1%20System%20Context%20Diagram.png)
- [Diagram 2 - Container Diagram](https://github.com/Yeshiva-University-CS/Borenstein_Jacob_800755097/blob/main/com3810/diagrams/Diagram-2%20Container%20Diagram.png)
- [Diagram 3 - User Sets Notification Preferences BPMN](https://github.com/Yeshiva-University-CS/Borenstein_Jacob_800755097/blob/main/com3810/diagrams/Diagram-3-User%20Sets%20Notification%20Preferences%20Diagram.png)
- [Diagram 4 -  Live Event Notification BPMN](https://github.com/Yeshiva-University-CS/Borenstein_Jacob_800755097/blob/main/com3810/diagrams/Diagram-4-Live%20Event%20Notification.png)
- [Diagram 5 - View Game State BPMN](https://github.com/Yeshiva-University-CS/Borenstein_Jacob_800755097/blob/main/com3810/diagrams/Diagram-5-View%20Game%20State.png)
- [Diagram 6 -  Event Processing Service State Chart](https://github.com/Yeshiva-University-CS/Borenstein_Jacob_800755097/blob/main/com3810/diagrams/Diagram-6-Event%20Processing%20Service%20State%20Chart.png)
- [Diagram 7 - Pub/Sub Worker State Chart](https://github.com/Yeshiva-University-CS/Borenstein_Jacob_800755097/blob/main/com3810/diagrams/Diagram-7-Pub%3ASub%20Worker%20State%20Chart.png)
- [Diagram 8 - User Preference Service State Chart](https://github.com/Yeshiva-University-CS/Borenstein_Jacob_800755097/blob/main/com3810/diagrams/Diagram-8-User%20Preference%20Service%20State%20Chart.png)

---

A distributed, real-time sports notification platform. I built this to allow users to create accounts, select notification preferences (teams, leagues, event types), and receive live push notifications via WebSocket as game events occur. Multiple services run as independent JVM processes coordinated through Redis.

---

## Table of Contents

1. [Automated Quickstart](#automated-quickstart)
2. [Demo Walkthrough](#demo-walkthrough)
3. [Architecture Overview](#architecture-overview)
4. [Services](#services)
5. [Distributed Systems Design](#distributed-systems-design)
6. [Design Trade-offs](#design-trade-offs)
7. [Future Upgrades](#future-upgrades)
8. [Prerequisites](#prerequisites)
9. [Build](#build)
10. [Run](#run)
11. [Test](#test)
12. [API Reference](#api-reference)

---

## Automated Quickstart

For convenience, I have included a `quickstart.sh` script in the root directory that automatically downloads Java dependencies, verifies Redis is running, runs all tests, and boots the environment.

Open your terminal in the root folder and run:
```bash
./quickstart.sh
```
The script will confirm your environment is ready, run all tests, and then ask if you want to launch the **Sports Notification System**. Simply press `y` to boot all services.

*(If you prefer to run the steps manually, follow the Build and Run instructions below).*

---

## Demo Walkthrough

Once the system is running (either via `quickstart.sh` or the manual steps below), you can test the real-time notification routing:

1. Open **http://localhost:8080** in two or more browser tabs (or different browsers to simulate different users)
2. Sign up for an account in each tab
3. **Important:** Before running the simulator, you must set your notification preferences!
   * In tab 1: Go to Preferences, follow team **LAL**, and enable **SCORE_UPDATE** notifications.
   * In tab 2: Go to Preferences, follow league **NBA**, and enable **GAME_START** notifications.
4. Run the simulator: `bash scripts/run-simulator.sh`
5. Watch notifications arrive in real time in each tab — tab 1 gets score updates for Lakers games, tab 2 gets game-start notifications for all NBA games

The **Live Games** panel updates as events arrive. The **Notification History** tab shows past notifications persisted in Redis.

---

## Architecture Overview

```
Sports Data         Event Ingestion     Event Processing
  Provider    ──►   (Simulator /   ──►   Service          ──► Redis Stream (game-events)
                    ESPN Poller)         - validates                │
                                         - deduplicates             │
                                         - staleness check          ▼
                                         - updates game state   PubSub Workers (x2)
                                                                   - consume stream
User ──► Browser ──► Gateway ──► User Preference Service           - look up subscribers
              │              └──► Game State Service                - fan-out to
              │              └──► Notification Service                Notification Svc
              │                                                         │
              └── WebSocket ◄──────────────────────────────────────────┘
                  (real-time push via Redis Pub/Sub notify:<userId>)
```

The system matches **Diagram 2 (Container Diagram)** exactly:
- **Event Ingestion** = `EventSimulatorMain` / `EspnPollerMain`
- **Event Processing** = `EventProcessingServiceImpl` (validates, deduplicates, updates game state, publishes to stream)
- **Pub/Sub Broker** = Redis Stream `game-events`
- **Pub/Sub Workers** = two independent `PubSubWorkerMain` JVM processes in one Redis consumer group
- **Game State Management** = `GameStateServiceImpl` (Redis Hash per game)
- **Notification Service** = `NotificationServiceImpl` (Redis List history + Redis Pub/Sub push)
- **Push Notification Service** = `NotificationFanOut` in the Gateway (PSUBSCRIBE → WebSocket)
- **User Preference Service** = `UserPreferenceServiceImpl` (Redis Sets for O(1) subscription lookup)
- **Application / Gateway** = `GatewayMain` (Javalin HTTP + WebSocket, session auth via Redis)

---

## Services

| Service | Port | JAR | Responsibility |
|---|---|---|---|
| Gateway | 8080 | `gateway.jar` | HTTP API, WebSocket push, session management |
| User Preference Service | 8081 | `user-pref.jar` | User accounts, preference storage, subscription cache |
| Game State Service | 8082 | `game-state.jar` | Game state read endpoint |
| Event Processing Service | 8083 | `event-processing.jar` | Validate, dedup, update game state, publish to stream |
| PubSub Worker 1 | — | `pubsub-worker.jar` | Consume stream, fan-out notifications |
| PubSub Worker 2 | — | `pubsub-worker.jar` | Consume stream, fan-out notifications |
| Notification Service | 8087 | `notification.jar` | Store history, publish to Redis Pub/Sub channel |
| Event Simulator | — | `simulator.jar` | Generates synthetic game events for demo |
| ESPN Poller | — | `espn-poller.jar` | Polls live ESPN scoreboard data |

All services are stateless JVM processes. All shared state lives in Redis.

---

## Distributed Systems Design

This section directly addresses the Stage 3 feedback.

### 1. Event Ordering — How global order is achieved with parallel workers

**Problem:** Two PubSub Workers run in parallel. How did I prevent duplicate processing and ensure consistent ordering?

**Solution: Redis Streams with consumer groups.**

Redis Streams (`XADD` / `XREADGROUP`) are an append-only log where every entry gets a monotonically increasing ID (millisecond timestamp + sequence number). This provides **total order** within the stream.

When both workers call `XREADGROUP`, Redis delivers each message to **exactly one consumer** in the group — there is no duplication. Worker 1 gets entries A, C, E; Worker 2 gets B, D, F. Each game's events appear in stream order.

```
EventProcessingServiceImpl.publishEvent():
    jedis.xadd("game-events", XAddParams.maxLen(1000).approximateTrimming(), Map.of("data", json))

PubSubWorkerImpl.startConsuming():
    blockingClient.xreadGroup(GROUP_NAME, consumerName,
        XReadGroupParams.count(10).block(1000), Map.of(STREAM_KEY, UNRECEIVED_ENTRY))
    // After processing: xack → message removed from pending list
```

I did **not shard workers by game** — instead I rely on Redis Streams consumer groups to distribute load. This is simpler and correct: if I sharded by game, I'd need consistent hashing and worker discovery. With consumer groups, Redis handles distribution automatically and any worker can handle any game.

### 2. Duplicate Event Detection — Idempotent processing

**Problem:** The simulator or a network retry might send the same event twice.

**Solution:** Dedup is handled inside the atomic Lua script described in §3. `SET NX EX` on `processed:<eventId>` claims each event exactly once. If two identical events arrive (same `eventId`), the second `SET NX` returns false and the event is silently dropped. The 1-hour TTL prevents the dedup key set from growing unbounded.

The key improvement over a naïve standalone `SET NX`: the claim only happens **after** the staleness check passes, inside the same atomic script. A stale event never consumes a dedup slot.

### 3. Out-of-Order Event Rejection — Fully atomic Lua script

**Problem:** Two separate races had to be eliminated:

1. **Dedup-before-staleness TOCTOU:** If dedup was claimed first and staleness checked second, a higher-timestamped concurrent event could advance `lastSeenTimestamp` between the two commands, causing the lower-timestamped event to be permanently dropped with its dedup key consumed.

2. **State-update race:** Even with dedup+staleness made atomic, the subsequent `HGET`/`HSET` in `GameStateServiceImpl` was still two separate Redis commands. Under a 50-event burst, threads could both see `lastSeenTimestamp=X`, both pass the check, and the lower-timestamped thread's `HSET` could overwrite the higher one's if it ran last — producing a wrong final score (observed: 48 instead of 50).

**Solution:** A single Redis **Lua script** performs all three operations — staleness check, dedup claim, and game state update — atomically. Redis executes Lua scripts as a single unit; no other command can interleave between any step.

```lua
-- KEYS[1]=game hash  KEYS[2]=dedup key
-- ARGV: timestamp, TTL, eventType, homeScore, awayScore, homeTeam, awayTeam, league, gameStatus
local lastSeenTs = tonumber(redis.call('HGET', KEYS[1], 'lastSeenTimestamp')) or 0
local newTs      = tonumber(ARGV[1])
if newTs <= lastSeenTs then return -1 end                   -- stale: dedup key NOT consumed
if redis.call('SET', KEYS[2], '1', 'NX', 'EX', ARGV[2]) == false then return 0 end  -- duplicate
-- update game state atomically with the checks above
if     ARGV[3] == 'GAME_START'    then redis.call('HSET', KEYS[1], 'status','IN_PROGRESS', ...)
elseif ARGV[3] == 'SCORE_UPDATE'  then redis.call('HSET', KEYS[1], 'homeScore', ARGV[4], ...)
elseif ARGV[3] == 'GAME_END'      then redis.call('HSET', KEYS[1], 'status','FINAL', ...)
end
redis.call('HSET', KEYS[1], 'lastSeenTimestamp', ARGV[1])
return 1
```

| Return | Meaning | Dedup key consumed? |
|--------|---------|---------------------|
| `-1` | Stale — timestamp ≤ lastSeenTimestamp | **No** |
| `0` | Duplicate — already processed | N/A |
| `1` | Valid — all three steps completed | Yes |

**Verified:** Under a 50-event concurrent burst for the same game, `homeScore` = `50` every run. Before this fix: `43` (original code) → `48` (partial fix, dedup+staleness only) → `50` (full atomic script).

`GameStateServiceImpl.updateGameState()` retains its own staleness guard as defense-in-depth for any direct calls that bypass the Event Processing Service.

### 4. Queue Backpressure — Bounded stream length

**Problem:** If workers fall behind, the Redis Stream grows unbounded and exhausts memory.

**Solution:** I used `MAXLEN ~1000` (approximate trimming) on every `XADD`. Approximate trimming (`~`) is far cheaper than exact trimming because Redis trims at macro-node boundaries rather than scanning every entry.

```java
private static final long MAX_STREAM_LENGTH = 1000L;
XAddParams params = XAddParams.xAddParams().maxLen(MAX_STREAM_LENGTH).approximateTrimming();
```

If both workers are running, they drain the stream at ~20 messages/second (10 per `XREADGROUP` call, 1s block timeout). The 1000-entry cap represents ~50 seconds of simulator output, giving enough headroom for transient slowdowns without unbounded growth.

### 5. Subscription Lookup — O(1) fan-out via Redis Sets

**Problem:** At notification time, I need to find all users subscribed to a given team, game, or league in O(1), not by scanning all user preferences.

**Solution:** When a user saves preferences, `UserPreferenceServiceImpl` updates Redis Sets atomically via a pipeline:

```
sub:team:<teamName>    → Set of userIds
sub:game:<gameId>      → Set of userIds
sub:league:<league>    → Set of userIds
sub:notif:<eventType>  → Set of userIds
```

At fan-out time, `PubSubWorkerImpl` performs:
```
SSCAN sub:team:Lakers  →  {user1, user3}
SSCAN sub:game:g1      →  {user1, user2}
SSCAN sub:league:NBA   →  {user2, user4}
```
then intersects with `SISMEMBER sub:notif:SCORE_UPDATE` to filter by event type. No full table scan.

To prevent Java Heap OutOfMemory (OOM) errors and "Long Tail" latency when pulling millions of IDs for popular events, I implemented `SSCAN` with a chunk size of 100 instead of `SMEMBERS`. This streams the subscriber IDs into memory safely rather than loading a massive array all at once.

When a user updates preferences, I remove the old Set memberships and add new ones — all in a single pipelined transaction.

### 6. Real-Time Push — Redis Pub/Sub + WebSocket

**Problem:** How do notifications get from the Notification Service to the user's browser without polling?

**Solution:** A two-hop push pipeline:
1. `NotificationServiceImpl.sendNotification()` calls `PUBLISH notify:<userId> <json>` on every notification
2. `NotificationFanOut` in the Gateway runs `PSUBSCRIBE notify:*` on a dedicated Jedis connection (thread-safe, non-pool)
3. On receipt, it looks up WebSocket connections registered for that `userId` and sends the JSON directly

```
Notification Service → Redis PUBLISH notify:user123 → NotificationFanOut → ws.send(json)
```

This means notifications arrive at the browser within milliseconds of the game event, with no polling. Multiple WebSocket connections for the same user (e.g., two browser tabs) all receive the notification.

### 7. Session Management — Redis TTL sessions

I store HTTP sessions as `session:<token>` keys with a 24-hour TTL in Redis. The Gateway validates each request by looking up the session token from a cookie. No sticky sessions are needed — any Gateway instance can validate any session.

---

## Design Trade-offs

Every design decision in this system involved a trade-off. This section explains the key choices I made and what I gave up in exchange.

### Redis as the single coordination layer

**What I gain:** Redis provides streams, pub/sub, sets, hashes, and TTL keys in one place. There is no separate message broker, no separate cache, and no separate session store. The operational footprint is minimal — one Redis process coordinates everything.

**What I give up:** Redis is a single point of failure. If Redis goes down, all services lose their coordination layer simultaneously. A production system would run Redis in cluster mode or use Redis Sentinel for automatic failover. I also cannot exceed the memory of a single Redis node without sharding, which becomes relevant at very large scale (millions of users or games).

**Why it is acceptable here:** For a demo system running on a single machine, the simplicity is worth it. I wrote the code in a way that makes adding Redis Cluster straightforward — all Redis access goes through `JedisPool`, so switching to `JedisCluster` requires only changes in `JedisPoolHolder`.

---

### Redis Streams consumer groups instead of game-sharded workers

**What I gain:** Workers are stateless. Any worker can handle any game's events. Adding a third worker is just running another JVM process with a different `PUBSUB_CONSUMER_NAME` — no configuration changes to other services, no consistent hashing, no worker registry.

**What I give up:** I lose strict per-game ordering guarantees across workers. Worker 1 might process event A for game G, and Worker 2 might process event B for game G in parallel. If they arrive at Redis at the same time, there is no guarantee B is processed after A at the worker level (though the stream itself maintains order).

**How the race is made safe:** The atomic Lua script in `EventProcessingServiceImpl` (see §3 above) ensures that for the same game, concurrent workers cannot both update game state with out-of-order timestamps. The loser of the race sees a `lastSeenTimestamp` already advanced past its own timestamp, returns `-1` (stale), and stops without corrupting state or consuming a dedup key.

**The alternative:** Shard workers by game ID (hash the game ID, assign to a worker). This guarantees all events for a given game are processed sequentially by one worker. The cost: worker assignment requires consistent hashing and worker discovery (e.g., ZooKeeper, etcd), re-sharding when workers join or leave, and the system is no longer trivially scalable. The Lua-atomic approach achieves correctness without this operational complexity.

---

### Eventual consistency between game state and notifications

**What I gain:** Game state updates (`GameStateServiceImpl`) and notification fan-out (`PubSubWorkerImpl`) happen independently. The Event Processing Service updates game state synchronously and then publishes to the stream. Workers consume the stream and send notifications. This decouples the two concerns.

**What I give up:** There is a small window where the game state in Redis reflects the new score but no notification has been sent yet (the worker hasn't consumed the message). Conversely, a notification could theoretically arrive at a user before their next `GET /api/games` response reflects the score — but in practice the lag is milliseconds.

**Why it is acceptable:** Sports notification systems are inherently "best effort." A 50ms notification delay is imperceptible. If I required strict consistency (notification and game state update in the same transaction), I would need distributed transactions (2PC or saga), which adds enormous complexity for no user-visible benefit.

---

### In-memory WebSocket registry in the Gateway

**What I gain:** Extremely low latency for the final hop from notification to browser. The `NotificationFanOut` holds a `ConcurrentHashMap<userId, Set<WsContext>>` and forwards notifications directly to the socket without any Redis lookup.

**What I give up:** WebSocket connections are local to one Gateway instance. If I run two Gateways, a user connected to Gateway A will not receive notifications delivered via Gateway B's registry. The current setup assumes a single Gateway.

**The production fix:** Use Redis Pub/Sub as the broadcast layer between Gateways (which I already do at the notification level — each Gateway subscribes to `notify:*` and forwards to its own connected users). With multiple Gateways each running `PSUBSCRIBE notify:*`, a notification published to Redis reaches every Gateway, and each one forwards it to locally connected users for that `userId`. The architecture already supports this — I would just run more Gateways.

---

### BCrypt password hashing

**What I gain:** I store passwords as bcrypt hashes with a cost factor of 10. Even if the Redis data is exfiltrated, passwords cannot be reversed without a brute-force attack that is computationally prohibitive.

**What I give up:** BCrypt is intentionally slow (~60ms per check at cost factor 10). Under heavy login load this adds latency. At scale, I would use a dedicated auth service with connection pooling to absorb this.

---

### No persistent durable store (Redis only)

**What I gain:** Everything is fast. All reads and writes hit memory.

**What I give up:** If Redis restarts without persistence configured, all game state, user preferences, notification history, and sessions are lost. For a demo this is acceptable, but for a production system I would enable Redis AOF persistence (append-only log) or use a separate PostgreSQL/Cassandra store for durable data like user accounts and notification history.

---

## Future Upgrades

These are concrete improvements I identified to take this system from a demo to production-grade.

### 1. Redis persistence and high availability

I will enable Redis AOF persistence (`appendonly yes`) so data survives restarts. I would also add Redis Sentinel or deploy Redis Cluster for automatic failover. Without this, a Redis restart wipes all user accounts and preferences.

### 2. Durable user and preference storage (PostgreSQL)

I plan to move user accounts and preferences to a relational database. Redis would remain the cache/index (the `sub:team:*` sets, `prefs:<userId>` keys), but the source of truth would be Postgres. On startup, the User Preference Service would warm Redis from Postgres. This ensures user data survives a Redis wipe.

### 3. Multiple Gateway instances and load balancing

I currently run one Gateway. To scale horizontally, I will run multiple Gateways behind an Nginx or HAProxy load balancer with sticky sessions. My notification fan-out already supports this — each Gateway runs `PSUBSCRIBE notify:*` and delivers to its own connected clients.

### 4. Worker autoscaling

I currently have two PubSub Workers hardcoded in `run-all.sh`. In a production system, I will monitor Redis Stream lag (`XPENDING`) and spin up additional workers when the pending entry count exceeds a threshold. This can be done with Kubernetes horizontal pod autoscaling.

### 5. Dead-letter queue for failed events

If a PubSub Worker crashes mid-processing, the message stays in the consumer group's pending list (`XPENDING`). I will implement a background job that uses `XCLAIM` to reclaim pending messages older than a threshold (e.g., 30 seconds) and retry them, moving them to a dead-letter stream after a max retry count.

### 6. Email / SMS / push notification delivery

I will integrate with services like SendGrid (email), Twilio (SMS), or Firebase Cloud Messaging (mobile push). My `NotificationService` interface is designed to support this — I just need to add delivery channel fields to `UserPreferences` and a routing step inside `NotificationServiceImpl.sendNotification()`.

### 7. Structured logging and distributed tracing

I will implement structured JSON logs (using SLF4J + Logback) shipped to a centralized log aggregator. I will also add an `X-Request-Id` header threaded through all service calls to enable distributed tracing across the entire system.

### 8. Authentication hardening

I will improve the current session token system by using JWT tokens for stateless auth, adding token refresh, enforcing HTTPS, and adding rate limiting on `/api/login` to prevent brute-force attacks.

### 9. Schema versioning for Redis data

I will version the JSON schema (e.g., `"v": 2` in the JSON) to handle both old and new formats during deserialization. This ensures that game state and notification payloads remain readable even if class fields change.

### 10. Modernizing the Frontend (UI/UX)

While the current UI is a functional prototype, I plan to redesign the frontend to make it much more user-friendly and intuitive. The goal is to provide a cleaner dashboard, smoother navigation, and clearer real-time feedback for users managing their sports notifications.

### 11. Scaling to Millions: The "Massive Fan-out" Strategy

To handle millions of users, I will implement the following strategies:

* **Distributed Task Queues (RabbitMQ/Kafka):** Instead of sending notifications synchronously, I will generate "Delivery Jobs" and push them to a massive queue. Hundreds of "Delivery Workers" will then consume these jobs and handle delivery in parallel.
* **Database Layering (PostgreSQL + Redis):** I will move durable data to a sharded PostgreSQL cluster, using Redis strictly as a write-through cache.
* **Redis Cluster Sharding:** I will move from a single Redis node to a **Redis Cluster** to shard data across multiple nodes and handle millions of operations per second.
* **Geographic Edge Gateways:** I will deploy Gateway instances at the "edge" in different regions to reduce WebSocket latency for users worldwide.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 17+ | `java -version` |
| Maven | 3.8+ | `mvn -version` |
| Redis | 7.x | `redis-server --version` |

Install Redis on Mac: `brew install redis`

---

## Build

```bash
mvn -f code/pom.xml clean package
```

This compiles all source, runs all 31 unit tests, and produces 8 fat JARs in `code/target/`:

```
code/target/gateway.jar
code/target/user-pref.jar
code/target/game-state.jar
code/target/event-processing.jar
code/target/pubsub-worker.jar
code/target/notification.jar
code/target/simulator.jar
code/target/espn-poller.jar
```

---

## Run

### Step 1 — Start Redis

```bash
redis-server --daemonize yes
```

Verify: `redis-cli ping` → `PONG`

### Step 2 — Start all services

```bash
bash scripts/run-all.sh
```

This starts 7 services as background JVM processes:
- **user-pref** on :8081
- **game-state** on :8082
- **event-processing** on :8083
- **worker-1** (PubSub consumer)
- **worker-2** (PubSub consumer)
- **notification** on :8087
- **gateway** on :8080

Each service logs to `logs/<name>.log` and writes its PID to `pids/<name>.pid`.

Open **http://localhost:8080** in a browser.

### Step 3 — Start the event simulator

```bash
bash scripts/run-simulator.sh
```

The simulator runs 3 waves of 6 concurrent games (18 total), generating `GAME_START`, `SCORE_UPDATE`, and `GAME_END` events. Each game runs in its own thread with realistic score progression.

Alternatively, use the ESPN poller for live game data:

```bash
bash scripts/run-espn-poller.sh
```

### Step 4 — Stop everything

```bash
bash scripts/stop-all.sh
```

### Environment Variables

Each service is configurable via environment variables (all have defaults):

| Variable | Default | Service |
|---|---|---|
| `REDIS_HOST` | `127.0.0.1` | all |
| `REDIS_PORT` | `6379` | all |
| `GATEWAY_PORT` | `8080` | gateway |
| `USER_PREF_PORT` | `8081` | user-pref |
| `GAME_STATE_PORT` | `8082` | game-state |
| `EVENT_PROCESSING_PORT` | `8083` | event-processing |
| `NOTIFICATION_PORT` | `8087` | notification |
| `PUBSUB_CONSUMER_NAME` | `worker-1` | pubsub-worker |
| `EPS_URL` | `http://127.0.0.1:8083` | simulator |
| `SIM_INTERVAL_MS` | `2000` | simulator |

---

## Test

Tests require a running Redis instance on `localhost:6379`. All tests run against DB 1 (`SELECT 1`) and flush it before each test case to avoid interference with development data in DB 0.

```bash
mvn -f code/pom.xml test
```

Expected output: **31 tests, 0 failures, 0 errors.**

### Test Coverage

| Test Class | Tests | What it verifies |
|---|---|---|
| `EventProcessingServiceImplTest` | 6 | Atomic Lua (dedup + staleness + state update), stream publish, null/blank validation |
| `GameStateServiceImplTest` | 6 | GAME_START init, SCORE_UPDATE, GAME_END, stale reject, duplicate timestamp |
| `PubSubWorkerImplTest` | 6 | Team/game/league fan-out, event-type filter, dedup across sets, empty subscriber no-op |
| `NotificationServiceImplTest` | 5 | Single send, bulk send, newest-first order, limit, empty history |
| `UserPreferenceServiceImplTest` | 5 | Round-trip, cache population, cache removal on update, null for missing, updateSubscriptionCache |
| `GameEventParserTest` | 3 | JSON serialization round-trip |

---

## API Reference

All endpoints are served by the Gateway on port 8080.

### Auth

| Method | Path | Body | Description |
|---|---|---|---|
| POST | `/api/signup` | `{"username":"...", "password":"..."}` | Create account, set session cookie |
| POST | `/api/login` | `{"username":"...", "password":"..."}` | Login, set session cookie |
| POST | `/api/logout` | — | Destroy session |
| GET | `/api/me` | — | Current user info |

### Preferences

| Method | Path | Body | Description |
|---|---|---|---|
| GET | `/api/preferences` | — | Get user preferences |
| PUT | `/api/preferences` | `UserPreferences JSON` | Save preferences (updates subscription cache atomically) |

### Games

| Method | Path | Description |
|---|---|---|
| GET | `/api/games` | All active game states from Redis |
| GET | `/api/games/{gameId}` | Single game state |
| GET | `/api/upcoming` | Scheduled games from ESPN (proxied) |

### Notifications

| Method | Path | Description |
|---|---|---|
| GET | `/api/history?limit=50` | Notification history for current user |

### WebSocket

| Path | Description |
|---|---|
| `ws://localhost:8080/ws` | Real-time notification stream. Requires session cookie. |

WebSocket messages are JSON: `{"event":"connected","userId":"..."}` on connect, then notification payloads as they arrive.
