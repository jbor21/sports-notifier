# Phase 2: Correctness and security — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix fan-out to cover both teams, order events by game progress (`seq`), make the ESPN poller stateless and safe to run twice, expire old games, and close the account and network security holes, all on the existing Redis-only pipeline.

**Architecture:** The Lua script in `EventProcessingServiceImpl` stays the only writer of game state and now orders by `seq`. The poller becomes a thin loop over three pure pieces (`EspnScoreFeed`, `Seq`, `PollDecision`) and compares ESPN against the game record the script wrote. Security changes live in the user service (`AccountService`), the gateway (`ClientAddress`, `LoginThrottle`, `SessionAuth`), and the Helm chart (NetworkPolicies).

**Tech Stack:** Java 17, Maven, Javalin 6.1.6, Jedis 4.4.7, Gson 2.10.1, jbcrypt 0.4, JUnit 5.10.2, Redis 7.4, Helm, k3d, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-02-phase-2-correctness-security-design.md`

## Global Constraints

- Work in the worktree `.worktrees/phase-2-correctness` on branch `phase-2-correctness`. CI green after every task.
- No new Maven dependencies. No version changes.
- Every Redis key prefix is a constant in `util/RedisKeys.java`. No inline key strings.
- Every Jedis handle in try-with-resources. `JedisPoolHolder.create()` for pools, `dedicated()` only for blocking connections.
- All config from env vars with defaults.
- Tests use Redis DB 1 on `localhost:6379` and `flushDB()` in `@BeforeEach`, like the existing tests. Run with `make test` (or `mvn -B -q test -Dtest=<Class>` while a test Redis is up).
- Comment density matches the code: explain *why* at the tricky spots (Lua, fan-out, `seq`, client address), quiet elsewhere.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Core distributed code (Tasks 1–10) is built inline in small steps the user reads. Tasks 11–13 may go to subagents, then are walked through with the user.
- `seq` layout (spec 2.1), most significant first: phase(1) period(2) half(1) clock(5) outs(1) stage(1) scoreTotal(3).

## Review Focus

1. **An ESPN game object missing fields** (no competitors, null score, no `situation` before an MLB game) → that game is skipped and the rest of the league still polls. Test in Task 4.
2. **An empty or off-season scoreboard** (`events` missing or `[]`) → empty list, the league polls at the idle rate, no exception. Test in Task 4.
3. **A malformed POST to `/events`** (bad JSON, `seq` as a string) → `400`, not `500`. Test in Task 2.
4. **A login body without `username`** → `400` or `401` from the user service, never a `500` in the gateway, and the per-address count still increases. Test in Task 9.
5. **The same username in different case at once** (`Bob` and `bob`) → exactly one account. Test in Task 8.

Accepted, no test: the old UI shows a live pill only for `IN_PROGRESS`, so a game at `HALFTIME` or `DELAYED` shows the raw status text until the Phase 3b UI.

---

### Task 1: Fan-out matches both teams, with set operations

**Files:**
- Modify: `src/main/java/service/impl/PubSubWorkerImpl.java` (`fanOutNotifications`, remove `scanAndAddSubscribers`)
- Modify: `src/main/java/util/RedisKeys.java`
- Modify: `src/test/java/service/impl/PubSubWorkerImplTest.java`
- Modify: `scripts/smoke-test.sh`

**Interfaces:**
- Produces: `RedisKeys.FANOUT_PREFIX = "fanout:"`. `fanOutNotifications(GameEvent)` keeps its signature.

- [ ] **Step 1: Write the failing tests** in `PubSubWorkerImplTest`. Helper: `follow(String userId, String setKey, String... types)` adds the user to `setKey` and to `sub:notif:<type>` for each type. Helper: `historyOf(userId)` → `notificationService.getNotificationHistory(userId, 50)`.

```java
@Test void awayFollowerGetsHomeTeamScore() {
    follow("bos-fan", "sub:team:BOS", "SCORE_UPDATE");
    worker.fanOutNotifications(new GameEvent("game-LAL-BOS", "e1", "SCORE_UPDATE", "LAL",
        1000L, "LAL scores! LAL 2 - 0 BOS", "LAL", "BOS", 2, 0, "IN_PROGRESS", "NBA"));
    assertEquals(1, historyOf("bos-fan").size());
}
@Test void awayFollowerGetsGameStartTaggedWithHomeTeam() { /* GAME_START, team "LAL", BOS follower with GAME_START → 1 */ }
@Test void nflFollowerGetsOpponentTouchdown() { /* CHI follower; SCORE_UPDATE team "LV", home "CHI", away "LV", league "NFL" → 1 */ }
@Test void followerOfBothTeamsGetsOneNotification() { /* user in sub:team:LAL and sub:team:BOS → exactly 1 */ }
@Test void followerWithoutTypeGetsNothing() { /* BOS follower with only GAME_START, SCORE_UPDATE event → 0 */ }
@Test void unrelatedTeamFollowerGetsNothing() { /* MIA follower → 0 */ }
@Test void noFanoutKeyLeftBehind() { /* after any fan-out: jedis.keys("fanout:*").isEmpty() */ }
```

- [ ] **Step 2: Run them and confirm the first three fail.** `mvn -B -q test -Dtest=PubSubWorkerImplTest`. Expected: `awayFollowerGetsHomeTeamScore`, `awayFollowerGetsGameStartTaggedWithHomeTeam`, `nflFollowerGetsOpponentTouchdown` FAIL (0 notifications). Show the user the failures before fixing.

- [ ] **Step 3: Rewrite `fanOutNotifications`.** Order, one pipeline for 1–3:
  1. `SUNIONSTORE fanout:<eventId>` over the non-null of `sub:team:<homeTeam>`, `sub:team:<awayTeam>`, `sub:team:<team>`, `sub:game:<gameId>`, `sub:league:<league>`. If no source keys, return.
  2. `SINTERSTORE fanout:<eventId> fanout:<eventId> sub:notif:<eventType>`.
  3. `EXPIRE fanout:<eventId> 60`.
  4. `SSCAN` the key with `count(1000)`. For each page, `notificationService.sendNotification(page, notification)`.
  5. `DEL fanout:<eventId>` in a `finally`.

  Comment why at the top: one Redis call per event instead of one per subscriber; the union deduplicates a user who follows both teams; the 60 s TTL cleans up if the worker dies mid-fan-out.

- [ ] **Step 4: Run all worker tests.** Same command. Expected: all PASS, including the pre-existing ones.

- [ ] **Step 5: Tighten the smoke test** (spec section 8, steps 2–6). Replace the single user with:
  - Bob: signup, then `PUT /api/preferences` with `{"followedTeams":["BOS"],"followedGames":[],"followedLeagues":[],"notificationTypes":["GAME_START","SCORE_UPDATE"]}`.
  - Alice: signup, no preferences.
  - Separate cookie jars. Passwords `smoke-password-1` (16 characters, so they pass Task 8's rule).
  - After the simulator: wait up to 60 s for Bob's history to contain a `game-LAL-BOS` entry with `has started` **and** one with `scores!` (`jq -e '(any(.[]; .gameId=="game-LAL-BOS" and (.message|contains("has started")))) and (any(.[]; .gameId=="game-LAL-BOS" and (.message|contains("scores!"))))'`).
  - Then Alice: `GET /api/history` → `[]`. `GET /api/history?userId=<bobId>` and `GET /api/preferences?userId=<bobId>` with Alice's jar → Alice's own data (empty history; preferences `404` or empty). Bob's id comes from his signup response.
  - Replace the old step-3 comment about flaky score matching with one line on why BOS: the away team proves both fixes.

- [ ] **Step 6: Run end to end.** `make cluster up smoke`. Expected: `SMOKE PASS`.

- [ ] **Step 7: Commit.** `git add -A && git commit -m "Fan-out matches both teams with Redis set operations"`

---

### Task 2: Order events by `seq`

**Files:**
- Modify: `src/main/java/model/GameEvent.java` (add `seq`)
- Create: `src/main/java/service/ProcessResult.java`
- Modify: `src/main/java/service/EventProcessingService.java`, `src/main/java/service/impl/EventProcessingServiceImpl.java`, `src/main/java/service/impl/EventProcessingServiceMain.java`
- Modify: `src/main/java/service/GameStateService.java`, `src/main/java/service/impl/GameStateServiceImpl.java` (remove `updateGameState`)
- Modify: `src/main/java/simulator/EventSimulatorMain.java`
- Modify: `src/test/java/service/impl/EventProcessingServiceImplTest.java`, `src/test/java/service/impl/GameStateServiceImplTest.java`
- Create: `src/test/java/service/impl/EventProcessingBurstTest.java`
- Modify: `scripts/smoke-test.sh` (comment in step 4 only)

**Interfaces:**
- Produces:
  - `GameEvent`: field `private long seq` with Javadoc ("Position in the game; bigger is later. See feed.Seq."), getter `getSeq()`, new 13-arg constructor `(gameId, eventId, eventType, team, timestamp, description, homeTeam, awayTeam, homeScore, awayScore, gameStatus, league, long seq)`. The 12-arg constructor stays and delegates with `seq = 0`.
  - `public enum ProcessResult { ACCEPTED, DUPLICATE, STALE, INVALID }` in package `service`.
  - `EventProcessingService.processEvent(GameEvent) → ProcessResult` and new `ProcessResult processJson(String json)`.
  - `EventProcessingServiceImpl(JedisPool pool)`. The `GameStateService` parameter is gone.
  - Event type constant `"GAME_SYNC"`: state is saved, nothing is published.
  - Game hash fields after any accepted event: `homeTeam, awayTeam, league, homeScore, awayScore, status, lastSeq, lastTimestamp`.

- [ ] **Step 1: Write the failing tests** in `EventProcessingServiceImplTest`. Construct `service = new EventProcessingServiceImpl(pool)`. Helper `ev(String id, long seq, int home, int away, String type)` builds an LAL–BOS NBA event with status `IN_PROGRESS`.

```java
@Test void acceptedEventIsPublishedAndSaved() {
    assertEquals(ProcessResult.ACCEPTED, service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE")));
    assertEquals(1L, jedis.xlen("game-events"));
    assertEquals("10", jedis.hget("game:g1", "lastSeq"));
    assertEquals("LAL", jedis.hget("game:g1", "homeTeam"));
}
@Test void sameIdTwiceIsDuplicate()      { /* e1 seq 10, then e1 seq 10 → DUPLICATE, xlen 1 */ }
@Test void retryOfAcceptedEventIsDuplicateNotStale() { /* e1 seq 10, then e1 again → DUPLICATE (id checked before seq) */ }
@Test void lowerSeqIsStaleAndIdNotClaimed() {
    service.processEvent(ev("e2", 20, 4, 0, "SCORE_UPDATE"));
    assertEquals(ProcessResult.STALE, service.processEvent(ev("e1", 10, 2, 0, "SCORE_UPDATE")));
    assertFalse(jedis.exists("processed:e1"));
}
@Test void equalSeqNewIdIsStale()        { /* e1 seq 10, e3 seq 10 → STALE */ }
@Test void gameStartKeepsCarriedScore()  { /* GAME_START with 3-1 → hash homeScore "3" (no reset to 0) */ }
@Test void gameSyncSavesStateButPublishesNothing() { /* GAME_SYNC accepted, xlen 0, status saved */ }
@Test void missingSeqIsInvalid()         { /* seq 0 → INVALID, nothing written */ }
@Test void missingStatusIsInvalid()      { /* gameStatus null → INVALID */ }
@Test void malformedJsonIsInvalid() {    // Review Focus 3
    assertEquals(ProcessResult.INVALID, service.processJson("{not json"));
    assertEquals(ProcessResult.INVALID, service.processJson("{\"gameId\":\"g1\",\"seq\":\"abc\"}"));
}
```

Delete the old timestamp-based tests (`processEvent_ignoresStaleEvent`, `processEvent_ignoresDuplicateTimestamp`) and the `updateGameState_*` tests in `GameStateServiceImplTest`. Keep `getGameState_returnsNullIfGameNotSeen`, and add one that seeds the hash through `processEvent` and reads it back with `getGameState`.

`EventProcessingBurstTest`:

```java
@Test void fiftyConcurrentEventsEndAtFifty() throws Exception {
    // The course race: 43 → 48 → 50. 50 threads, seq 1..50, homeScore = seq, released together.
    // Repeat the whole burst 5 times on a fresh game id; every run must end at 50.
    ...
    assertEquals("50", jedis.hget("game:" + gameId, "homeScore"));
    assertEquals("50", jedis.hget("game:" + gameId, "lastSeq"));
}
```

Use a `CountDownLatch(1)` start gate and a 50-thread pool so the events really race.

- [ ] **Step 2: Run and confirm they fail.** `mvn -B -q test -Dtest='EventProcessing*'`. Expected: compile errors (no `ProcessResult`, no 13-arg constructor). That is the failing state.

- [ ] **Step 3: Add `seq` to `GameEvent` and create `ProcessResult`.**

- [ ] **Step 4: Rewrite the Lua script and `processEvent`.** Contract (comment it above the script, like the current one):

```
KEYS[1] = game:<gameId>        KEYS[2] = processed:<eventId>
ARGV[1] = seq   ARGV[2] = dedup TTL seconds (3600)
ARGV[3] = homeTeam  ARGV[4] = awayTeam  ARGV[5] = league
ARGV[6] = homeScore ARGV[7] = awayScore ARGV[8] = status  ARGV[9] = timestamp
Returns: 0 duplicate | -1 stale | 1 accepted
1. if EXISTS KEYS[2] → return 0          -- read only; a retry is "duplicate", never "stale" (decision 5 needs this)
2. if seq <= tonumber(HGET lastSeq or 0) → return -1   -- id stays unclaimed
3. SET KEYS[2] 1 EX ARGV[2]; HSET all eight fields; return 1
```

The script no longer branches on event type. `processEvent`: validate (non-blank `gameId`, `eventId`, `eventType`, `gameStatus`; `seq > 0`) → `INVALID`; run the script; on `1` publish unless the type is `GAME_SYNC`; map results to `ProcessResult`. `processJson`: parse with `GameEventParser.parse`, catch `JsonParseException` / `NumberFormatException` / `IllegalStateException` → `INVALID`, null → `INVALID`, else `processEvent`.

- [ ] **Step 5: Remove the dead code.** Delete `updateGameState` from `GameStateService` and `GameStateServiceImpl`. Fix `EventProcessingServiceMain` to `new EventProcessingServiceImpl(pool)`.

- [ ] **Step 6: Map results to HTTP** in `EventProcessingServiceMain`'s `POST /events`: `INVALID` → `400 {"result":"invalid"}`, otherwise `200 {"result":"accepted"|"duplicate"|"stale"}`. A Redis exception propagates, so Javalin answers `500`.

- [ ] **Step 7: Simulator `seq` and ids.** In `EventSimulatorMain`: `long runId = System.currentTimeMillis()` once in `main`. In `runGame`, a counter `n` from 1 per game. Each event: id `"sim:" + runId + ":" + gameId + ":" + n`, `seq = runId * 1000 + n`, then `n++`. Remove `uuid()`. Update the comment in smoke-test step 4: the later run now wins, so deleting running jobs only keeps the run clean.

- [ ] **Step 8: Run all tests.** `make test`. Expected: all PASS. Run the burst test alone 3 times as well; all three end at 50.

- [ ] **Step 9: Run end to end.** `make up smoke`. Expected: `SMOKE PASS`. Then `make smoke` a second time on the same cluster; also `SMOKE PASS` (proves a second simulator run is not stale).

- [ ] **Step 10: Commit.** `"Order events by seq; id checked before seq; burst test; remove dead updateGameState"`

---

### Task 3: `GameSnapshot` and `Seq`

**Files:**
- Create: `src/main/java/model/GameSnapshot.java`
- Create: `src/main/java/feed/Seq.java`
- Create: `src/test/java/feed/SeqTest.java`

**Interfaces:**
- Produces:

```java
public record GameSnapshot(String gameId, String league, String homeTeam, String awayTeam,
                           int homeScore, int awayScore, Phase phase, int period, Half half,
                           int clockTenths, int outs, Stage stage, String status, long startTimeMillis) {
    public enum Phase { PRE, LIVE, FINAL }                   // digit = ordinal()
    public enum Half  { TOP, MIDDLE, BOTTOM, END }           // MLB only; NONE is TOP (0) for other sports
    public enum Stage { IN_PLAY, DELAYED, END_PERIOD, HALFTIME }
}
public final class Seq { public static long of(GameSnapshot s); }
```

`status` is one of `SCHEDULED, IN_PROGRESS, END_PERIOD, HALFTIME, DELAYED, POSTPONED, SUSPENDED, FINAL, CANCELED`. `clockTenths` is tenths of a second *remaining* (0 for MLB). `startTimeMillis` is the scheduled start (0 if unknown).

- [ ] **Step 1: Write the failing tests** (`SeqTest`).

```java
@Test void specExampleNflFourthQuarter() {   // spec 2.1: 1 04 0 34800 0 0 045
    assertEquals(10403480000045L, Seq.of(nfl(LIVE, 4, 1200, IN_PLAY, 24, 21)));
}
@Test void lessTimeLeftIsBigger()            { /* Q4 1:00 > Q4 2:00 */ }
@Test void nextPeriodBeatsAnyClockInPrevious() { /* Q3 15:00 > Q2 0:00 halftime */ }
@Test void halftimeBeatsEndOfQ2LastPlay()    { /* period 2 clock 0 HALFTIME > period 2 clock 0 IN_PLAY with same score */ }
@Test void extraPointBreaksTieAtStoppedClock() { /* same position, 27 total > 26 total... TD 21-20 then XP 21-21? use 6→7 */ }
@Test void delayedBeatsInPlayAtSamePosition()  { /* DELAYED > IN_PLAY same clock */ }
@Test void finalBeatsEverythingLive()        { /* FINAL period 4 > LIVE period 9 (overtime) */ }
@Test void mlbHalfAndOuts()                  { /* Bot 4th 0 out > Top 4th 2 out; Mid 4th between; End 4th > Bot 4th 3 out; clock block is 0 */ }
@Test void suspendedThenResumedKeepsRising() { /* LIVE inning 5 DELAYED (suspended) < LIVE inning 5 Top 1 out IN_PLAY later */ }
@Test void clockOutOfRangeIsClamped()        { /* clockTenths -5 → as 0; 50000 → as 36000; never throws */ }
@Test void scoreTotalCappedAt999()           { /* 600 + 600 → last three digits 999 */ }
@Test void fitsInLuaNumber()                 { /* Seq.of(max everything) < (1L << 53) */ }
```

- [ ] **Step 2: Run.** `mvn -B -q test -Dtest=SeqTest`. Expected: compile failure.

- [ ] **Step 3: Implement `GameSnapshot` and `Seq.of`.** `phase·10¹³ + period·10¹¹ + half·10¹⁰ + clock·10⁵ + outs·10⁴ + stage·10³ + min(total, 999)`, where `clock = "MLB".equals(league) ? 0 : 36000 − clamp(clockTenths, 0, 36000)`, period clamped to 0–99, outs to 0–3. Javadoc on `Seq` explains the blocks in one table-like comment, since this is what the user will read.

- [ ] **Step 4: Run.** Expected: PASS.

- [ ] **Step 5: Commit.** `"Add GameSnapshot and Seq: game progress as one comparable number"`

---

### Task 4: `EspnClient`, `EspnScoreFeed`, and the gateway proxy

**Files:**
- Create: `src/main/java/feed/ScoreFeed.java`, `src/main/java/feed/FeedBlockedException.java`, `src/main/java/feed/EspnClient.java`, `src/main/java/feed/EspnScoreFeed.java`
- Modify: `src/main/java/util/SimpleHttp.java` (add `get(String url, Map<String,String> headers)`)
- Modify: `src/main/java/gateway/EspnProxyHandler.java`, `src/main/java/gateway/GatewayMain.java`
- Create: `src/test/resources/espn/*.json` (fixtures), `src/test/java/feed/EspnScoreFeedTest.java`
- Modify: `deploy/helm/sports-notifier/values.yaml`, `templates/java-services.yaml` or gateway env, `templates/espn-poller.yaml`
- Modify: the spec, section 3.1 row for `EspnProxyHandler`: "reads through the shared `EspnClient`" (the proxy keeps its own parsing for logos and names; only the HTTP request is shared)

**Interfaces:**
- Produces:

```java
public interface ScoreFeed { List<GameSnapshot> fetch(String league) throws FeedBlockedException, IOException; }
public class FeedBlockedException extends Exception { public FeedBlockedException(int status, String url); public int status(); }
public final class EspnClient {
    public static final String DEFAULT_HOST = "site.web.api.espn.com";
    public static final String DEFAULT_USER_AGENT = "sports-notifier/1.0 (+https://github.com/boboborenstein-collab/sports-notifier)";
    public EspnClient(String host, String userAgent);
    public static EspnClient fromEnv();                                   // ESPN_HOST, ESPN_USER_AGENT
    public String scoreboard(String league) throws FeedBlockedException, IOException;  // league ∈ NBA, NFL, MLB, NHL
}
public final class EspnScoreFeed implements ScoreFeed {
    public EspnScoreFeed(EspnClient client);
    static List<GameSnapshot> parse(String json, String league);         // package-private, tested on fixtures
}
```

- [ ] **Step 1: Capture fixtures.** With the honest User-Agent against `site.web.api.espn.com`, save one game per file, trimmed with `jq '{events: [.events[] | select(.id=="<id>")]}'`:
  - `mlb-live.json` (an inning in progress, with `situation.outs`), `mlb-final.json`, `nfl-scheduled.json`, `nfl-live.json` (needs a Sunday or Thursday game), `nfl-final.json`, `nba-scheduled.json` (preseason), `nhl-live.json` or `nhl-final.json` (preseason), `empty.json` (`{"events":[]}`), `broken.json` (two games: one with `competitors` removed, one valid).
  - Make `nfl-halftime.json`, `nba-end-period.json`, `mlb-delayed.json`, `mlb-postponed.json` by editing a captured live file's `status.type.name`/`state`/`completed`.
  - **Record what you found** in a comment at the top of `EspnScoreFeed`: the real status names seen, and where MLB's half-inning and outs live (`status.type.shortDetail` such as `"Bot 4th"`, `"Mid 4th"`, `"End 4th"`; `competitions[0].situation.outs`). If a live file for a sport cannot be captured yet, leave that sport's live test `@Disabled("needs live capture")` and say so in the task report.

- [ ] **Step 2: Write the failing tests** (`EspnScoreFeedTest`), each loading a fixture with `getResourceAsStream`:

```java
@Test void nflLiveParsesPositionAndScore() { /* phase LIVE, period, clockTenths = round(clock*10), scores, status IN_PROGRESS, gameId "espn-<id>" */ }
@Test void nflScheduledHasStartTime()      { /* phase PRE, status SCHEDULED, startTimeMillis = Instant.parse(event.date) */ }
@Test void halftimeAndEndPeriodStages()    { /* HALFTIME → Stage.HALFTIME status HALFTIME; END_PERIOD → Stage.END_PERIOD */ }
@Test void mlbLiveHasHalfAndOuts()         { /* half from shortDetail, outs from situation, clockTenths 0 */ }
@Test void mlbDelayedAndPostponed()        { /* delayed in game → LIVE + DELAYED; postponed → PRE + DELAYED, status POSTPONED */ }
@Test void finalIsPhaseFinal()             { /* completed true → FINAL phase, status FINAL */ }
@Test void emptyScoreboardIsEmptyList()    { /* Review Focus 2: empty.json and "{}" → [] */ }
@Test void brokenGameIsSkipped()           { /* Review Focus 1: broken.json → exactly 1 snapshot */ }
@Test void unknownStatusFallsBackToState() { /* name "STATUS_SOMETHING_NEW", state "in" → LIVE, IN_PROGRESS */ }
```

- [ ] **Step 3: Run.** Expected: compile failure.

- [ ] **Step 4: Implement.** Status mapping (by `status.type.name`, then fallback on `state`):

| ESPN name | status | phase | stage |
|---|---|---|---|
| `STATUS_SCHEDULED` | SCHEDULED | PRE | IN_PLAY |
| `STATUS_IN_PROGRESS`, other names with state `in` | IN_PROGRESS | LIVE | IN_PLAY |
| `STATUS_END_PERIOD` | END_PERIOD | LIVE | END_PERIOD |
| `STATUS_HALFTIME` | HALFTIME | LIVE | HALFTIME |
| `STATUS_DELAYED`, `STATUS_RAIN_DELAY` | DELAYED | LIVE if state `in`, else PRE | DELAYED |
| `STATUS_POSTPONED` | POSTPONED | PRE | DELAYED |
| `STATUS_SUSPENDED` | SUSPENDED | LIVE | DELAYED |
| `STATUS_CANCELED` | CANCELED | FINAL | IN_PLAY |
| `completed: true` (any name, e.g. `STATUS_FINAL`) | FINAL | FINAL | IN_PLAY |
| unknown, state `pre` / `in` / `post` | SCHEDULED / IN_PROGRESS / FINAL if completed else SUSPENDED | by state | IN_PLAY |

Adjust names to what Step 1 found. Log an unknown name once per name (a `Set<String>` of names already logged). Parse each game in its own `try`; a bad game is logged and skipped. `EspnClient.scoreboard`: URL `https://<host>/apis/site/v2/sports/<sport>/<league>/scoreboard`; `403` or `429` → `FeedBlockedException`; other non-200 → `IOException`.

- [ ] **Step 5: Point `EspnProxyHandler` at `EspnClient`.** Constructor takes `EspnClient`; `handleUpcoming` calls `client.scoreboard(league)` instead of building its own `site.api.espn.com` URL. Its parsing is unchanged. `GatewayMain` passes `EspnClient.fromEnv()`.

- [ ] **Step 6: Chart settings.** `values.yaml`: `espn: { host: site.web.api.espn.com, userAgent: "sports-notifier/1.0 (+https://github.com/boboborenstein-collab/sports-notifier)" }`. Set `ESPN_HOST` and `ESPN_USER_AGENT` from it in the gateway's env and the poller's env.

- [ ] **Step 7: Run.** `make test`. Expected: PASS. `helm template deploy/helm/sports-notifier -f deploy/helm/sports-notifier/values-local.yaml | grep -A1 ESPN_HOST` shows the value for the gateway.

- [ ] **Step 8: Commit.** `"Add ScoreFeed with the ESPN adapter; gateway and poller share host and User-Agent settings"`

---

### Task 5: `PollDecision`

**Files:**
- Create: `src/main/java/feed/PollDecision.java`
- Create: `src/test/java/feed/PollDecisionTest.java`

**Interfaces:**
- Consumes: `GameSnapshot`, `Seq.of` (Task 3); `GameEvent` 13-arg constructor (Task 2).
- Produces: `public static Optional<GameEvent> decide(Map<String,String> record, GameSnapshot snap, long nowMillis)`. `record` is the `HGETALL game:<id>` result, empty when there is none.

- [ ] **Step 1: Write the failing tests**, one per row of spec 3.3, in priority order. Every returned event must have `seq == Seq.of(snap)`, `eventId == snap.gameId() + ":" + seq`, `timestamp == nowMillis`, `gameStatus == snap.status()`.

```java
@Test void firstSightIsSync()                  { /* empty record, live snap → GAME_SYNC */ }
@Test void firstSightFinalIsSync()             { /* empty record, final snap → GAME_SYNC */ }
@Test void realFinalIsGameEnd()                { /* record IN_PROGRESS, snap FINAL → GAME_END "Final: LAL 101 - 99 BOS", team = home */ }
@Test void canceledIsSyncNotEnd()              { /* record SCHEDULED, snap CANCELED → GAME_SYNC */ }
@Test void scheduledToLiveIsStartWithScore()   { /* record lastSeq phase 0, snap LIVE 2-0 → GAME_START "LAL vs BOS has started", homeScore 2 */ }
@Test void postponedThenLiveIsStart()          { /* record status POSTPONED, lastSeq phase digit 0 → GAME_START */ }
@Test void scoreUpIsScoreUpdate()              { /* record 2-0, snap 2-3 → SCORE_UPDATE team "BOS", "BOS scores! LAL 2 - 3 BOS" */ }
@Test void bothScoresUpNamesBiggerRise()       { /* record 2-0, snap 3-3 → team "BOS" */ }
@Test void scoreDownIsSync()                   { /* record 7-0, snap 0-0 live → GAME_SYNC */ }
@Test void scoreChangeAfterFinalIsSync()       { /* record FINAL 101-99, snap FINAL 101-100 → GAME_SYNC, never SCORE_UPDATE */ }
@Test void statusOnlyChangeIsSync()            { /* record IN_PROGRESS, snap HALFTIME same score → GAME_SYNC */ }
@Test void clockOnlyChangeIsNothing()          { /* same status and score, different clock → Optional.empty() */ }
```

- [ ] **Step 2: Run.** Expected: compile failure.

- [ ] **Step 3: Implement `decide`.** Rules, first match wins:
  1. Empty record → `GAME_SYNC`.
  2. Record status not `FINAL` and snap status `FINAL` → `GAME_END`.
  3. Record not started (`Long.parseLong(record lastSeq) / 10¹³ == 0`) and snap phase `LIVE` → `GAME_START`.
  4. Snap phase `LIVE` and a score above the record's → `SCORE_UPDATE`.
  5. Status differs or any score differs → `GAME_SYNC`.
  6. Otherwise empty.

  Descriptions: `"<H> vs <A> has started"`, `"<T> scores! <H> h - a <A>"`, `"Final: <H> h - a <A>"`, and `"<H> vs <A>: <status>"` for `GAME_SYNC`. `team` is the scorer for `SCORE_UPDATE`, the home team otherwise.

- [ ] **Step 4: Run.** Expected: PASS.

- [ ] **Step 5: Commit.** `"Add PollDecision: one event per snapshot, alerts only on changes we saw"`

---

### Task 6: The stateless poller

**Files:**
- Create: `src/main/java/feed/EventSink.java`, `src/main/java/feed/HttpEventSink.java`, `src/main/java/feed/Poller.java`
- Modify: `src/main/java/simulator/EspnPollerMain.java` (becomes the scheduling loop only)
- Create: `src/test/java/feed/PollerTest.java`
- Modify: `deploy/helm/sports-notifier/templates/espn-poller.yaml`, `values.yaml`

**Interfaces:**
- Consumes: `ScoreFeed`, `EspnScoreFeed`, `EspnClient` (Task 4); `PollDecision.decide` (Task 5); `EventProcessingServiceImpl.processEvent → ProcessResult` (Task 2).
- Produces:

```java
public interface EventSink { int post(GameEvent event) throws Exception; }   // returns HTTP status
public final class HttpEventSink implements EventSink { public HttpEventSink(String epsUrl); }
public final class Poller {
    public static final long SOON_MILLIS = 10 * 60 * 1000;
    public Poller(ScoreFeed feed, JedisPool pool, EventSink sink, LongSupplier clock);
    /** Polls one league once. Returns true if the league should be polled at the live rate. */
    public boolean pollLeague(String league) throws FeedBlockedException, IOException;
}
```

- [ ] **Step 1: Write the failing tests** (`PollerTest`). Fakes inside the test: `FakeFeed` (returns whatever list the test sets), `InProcessSink` (calls a real `EventProcessingServiceImpl` on DB 1 and returns `200`, or `400` for `INVALID`), `FailOnceSink` (returns `503` on the first call, then delegates).

```java
@Test void firstSightIsQuiet()              { /* live + final snaps, empty Redis → 2 records exist, xlen 0 */ }
@Test void scheduledThenLiveSendsOneStart() { /* poll scheduled, then live → xlen 1, history type GAME_START */ }
@Test void twoPollersSendEachChangeOnce() throws Exception {
    // Spec 3.5: rolling-update overlap. Two Pollers, one Redis, same feed.
    // poll scheduled once; switch feed to live 2-0; both pollers pollLeague concurrently (start gate);
    // then switch to 2-3 and do it again → xlen == 2 (one GAME_START, one SCORE_UPDATE)
}
@Test void failedPostIsRetriedByNextPoll()  { /* FailOnceSink; scheduled→live: poll 1 → 503, record unchanged, xlen 0; poll 2 → xlen 1, same eventId */ }
@Test void expiredFinalSeenAgainIsQuiet()   { /* final accepted, DEL game key, poll final again → GAME_SYNC, xlen unchanged */ }
@Test void liveLeagueIsFast()               { /* any LIVE snap → true */ }
@Test void gameStartingSoonIsFast()         { /* PRE, start = now + 5 min → true */ }
@Test void lateStartIsFast()                { /* PRE, start = now − 3 min, status SCHEDULED → true */ }
@Test void farFutureGameIsSlow()            { /* PRE, start = now + 2 h → false */ }
@Test void postponedIsSlow()                { /* PRE, status POSTPONED, start = now − 3 min → false */ }
```

- [ ] **Step 2: Run.** Expected: compile failure.

- [ ] **Step 3: Implement `Poller.pollLeague`.** For each snapshot: `HGETALL game:<gameId>` → `PollDecision.decide(record, snap, clock.getAsLong())` → if present, `sink.post(event)`. Status `2xx`: done (log accepted at info, duplicate and stale at debug, i.e. only when `POLLER_DEBUG=true`). `4xx`: log an error with the event id, no retry. `5xx` or an exception: log a warning; the next poll retries because the record is unchanged. One game's failure does not stop the others. Fast if any snapshot is `LIVE`, or is `PRE` with status `SCHEDULED` and `startTimeMillis − now ≤ SOON_MILLIS` (this includes start times already passed).

  Comment why at the top of the class: the record the Lua script saved is the poller's memory, so a restart loses nothing, a failed POST is retried by the next poll with the same id, and two pollers can't double-send.

- [ ] **Step 4: Rewrite `EspnPollerMain` as the loop.** Env: `EPS_URL` (default `http://127.0.0.1:8083`), `ESPN_POLL_LIVE_MS` (5000), `ESPN_POLL_IDLE_MS` (60000). One single-thread `ScheduledExecutorService` per league; each run schedules the next. Next delay: live rate if `pollLeague` returned true, idle rate otherwise. On `FeedBlockedException`: `min(max(previousDelay, idle) * 2, 300000)` with a warning naming the league, host, and status. Reset on the next success. Initial stagger stays 0/2/4/6 s. Remove the `HashMap`, the `Snapshot` class, `uuid()`, and `ESPN_POLL_INTERVAL_MS`.

- [ ] **Step 5: Run.** `make test`. Expected: PASS.

- [ ] **Step 6: Chart.** In `espn-poller.yaml`: remove `strategy: Recreate` and the two "until Phase 2" comments; replace with one comment: overlapping pollers are harmless because ids and `seq` come from ESPN's data and the Lua script decides. Keep `replicas: 1`. Env `ESPN_POLL_LIVE_MS` / `ESPN_POLL_IDLE_MS` from `values.yaml` `espnPoller: { enabled: false, liveMs: 5000, idleMs: 60000 }` (replacing `pollIntervalMs`). Fix the `values.yaml` comment above `espnPoller`.

- [ ] **Step 7: Run with the poller on.** `make up` with `--set espnPoller.enabled=true` (add `EXTRA_HELM_ARGS` to the Makefile's `up` if needed), then `kubectl logs deploy/espn-poller` shows one line per league, no exceptions, and first-sight syncs. Then `kubectl rollout restart deploy/espn-poller` and confirm the new pod sends **no** `GAME_START` for games already live. `make smoke` still passes.

- [ ] **Step 8: Commit.** `"Make the ESPN poller stateless: diff against the game record, adaptive polling, block backoff"`

---

### Task 7: Old games expire

**Files:**
- Modify: `src/main/java/service/impl/EventProcessingServiceImpl.java` (script and its call)
- Modify: `src/main/java/util/RedisKeys.java`
- Modify: `src/main/java/gateway/GameListingHandler.java`
- Create: `src/test/java/service/impl/GameExpiryTest.java`, `src/test/java/gateway/GameListingHandlerTest.java`

**Interfaces:**
- Consumes: the Task 2 script contract.
- Produces: `RedisKeys.GAMES_RECENT = "games:recent"`. Script: `KEYS[3] = games:recent`, `ARGV[10] = gameId`, `ARGV[11] = finished TTL seconds (86400)`, `ARGV[12] = idle TTL and recent window, seconds (691200)`. `GameListingHandler.listGames() → List<Map<String,String>>` (public; `handleList` wraps it).

- [ ] **Step 1: Write the failing tests.**

```java
// GameExpiryTest
@Test void finalGameExpiresWithinADay()  { /* GAME_END status FINAL → 0 < ttl(game:g1) <= 86400 */ }
@Test void canceledGameExpiresWithinADay() { /* status CANCELED → ttl <= 86400 */ }
@Test void liveGameKeepsEightDays()      { /* IN_PROGRESS → ttl > 86400 && <= 691200 */ }
@Test void acceptedEventJoinsRecentList() { /* zscore games:recent g1 != null */ }
// GameListingHandlerTest
@Test void listsRecentGamesNewestFirst() { /* two games accepted → both, with gameId field */ }
@Test void skipsExpiredRecords()         { /* DEL game:g1 → only g2 listed */ }
@Test void leavesOutScheduledGames()     { /* GAME_SYNC status SCHEDULED → not listed */ }
```

- [ ] **Step 2: Run.** Expected: FAIL (no TTL, no `games:recent`).

- [ ] **Step 3: Extend the script** after the `HSET`: `EXPIRE KEYS[1]` with `ARGV[11]` when status is `FINAL` or `CANCELED`, else `ARGV[12]`; `local now = tonumber(redis.call('TIME')[1])`; `ZADD KEYS[3] now ARGV[10]`; `ZREMRANGEBYSCORE KEYS[3] -inf (now - ARGV[12])`. TTLs as named constants in `EventProcessingServiceImpl`.

- [ ] **Step 4: Rewrite `GameListingHandler.listGames`:** `ZREVRANGE games:recent 0 -1`, pipeline `HGETALL` for each, skip empty maps and status `SCHEDULED`, add `gameId`. Update the class Javadoc (no more full scan).

- [ ] **Step 5: Run.** `make test`, then `make up smoke`. Expected: PASS, and `http://localhost:8080` shows the simulated games.

- [ ] **Step 6: Commit.** `"Expire finished and idle games; list games from a bounded recent set"`

---

### Task 8: Signup race and password length

**Files:**
- Create: `src/main/java/service/impl/AccountService.java`
- Modify: `src/main/java/service/impl/UserPreferenceServiceMain.java`
- Modify: `src/main/java/util/RedisKeys.java`
- Create: `src/test/java/service/impl/AccountServiceTest.java`

**Interfaces:**
- Produces:

```java
public final class AccountService {
    public record Result(int status, String userId, String username, String error) {}
    public AccountService(JedisPool pool);
    public Result signup(String username, String password);   // 200 | 400 | 409
    public Result login(String username, String password);    // 200 | 401
}
```

`RedisKeys.USER_PREFIX = "user:"`, `RedisKeys.USERNAME_PREFIX = "username:"`.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void twentyConcurrentSignupsOneWinner() { /* 20 threads, same name, start gate → one 200, nineteen 409, exactly one username: key, exactly one user: key */ }
@Test void caseVariantsAreTheSameName()       { /* Review Focus 5: "Bob" and "bob" concurrently → one 200, one 409 */ }
@Test void passwordUnderEightRejected()       { /* "1234567" → 400, error mentions 8 */ }
@Test void passwordOverSeventyTwoBytesRejected() { /* 73 ASCII → 400; 19 four-byte emoji (76 bytes, 38 chars) → 400 */ }
@Test void seventyTwoBytesAccepted()          { /* 72 ASCII → 200, and login with it → 200 */ }
@Test void blankUsernameRejected()            { /* "  " → 400 */ }
@Test void wrongPasswordIs401()               { /* signup then login wrong → 401 */ }
```

- [ ] **Step 2: Run.** Expected: compile failure.

- [ ] **Step 3: Implement.** Signup order (comment why: a crash leaves an unreachable record, never a name with no account): trim and lowercase the name; validate; write `user:<newId>`; `SET username:<name> <id> NX`; on failure `DEL user:<newId>` and return 409. Password rule: `password.length() >= 8` and `password.getBytes(UTF_8).length <= 72`, messages `"password must be at least 8 characters"` / `"password must be at most 72 bytes"`. Move login's lookup here unchanged except for the key constants.

- [ ] **Step 4: Wire `UserPreferenceServiceMain`** routes `/users` and `/users/login` to `AccountService`, writing `Result` as the same JSON as today (`userId`, `username`, or `error`). Missing `username` or `password` in the body → `400` (not a `NullPointerException`).

- [ ] **Step 5: Run.** `make test`, `make up smoke`. Expected: PASS.

- [ ] **Step 6: Commit.** `"Claim usernames with SET NX; passwords 8 to 72 bytes"`

---

### Task 9: Login throttle, client address, cookie

**Files:**
- Create: `src/main/java/gateway/ClientAddress.java`, `src/main/java/gateway/LoginThrottle.java`
- Modify: `src/main/java/gateway/GatewayMain.java` (`/api/login`), `src/main/java/gateway/SessionAuth.java`
- Modify: `src/main/java/util/RedisKeys.java`
- Modify: `deploy/helm/sports-notifier/values-local.yaml`
- Create: `src/test/java/gateway/ClientAddressTest.java`, `src/test/java/gateway/LoginThrottleTest.java`, `src/test/java/gateway/SessionAuthTest.java`

**Interfaces:**
- Produces:

```java
public final class ClientAddress { public static String of(String xRealIp, String xForwardedFor, String remoteAddr); }
public final class LoginThrottle {
    public static final int USER_LIMIT = 5, IP_LIMIT = 20; public static final int WINDOW_SECONDS = 900;
    public LoginThrottle(JedisPool pool);
    public boolean blocked(String usernameOrNull, String ip);
    public void recordFailure(String usernameOrNull, String ip);
    public void recordSuccess(String username);
}
// SessionAuth
static Cookie sessionCookie(String sid, int maxAgeSeconds, boolean secure);   // package-private
```

`RedisKeys.LOGIN_FAIL_PREFIX = "login:fail:"`; keys `login:fail:user:<name>` and `login:fail:ip:<ip>`.

- [ ] **Step 1: Write the failing tests.**

```java
// ClientAddressTest
@Test void prefersRealIp()            { assertEquals("9.9.9.9", ClientAddress.of("9.9.9.9", "1.1.1.1, 9.9.9.9", "10.0.0.5")); }
@Test void elseLastForwardedEntry()   { assertEquals("9.9.9.9", ClientAddress.of(null, "6.6.6.6, 9.9.9.9", "10.0.0.5")); }
@Test void elseSocketAddress()        { assertEquals("10.0.0.5", ClientAddress.of(null, null, "10.0.0.5")); }
@Test void neverTheFirstEntry()       { /* "1.2.3.4" first, "9.9.9.9" last, no real ip → "9.9.9.9" */ }
// LoginThrottleTest
@Test void sixthAttemptOnUsernameBlocked()   { /* 5 failures → blocked(user, anyIp) true */ }
@Test void twentyFirstFromAddressBlocked()   { /* 20 failures, 20 different usernames, same ip → blocked */ }
@Test void spoofedForwardedHeaderDoesNotHelp() {
    // Spec 5.3: 20 failures, each with a new made-up first X-Forwarded-For entry and the same X-Real-Ip.
    // ip = ClientAddress.of("9.9.9.9", "<random>, 9.9.9.9", "10.0.0.5") → blocked after 20
}
@Test void successResetsUsernameCount()      { /* 4 failures, success, 4 failures → not blocked */ }
@Test void windowIsFifteenMinutes()          { /* first failure sets ttl 900 on both keys */ }
@Test void missingUsernameStillCountsAddress() { /* Review Focus 4: recordFailure(null, ip) ×20 → blocked(null, ip); no "login:fail:user:null" key */ }
// SessionAuthTest
@Test void cookieIsLaxAndHttpOnly()    { /* sameSite LAX, httpOnly true, path "/" */ }
@Test void secureFollowsSetting()      { /* secure true / false as passed */ }
```

- [ ] **Step 2: Run.** Expected: compile failure.

- [ ] **Step 3: Implement `ClientAddress`, `LoginThrottle`, and `sessionCookie`.** `ClientAddress` comment: the client writes the first `X-Forwarded-For` entry itself; Traefik appends the address it saw at the end and sets `X-Real-Ip`. `LoginThrottle.recordFailure`: `INCR` then `EXPIRE` only when the count is 1, in a pipeline per key. `SessionAuth` reads `COOKIE_SECURE` once (default `true`); `createSession` and `destroySession` both use `sessionCookie`.

- [ ] **Step 4: Wire `/api/login`.** Parse the body defensively for `username` (null if missing or not JSON). `ip = ClientAddress.of(ctx.header("X-Real-Ip"), ctx.header("X-Forwarded-For"), ctx.ip())`. If `blocked` → `429 {"error":"too many failed logins, try again later"}` without calling the user service. Forward as today; `401` → `recordFailure`; `200` → `recordSuccess`. Any upstream `4xx` is passed through, never a `500`.

- [ ] **Step 5: Laptop setting.** `values-local.yaml`: `services: { gateway: { env: { COOKIE_SECURE: "false" } } }` with a comment: some browsers (Safari) and tools refuse `Secure` cookies on plain `http://localhost`. Check that `helm template` merges it into the gateway's env alongside the URLs.

- [ ] **Step 6: Run.** `make test`, `make up smoke`. Then by hand: six wrong logins for one user through `http://localhost:8080` → the sixth is `429`.

- [ ] **Step 7: Commit.** `"Throttle logins by username and Traefik-reported address; SameSite and Secure cookie"`

---

### Task 10: Network policies and the outsider check

**Files:**
- Create: `deploy/helm/sports-notifier/templates/network-policies.yaml`
- Modify: `scripts/smoke-test.sh`

**Interfaces:**
- Consumes: labels from `_helpers.tpl` (`app.kubernetes.io/name`, `app.kubernetes.io/part-of: sports-notifier`).

- [ ] **Step 1: Write the failing check first.** Add smoke step 7: `k run netpol-probe-$RANDOM --rm -i --restart=Never --image=curlimages/curl --labels=app.kubernetes.io/name=netpol-probe -- curl -sS -m 5 http://user-pref:8081/health`. The step passes only if that command **fails** (non-zero exit). Run `make smoke`. Expected: `SMOKE FAIL` (no policies yet, so the probe gets through).

- [ ] **Step 2: Write the policies** (spec 6.2 table). One default-deny-ingress policy for the namespace, then one allow policy per row. Gateway allows from namespace `kube-system` pods labelled `app.kubernetes.io/name: traefik` on 8080 (verify the label with `k -n kube-system get pods --show-labels`). Comment at the top of the file: what a NetworkPolicy is, in one line each, and that k3s enforces them while EKS needs a policy engine (Phase 5).

- [ ] **Step 3: Check probes.** `make up`, then `k get pods`: every pod becomes Ready. If kubelet probes are blocked, add an allow rule for the node's address range and say why in a comment.

- [ ] **Step 4: Run.** `make smoke`. Expected: `SMOKE PASS` (signup and history through the gateway work, the probe pod is refused).

- [ ] **Step 5: Commit.** `"Add network policies: only the gateway faces outside; internal services accept known callers"`

---

### Task 11: Wait for Redis at startup

**Files:**
- Modify: `src/main/java/util/JedisPoolHolder.java`
- Modify: every `*Main` that uses Redis (`GatewayMain`, `UserPreferenceServiceMain`, `GameStateServiceMain`, `EventProcessingServiceMain`, `NotificationServiceMain`, `PubSubWorkerMain`, `EspnPollerMain`)
- Create: `src/test/java/util/JedisPoolHolderTest.java`

**Interfaces:**
- Produces: `public static boolean waitForRedis(String host, int port, Duration max, Duration every)` and `public static void awaitRedisOrExit(String serviceName)` (env host and port, 60 s, 2 s; logs `[<service>] waiting for Redis`; `System.exit(1)` on timeout).

- [ ] **Step 1: Write the failing tests.** `waitForRedis("localhost", 6379, 2s, 200ms)` → true. `waitForRedis("localhost", 1, 1s, 200ms)` → false, and it returns within 2 s.
- [ ] **Step 2: Run.** Expected: compile failure.
- [ ] **Step 3: Implement** with a plain `Jedis` `PING` per try, closed every time. Call `awaitRedisOrExit("<service>")` first thing in each `main` listed above.
- [ ] **Step 4: Run.** `make test`. Then `make down cluster up` and `k get pods`: restart counts are 0 for every Java pod.
- [ ] **Step 5: Commit.** `"Wait for Redis at startup instead of crash-looping"`

---

### Task 12: Daily ESPN check

**Files:**
- Create: `scripts/espn-check.sh`, `.github/workflows/espn-check.yml`

- [ ] **Step 1: Write `scripts/espn-check.sh`.** For each host in `site.api.espn.com site.web.api.espn.com` and each User-Agent in (the `EspnClient.DEFAULT_USER_AGENT` string, `Java-http-client/17`, `Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128 Safari/537.36`), request the four scoreboards with `curl -s -o body -w '%{http_code}'`, count `.events | length` with `jq`. Print a Markdown table (host, agent, league, status, games) to stdout and append it to `$GITHUB_STEP_SUMMARY` when set. Exit 1 if the default pair (`site.web.api.espn.com` + default agent) is not `200` for all four leagues.
- [ ] **Step 2: Run it locally.** `bash scripts/espn-check.sh`. Expected: a table, exit 0 from the laptop.
- [ ] **Step 3: Write the workflow.** Triggers: `workflow_dispatch` and `schedule: - cron: "17 6 * * *"` (daily, off the hour). One job on `ubuntu-latest`: checkout, run the script. Comment: GitHub's runners sit in a data center like AWS, so this warns before Phase 5 if ESPN refuses cloud addresses; a failed scheduled run emails the owner.
- [ ] **Step 4: Commit and run it once on GitHub** after the branch is pushed (`gh workflow run espn-check.yml --ref phase-2-correctness`, then `gh run watch`). Save the table for Task 13.
- [ ] **Step 5: Commit.** `"Add a daily ESPN reachability check from a data-center runner"`

---

### Task 13: Docs, live check, quiz

**Files:**
- Create: `docs/providers/espn-access.md`, `docs/study/phase-2-quiz.md`
- Modify: `DESIGN.md`, `CLAUDE.md`, `AGENTS.md`, `README.md`

- [ ] **Step 1: Live check by hand** (done-when 3). During a live MLB, NFL, NBA preseason, or NHL preseason game: `make up` with the poller on, sign up in the browser, follow the team that is *not* about to score, enable `SCORE_UPDATE`, and see the alert arrive over the WebSocket. Record the game and time in the PR description.
- [ ] **Step 2: `docs/providers/espn-access.md`:** the date, the Task 12 table from GitHub and from the laptop, the chosen defaults, and the Plan B note (poller on a home machine posting to the cloud over HTTPS; or league feeds).
- [ ] **Step 3: `DESIGN.md`:** in "Security and correctness still to fix", mark each item Phase 2 closed (fan-out, poller ids and timestamps, snapshot-before-POST, `HashMap` memory, game keys, signup race, password minimum, login throttle, cookie, network policies, dead `updateGameState`) and say which phase closes the rest. Build order step 2: **Done.**
- [ ] **Step 4: `CLAUDE.md` and `AGENTS.md`:** "Open problems" keeps only what is still true (pending-entry recovery, gateway broadcast, Lua-to-publish window); update the "Size", the test count, and "Current state" ("Next step: Phase 3"). The two files stay identical except the AI-features lines.
- [ ] **Step 5: `README.md`:** one paragraph on Phase 2, leading with the burst test now running on every push.
- [ ] **Step 6: `docs/study/phase-2-quiz.md`:** same format as `docs/study/phase-1-quiz.md` (questions, model answers in `<details>`, a summary table with every row "Not yet taken"). Cover: why id before `seq`; the `seq` digit blocks and why score total is last; why `GAME_SYNC` exists; why the poller keeps no memory; `SUNIONSTORE` vs one `SISMEMBER` per user and its blocking cost; `SET NX` ordering in signup; why the first `X-Forwarded-For` entry is unsafe; what a NetworkPolicy does; what still breaks before Phase 3.
- [ ] **Step 7: Run everything.** `make test` and `make down cluster up smoke`. Expected: all PASS.
- [ ] **Step 8: Commit.** `"Phase 2 docs: fix list, ESPN access results, study quiz"`
