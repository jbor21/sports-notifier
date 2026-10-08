# Phase 3.4: Live API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve the rest of `docs/api/`: `/leagues`, `/leagues/{id}/teams`, `/leagues/{id}/alerts`, `/games`, `/games/{id}`, and `/api/v1/ws` with `hello`, `notification`, `unread_count`, `game_update`, plus `Final`/`End` labels and the 10 s no-score heal.

**Architecture:** The accept script saves start time and line score, indexes the game in `games:start`, and publishes each accepted play on `live:<gameId>`. A schedule loop in the ESPN poller caches yesterday − 1 … today + 8 in `sched:<id>`. Game State builds `GameSummary` JSON from `game:` + `sched:` through one shared builder (`games/GameSummaries`), which the gateway also uses for `game_update`. Gateways subscribe per game, confirmed before reading current state. The unread badge is a Redis counter kept by Stage 2's delivery script and reset by the Notification service.

**Tech Stack:** Java 17, Javalin 6.1.6 (Jetty WebSocket), Jedis 4.4.7, Gson, JUnit 5, Redis DB 1 in tests, Testcontainers Kafka.

**Spec:** `docs/superpowers/specs/2026-10-08-phase-3-4-live-api-design.md`

## Global Constraints

- Redis keys only through `util/RedisKeys.java`: add `LIVE_CHANNEL_PREFIX = "live:"`, `GAMES_START = "games:start"`, `SCHED_PREFIX = "sched:"`, `UNREAD_PREFIX = "unread:"`.
- Every Jedis handle in try-with-resources; blocking subscribe connections from `JedisPoolHolder.dedicated()` (gateway) as today.
- No-score heal window: `10_000` ms.
- `games:start` keeps start times newer than `now − 3 days` (259,200,000 ms).
- Schedule: every 15 min, 11 UTC dates (today − 2 … today + 8), 4 leagues = 44 requests; `sched:<id>` TTL 10 days (864,000 s); only with the ESPN feed.
- `/games` dates: yesterday … today + 7 in the request's `tz`; otherwise 422.
- Socket: close 4401 without a session; `subscribe_games` keeps at most 100 ids matching `^(nfl|nba|mlb|nhl)-[A-Za-z0-9-]+$`; idle close after 60 s of nothing received (sweep every 10 s, code 1000, reason `idle`); confirm wait for `SUBSCRIBE` up to 2 s.
- Unread counter: `unread:<userId>`, TTL 86,400 s set only on creation (`SET NX EX` / `SET … EX` on reset); never created by `INCR`.
- Reference endpoints: `Cache-Control: public, max-age=3600`.
- Status mapping (API `GameStatus`): SCHEDULED→scheduled; IN_PROGRESS, END_PERIOD, HALFTIME→in_progress; FINAL→final; DELAYED→delayed; POSTPONED→postponed; CANCELED→canceled; SUSPENDED→suspended.
- Comment density: explain *why* at the script, the subscribe order, the counter; quiet elsewhere.
- `make test` green at the end of every task (`sh -c 'mvn -q clean && make test'`; iCloud may leave `X 2.class` duplicates in `target/`, hence `clean`).

## Review Focus

1. **Redis restarts under an open gateway** → after resubscribe, every subscribed socket gets current state again (Task 9 test `resubscribeAfterReconnectSendsCurrentState`).
2. **A tab closes while its `subscribe_games` is mid-flight** → ref counts never go negative and `UNSUBSCRIBE` happens only at zero (Task 9 test `closeDuringSubscribeLeavesNoSubscription`).
3. **A DST day** (America/New_York, 2026-11-01, 25 hours) → the day range is local midnight to next local midnight, not +24 h (Task 5 test `dstDayIsTwentyFiveHours`).
4. **ESPN schedule JSON missing venue, broadcasts, or competitors** → that game is skipped or blank-filled; the rest of the date is written (Task 4 test `partialEventsDoNotStopTheDate`).
5. **A malformed `live:` message or a team not in `teams.json`** → malformed is logged and dropped, unknown team still sends a summary with the abbreviation (Task 9 test `badLiveMessageIgnored`, Task 5 test `unknownTeamUsesAbbreviation`).

---

### Task 1: Labels: `Final`, `End`

**Files:**
- Modify: `src/main/java/alerts/GameLabels.java`
- Test: `src/test/java/alerts/GameLabelsTest.java`
- Re-record: `src/test/resources/espn/replay/*` expected files (via `make replay-detectors` / `ReplayGoldenTest`'s update path, as in 3.3)

**Interfaces:**
- Produces: `GameLabels.periodLabel(Play)` → display label per spec section 1 table (finals and between periods included); `GameLabels.position(Play)` → unchanged contract: null for non-live plays (so finals never put a label in alert text); between periods it now returns `End Q1` etc. `static String GameLabels.lineLabel(String league, int period)` → `Q1`…`Q4`, `OT`, `2OT` (NFL/NBA), `1`,`2`,`3`,`OT`,`SO` (NHL; `SO` when period 5 in regular season, else `2OT`), `1`…`n` (MLB).

- [ ] **Step 1: Write failing tests** in `GameLabelsTest`: `finalRegulationIsFinal` (NFL period 4 FINAL → `Final`), `finalOvertimeLabels` (NFL 5 → `Final/OT`, NBA 6 → `Final/2OT`, NHL 4 → `Final/OT`, NHL 5 seasonType 2 → `Final/SO`, NHL 5 seasonType 3 → `Final/2OT`, MLB 10 → `Final/10`, MLB 9 → `Final`), `betweenPeriodsSaysEnd` (NFL END_PERIOD 1 → `End Q1`, NHL END_PERIOD 2 → `End 2nd`, NHL END_PERIOD 4 → `End OT`, MLB stays `End 7th` / `Mid 7th`), `halftimeStaysHalf`, `scheduledPostponedCanceledAreNull`, `finalPositionIsNull` (`position(final)` is null, so text has no ` · Final`), `lineLabels` (the `lineLabel` values above).
- [ ] **Step 2: Run** `mvn -q test -Dtest=GameLabelsTest` → FAIL.
- [ ] **Step 3: Implement.** Split today's switch into `private static String livePeriodLabel(Play)` (adds the `End ` prefix when `stage == END_PERIOD` for non-MLB); `periodLabel` returns `"Final"` + (`"/" + suffix` when the last period is past regulation) for phase FINAL with status FINAL, null for CANCELED/PRE, else `livePeriodLabel`. `position` keeps using only live labels. MLB suffix is the inning number.
- [ ] **Step 4: Run** `mvn -q test -Dtest='alerts.*Test'` → PASS.
- [ ] **Step 5: Re-run goldens** `make replay-detectors`, then `mvn -q test -Dtest=ReplayGoldenTest`; for each golden diff confirm every changed line differs only in the label (`Q1`→`End Q1`, final label column). Save the diff to the ledger for the PR body, update the expected files, rerun → PASS.
- [ ] **Step 6: Commit** `git commit -m "Labels: Final, Final/OT, End Q1; goldens re-recorded for labels only"`.

### Task 2: No-score heal 10 s

**Files:**
- Modify: `src/main/java/service/impl/EventProcessingServiceImpl.java` (constant + the "30 seconds" comments)
- Test: `src/test/java/service/impl/EventProcessingHealTest.java`

- [ ] **Step 1: Change the tests first:** the 29/30 s test becomes `noScoreHealAtTenSeconds` (at +9,999 ms `STALE`, at +10,000 ms `ACCEPTED`, `lastSeq` lowered). Add `bruinsZeroZeroGlitchHealsInTenSeconds`: NHL period 2, 2-2: accept 3:55, accept 0:00, then 1:20 at t, 1:15 at t+5 s (both `STALE`), 1:10 at t+10 s `ACCEPTED`, `game:` clockTenths 700.
- [ ] **Step 2: Run** `mvn -q test -Dtest=EventProcessingHealTest` → FAIL.
- [ ] **Step 3: Set** `NO_SCORE_HEAL_MILLIS = 10_000L`; comments say 10 seconds.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `"No-score heal after 10 s, not 30 (the 0:00 freeze)"`.

### Task 3: Plays carry start time and line score; the script indexes and publishes

**Files:**
- Create: `src/main/java/model/LinePeriod.java` — `public record LinePeriod(int period, int home, int away) {}`
- Modify: `model/GameSnapshot.java` (new last component `List<LinePeriod> lineScore`; both existing constructors default it to `List.of()`), `model/GameEvent.java` (fields `long startTime`, `List<LinePeriod> lineScore`; getters; `GameEvent withSchedule(long startTime, List<LinePeriod> lineScore)`; `copy()` carries them), `feed/PollDecision.java` (`event(...)` adds `.withSchedule(snap.startTimeMillis(), snap.lineScore())`), `feed/EspnScoreFeed.java` (read `competitors[].linescores[].value` into `lineScore`; period = index + 1; missing → empty), `simulator/GameScript.java` (`snapshot` builds the line score: for steps 0…i, each step's score rise goes to that step's period), `util/RedisKeys.java` (the four new keys), `service/impl/EventProcessingServiceImpl.java`
- Test: `feed/EspnScoreFeedTest.java`, `simulator/SimulatedScoreFeedTest.java`, `service/impl/EventProcessingServiceImplTest.java`, new `service/impl/LivePublishTest.java`

**Interfaces:**
- Produces: `game:<id>` fields `startTime` (epoch ms, string) and `lineScore` (JSON array `[{"period":1,"home":7,"away":3},…]`); `games:start` zset member gameId score startTime; channel `live:<gameId>` message `{"version":<n>,"play":<GameEventParser.toJson(play)>}`.
- Script additions: `KEYS[7] = games:start`; `ARGV[25] = startTime`, `ARGV[26] = lineScore JSON`, `ARGV[27] = live channel name`, `ARGV[28] = games:start window millis (259200000)`.

- [ ] **Step 1: Failing tests.**
  - `EspnScoreFeedTest.lineScoreAndStartTime`: from an existing live fixture (`nfl-final.json`), the snapshot's `lineScore` sums to each side's score and has 4+ periods; `startTimeMillis` equals the fixture's `date`. `missingLinescoresIsEmpty` (fixture with `linescores` removed).
  - `SimulatedScoreFeedTest.lineScoreSumsToScore` at every step of the NFL script.
  - `LivePublishTest` (subscribe on a dedicated Jedis in a thread, DB 1): `acceptedPlayIsPublished` (message has `version` equal to the returned version and `play.gameId`); `staleAndPendingPublishNothing`; `tenAcceptsPublishInVersionOrder`.
  - `EventProcessingServiceImplTest.savesStartTimeAndLineScore`, `indexesGamesStartAndTrims` (a game with start 4 days before `now` is removed when another play is accepted; start 2 days before stays), `zeroStartTimeNotIndexed`.
- [ ] **Step 2: Run** those classes → FAIL.
- [ ] **Step 3: Implement.** In the script, after the `HSET` of state: `HSET startTime, lineScore`; `if tonumber(ARGV[25]) > 0 then ZADD KEYS[7] ARGV[25] ARGV[10] end`; `ZREMRANGEBYSCORE KEYS[7] -inf (ARGV[13] - ARGV[28])`; last before `return`: `PUBLISH ARGV[27] '{"version":' .. version .. ',"play":' .. ARGV[14] .. '}'`. Extend the script's header comment (step 7: publish, and why it is in the script: version order, saved state only). Line score JSON via Gson in Java.
- [ ] **Step 4: Run** the classes, then `make test` → PASS (existing tests that build `GameSnapshot` through the short constructors are unaffected).
- [ ] **Step 5: Commit** `"Plays carry start time and line score; the script indexes games:start and publishes live:<gameId>"`.

### Task 4: Schedule loop

**Files:**
- Modify: `feed/EspnClient.java` — `String scoreboard(String league, LocalDate date)` (`?dates=yyyyMMdd`); the no-date method stays.
- Modify: `feed/EspnScoreFeed.java` — make the per-event parse reachable: `static List<ScheduleEntry> parseSchedule(String league, String json)`.
- Create: `feed/ScheduleEntry.java` — `record ScheduleEntry(GameSnapshot snap, String venue, String broadcast)`; venue = `competitions[0].venue.fullName`, broadcast = first `competitions[0].broadcasts[0].names[0]`; null when missing.
- Create: `feed/ScheduleWriter.java` — `ScheduleWriter(JedisPool pool, LongSupplier now)`; `void write(List<ScheduleEntry> entries)`: per entry `HSET sched:<id>` (league, homeTeam, awayTeam, homeScore, awayScore, status, phase, period, clockTenths, half, outs, stage, seasonType, detail, startTime, lineScore, venue, broadcast, fetchedAt — same field names as `game:`), `EXPIRE 864000`, `ZADD games:start startTime id` when startTime > 0; pipelined; never touches `game:`.
- Create: `feed/ScheduleLoop.java` — `ScheduleLoop(EspnClient espn, ScheduleWriter writer, Clock clock)`; `int runOnce()` (all leagues × dates; logs and skips a failed league/date; returns games written); `static List<LocalDate> dates(LocalDate todayUtc)` → today − 2 … today + 8.
- Modify: `simulator/EspnPollerMain.java` — schedule `runOnce` at start and every 15 min on its own thread.
- Test: `feed/ScheduleLoopTest.java`, `feed/ScheduleWriterTest.java`; fixture `src/test/resources/espn/schedule-nfl.json` (trimmed real `dates=` response via `scripts/trim-espn-fixture.py`; venue and broadcast kept).

- [ ] **Step 1: Failing tests.** `datesAreElevenUtcDays` (2026-10-08 → 2026-10-06 … 2026-10-16, size 11); `writesSchedAndIndex` (fixture → `sched:` fields incl. venue, broadcast, startTime; TTL ≤ 864000; `games:start` scores); `leavesGameRecordAlone` (a pre-existing `game:<id>` hash is byte-identical after `write`); `partialEventsDoNotStopTheDate` (fixture with one event missing competitors and one missing venue/broadcasts → the bad one skipped, the other written with blanks); `failedDateIsSkipped` (a fake fetch that throws for one date → the other 43 still written, `runOnce` returns their count).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** as in Files. `ScheduleLoop` takes a `BiFunction`-style fetcher interface `ScheduleSource { String fetch(String league, LocalDate date) throws Exception; }` so tests fake ESPN; production passes `espn::scoreboard`.
- [ ] **Step 4: Run** → PASS; `make test` → PASS.
- [ ] **Step 5: Commit** `"Schedule loop: yesterday to +7 days from ESPN into sched:<id> and games:start"`.

### Task 5: `GameSummaries` and the game endpoints

**Files:**
- Create: `src/main/java/games/GameSummaries.java`, `src/main/java/games/GameDays.java`, `src/main/java/games/GameQuery.java`
- Modify: `service/GameStateService.java` (replace `getGameState` with `List<JsonObject> listDay(GameQuery q)` and `Optional<JsonObject> detail(String gameId)`), `service/impl/GameStateServiceImpl.java`, `service/impl/GameStateServiceMain.java` (routes `GET /games?from=&to=&league=&team=&status=` and `GET /games/{id}`), `catalog/Teams.java` (Team gains `location`, `name`, `logoUrl`, `primaryColor`, `secondaryColor`, nullable until Task 6 fills `teams.json`)
- Create: `gateway/GameRoutes.java` — `/api/v1/games`, `/api/v1/games/{id}`
- Modify: `gateway/GatewayMain.java` (register `GameRoutes`)
- Delete: `model/GameState.java`, `service/impl/GameStateServiceImplTest.java` (replaced)
- Test: `games/GameSummariesTest.java`, `games/GameDaysTest.java`, `service/impl/GameStateServiceImplTest.java` (new), `gateway/GameRoutesTest.java`

**Interfaces:**
- `GameSummaries(Teams teams)`; `JsonObject summary(Map<String,String> game, Map<String,String> sched)` (either map may be empty, not both); `JsonObject detail(Map<String,String> game, Map<String,String> sched)` (adds `lineScore` with `label` from `GameLabels.lineLabel`, `venue`, `broadcast`); `JsonObject fromLive(GameEvent play, long version)` (same shape as `summary`, `updatedAt` from `play.getTimestamp()`); `static String apiStatus(String status)`.
- `GameDays.range(LocalDate date, ZoneId tz, Instant now)` → `record Range(long fromMillis, long toMillis)`; throws `GameDays.OutOfRange` (message names the allowed first and last dates) when `date` is outside yesterday … today + 7 in `tz`.
- `GameQuery(long fromMillis, long toMillis, String league, String teamId, String status)`.
- Game State `GET /games?from=&to=` returns `{"items":[GameSummary…]}`, sorted by startTime then id. The gateway computes the range, fills `tz` (query → user's timezone via User Preference `/users/{id}` when signed in → UTC), answers 422 itself for bad `date`/`tz`/range, applies `following=true` (401 without a session; follows from User Preference `/users/{id}/follows`: keep games whose id, `leagueId`, or either team id is a follow target), and returns `{"date":"yyyy-MM-dd","items":[…]}`.

- [ ] **Step 1: Failing tests.**
  - `GameSummariesTest`: `statusMapping` (all nine), `recordOnly`, `schedOnlyHasVersionZero`, `recordWinsOverSched` (score/status/version from `game:`, venue/broadcast from `sched:` in `detail`), `unknownTeamUsesAbbreviation`, `lineScoreLabelsPerLeague`, `liveMatchesSummary` (`fromLive(play, v)` equals `summary(record written by the script for that play)` except `updatedAt`), `scheduledHasNoPeriod`.
  - `GameDaysTest`: `yesterdayToSevenAhead` (ok at both ends, 422 one past each), `laTimezoneLateUtc` (now 2026-10-09T03:30Z, tz America/Los_Angeles → "today" is 2026-10-08), `dstDayIsTwentyFiveHours` (America/New_York 2026-11-01 → toMillis − fromMillis = 25 h), `badTzIs422` (in `GameRoutesTest`).
  - `GameStateServiceImplTest` (Redis): `listsTheDaysGamesInStartOrder`, `skipsIdsWithNeitherHash`, `filtersLeagueTeamStatus`, `detail404WhenUnknown`.
  - `GameRoutesTest` (gateway with fake services via Javalin on random ports): `followingNeedsSession` (401), `followingKeepsFollowedGames` (team, league, and game follows), `tzDefaultsToUsersTimezone`, `outOfRangeIs422`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.** `summary` converts the merged fields to a `Play` (`Play` built from field map: the same names the script writes) and calls `GameLabels`; `TeamRef` via `TeamIds.of(league, abbr)` and `Teams.find`. Delete `GameState` and its old test.
- [ ] **Step 4: Run** → PASS; `make test` → PASS.
- [ ] **Step 5: Commit** `"/games and /games/{id}: one GameSummary builder over game: and sched:"`.

### Task 6: Reference endpoints

**Files:**
- Modify: `scripts/fetch-teams.py` (add `location`, `name`, `logoUrl` = first `logos[].href`, `colors.primary`/`secondary` = `#` + ESPN `color`/`alternateColor`; keep the 124-id check), `src/main/resources/catalog/teams.json` (re-run)
- Create: `src/main/resources/catalog/leagues.json` (NFL football quarter 4; NBA basketball quarter 4; MLB baseball inning 9; NHL hockey period 3; `name`s; `logoUrl` from ESPN's league logo URLs)
- Create: `catalog/Leagues.java` — `static Leagues load()`, `List<JsonObject> all()`, `boolean exists(String id)`
- Create: `gateway/ReferenceRoutes.java` — `register(Javalin app, Leagues leagues, Teams teams, AlertCatalog catalog)`
- Test: `gateway/ReferenceRoutesTest.java`, `catalog/TeamsTest.java` (extend)

- [ ] **Step 1: Failing tests.** `leaguesListsFour` (ids NFL, NBA, MLB, NHL with `periodName`, `regulationPeriods`); `teamsAlphabetical` (`/leagues/NFL/teams` sorted by displayName, each has `logoUrl`, `colors.primary` starting `#`); `alertsInCatalogOrder` (equals `AlertCatalog.forLeague("NFL")` types in order, `DAILY_SUMMARY` `coming_soon`); `unknownLeague404` (both teams and alerts, API `Error` shape); `cacheHeader` (all three); `TeamsTest.everyTeamHasLogoAndColors`; existing `TeamsTest` id check still 124.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement**; run `python3 scripts/fetch-teams.py` and confirm `git diff --stat` shows ids unchanged (script check).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `"Reference endpoints: /leagues, teams with logos and colors, alert catalog"`.

### Task 7: Unread counter

**Files:**
- Modify: `model/NotificationMessage.java` (field `transient long createdAt`; `setCreatedAt(long)`, `getCreatedAt()`), `service/impl/Stage2Worker.java` (set `createdAt` from `r.timestamp()` after parse), `service/impl/Delivery.java`
- Create: `service/impl/UnreadCounter.java` (Notification service side): `UnreadCounter(JedisPool pool)`; `void reset(String userId)`
- Modify: `service/impl/NotificationRoutes.java` (`register(app, history, unread)`; after `markSeen` and after `clear` → `unread.reset(userId)`; a Redis failure is logged, the 204 still goes out), `service/impl/NotificationServiceMain.java` (`JedisPoolHolder.awaitRedisOrExit`, pool, pass `UnreadCounter`)
- Test: `service/impl/DeliveryTest.java`, new `service/impl/UnreadCounterTest.java`, `gateway/ApiV1NotificationsTest.java` (wiring)

**Interfaces:**
- Delivery script: `KEYS[3] = unread:<userId>`; `ARGV[2]` = gateway message `{"userId":…,"payload":{"type":"notification","notification":<body + createdAt ISO-8601>}}`; `ARGV[4] = userId JSON string`. After the notification publishes and before the claim: `if EXISTS KEYS[3] then local n = INCR KEYS[3]; PUBLISH to each gateway '{"userId":' .. ARGV[4] .. ',"payload":{"type":"unread_count","count":' .. n .. '}}' end`. A message with a null body publishes nothing to sockets (pre-3.3 shape; none should remain) but still claims and counts.
- `UnreadCounter.reset` script: `SET unread:<userId> 0 EX 86400`; `ZREMRANGEBYSCORE conn:<userId> -inf now`; publish `{"userId":…,"payload":{"type":"unread_count","count":0}}` to `gw:<each>`.
- Gateway (used by Task 8): `gateway/UnreadCount.java` — `UnreadCount(JedisPool pool, Function<String,Integer> fromPostgres)`; `int get(String userId)`: `GET`; if missing → `fromPostgres` then `SET NX EX 86400`, return what is now in Redis.

- [ ] **Step 1: Failing tests.** `DeliveryTest`: `notificationInApiShape` (payload `type` = notification, `notification.id` = message id, `createdAt` = the Kafka timestamp), `counterPresentIncrementsAndPublishesAfter` (two messages on the gateway channel, notification first, count = old + 1), `counterMissingStaysMissing` (no key after, no `unread_count`), `redeliveryCountsOnce`. `UnreadCounterTest`: `resetSetsZeroWithTtlAndTellsEveryGateway` (two gateways in `conn:`, both hear count 0; TTL in (0, 86400]). Gateway `UnreadCountTest`: `missingAsksPostgresOnce`, `presentSkipsPostgres`, `racingIncrementIsKept` (key set between the Postgres read and SET NX → the Redis value wins).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.** Keep the delivery script's order comment: publish, count, then claim last (a failing write must not leave a claim).
- [ ] **Step 4: Run** → PASS; `make test` → PASS.
- [ ] **Step 5: Commit** `"Unread badge: unread:<userId> counted in the delivery script, reset on seen and clear"`.

### Task 8: `/api/v1/ws`

**Files:**
- Create: `gateway/SocketSession.java` — per socket: `userId`, `ClientSocket socket`, `volatile long lastReceivedMillis`, `Set<String> games`; implements `ClientSocket` by delegating with `synchronized send/close`.
- Create: `gateway/LiveSocket.java` — `static void register(Javalin app, JedisPool pool, GatewayChannel channel, ConnectionRegistry registry, UnreadCount unread, GameSubscriptions games, LongSupplier now)`; route `/api/v1/ws`; `static List<String> parseGameIds(JsonElement)` (format check, first 100); sweeper `ScheduledExecutorService` every 10 s closing sessions idle ≥ 60 s with 1000 `idle`.
- Modify: `gateway/GatewayMain.java` (delete `/ws`, `/api/games`, `/api/games/{gameId}`, `/api/upcoming`; register `LiveSocket`, `ReferenceRoutes`, `GameRoutes`), `gateway/GatewayChannel.java` (register/unregister `SocketSession`s; payload passed through unchanged)
- Delete: `gateway/EspnProxyHandler.java`, `gateway/GameListingHandler.java` and their tests; `EspnClient`'s doc comment loses "the gateway's upcoming-games proxy"; network-policy comment loses `/api/upcoming`.
- Test: `gateway/LiveSocketTest.java` (Javalin on a random port + `java.net.http.WebSocket` client, Redis DB 1, fake Notification service)

**Interfaces:**
- Consumes: `UnreadCount.get` (Task 7); `GameSubscriptions` (Task 9) — in this task `LiveSocket` calls `games.set(session, ids)` and `games.removeAll(session)`; Task 8 ships a stub `GameSubscriptions` that records ids only, Task 9 makes it live.
- `hello`: `{"type":"hello","userId":…,"serverTime":ISO-8601,"unreadCount":n}`.

- [ ] **Step 1: Failing tests.** `noSessionCloses4401`; `helloHasUnreadCount`; `pingPong`; `unknownTypeAndBadJsonIgnored` (socket stays open, next ping answered); `parseGameIdsKeepsFirstHundredValid` (101 valid + 3 invalid → first 100 valid); `idleClosesAfterSixtySeconds` (injectable clock: 59 s open, 60 s closed with 1000); `notificationReachesSocketInApiShape` (Delivery → socket gets `type` notification then `unread_count`); `concurrentSendsDoNotThrow` (two threads × 200 sends on one session, all 400 arrive); `oldRoutesGone` (`/ws`, `/api/games`, `/api/upcoming` → 404).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement**, delete the old handlers.
- [ ] **Step 4: Run** → PASS; `make test` → PASS.
- [ ] **Step 5: Rebuild the laptop cluster** `make down && make cluster up` and check by hand with `scripts/ws-listen.py ws://localhost:8080/api/v1/ws <jar> hello 10` → `received`.
- [ ] **Step 6: Commit** `"/api/v1/ws: hello, 4401, ping, idle close, API-shaped notifications; old /ws and /api/games gone"`.

### Task 9: Per-game subscriptions and current state

**Files:**
- Create/replace: `gateway/GameSubscriptions.java`
- Modify: `gateway/GatewayChannel.java` — routes messages by channel prefix (`gw:` → users, `live:` → `GameSubscriptions.onLive(gameId, message)`); `CompletableFuture<Void> subscribe(String channel)` completes in `onSubscribe` for that channel; `void unsubscribe(String channel)`; both under one lock (Jedis writes the command on the caller's thread); after a reconnect, subscribes `gw:<id>` plus every channel in `GameSubscriptions.channels()` in one command, then calls `GameSubscriptions.resendAll()`.
- Test: `gateway/GameSubscriptionsTest.java`

**Interfaces:**
- `GameSubscriptions(JedisPool pool, GatewayChannel channel, GameSummaries summaries, long confirmTimeoutMillis)`; `void set(SocketSession s, List<String> ids)` (replace list; for ids new to the gateway: `subscribe(...).get(timeout)`, on timeout log and continue; then read `game:<id>` for every id new to *this socket*, pipelined, and send `game_update` for those with a record); `void removeAll(SocketSession s)`; `void onLive(String gameId, String message)` (parse `{version, play}` → `summaries.fromLive` → send to that game's sessions; malformed → log, drop); `Set<String> channels()`; `void resendAll()`; package-private hook `Runnable afterConfirmBeforeRead` for the race test.
- Ref counts: `ConcurrentHashMap<String, Set<SocketSession>>`, changed under one lock with subscribe/unsubscribe decisions; `UNSUBSCRIBE` only when a set empties.

- [ ] **Step 1: Failing tests.** `twoSocketsOneSubscribe` (Redis `PUBSUB NUMSUB live:<g>` = 1 for one gateway); `lastLeaveUnsubscribes` (0 after both leave; after one leaves still 1); `currentStateSentAfterConfirmedSubscribe` (spy channel records order: `onSubscribe(live:g)` before the `HGETALL game:g`); `playBetweenSubscribeAndReadArrives` (hook publishes a `live:g` play with version v+1 between confirm and read; socket receives v+1 — duplicates allowed); `alreadyListeningReadsAtOnce` (second socket on same game: no new `SUBSCRIBE`, current state sent); `liveReachesOnlySubscribers`; `badLiveMessageIgnored`; `closeDuringSubscribeLeavesNoSubscription` (session removed while its `set` waits on confirm → afterwards NUMSUB 0, no exception); `resubscribeAfterReconnectSendsCurrentState` (`CLIENT KILL TYPE pubsub` → after reconnect NUMSUB 1 again and the socket gets a fresh `game_update`); `endToEndAcceptedPlayReachesSocket` (EventProcessingServiceImpl accept → socket gets `game_update` whose `version` matches).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement**; the subscribe-then-read order gets the spec's *why* comment.
- [ ] **Step 4: Run** → PASS; `make test` → PASS.
- [ ] **Step 5: Commit** `"Live game updates: per-game SUBSCRIBE, confirmed before current state"`.

### Task 10: Smoke test, listener, persistence check

**Files:**
- Modify: `scripts/ws-listen.py` (path `/api/v1/ws`; optional 5th arg: a JSON message to send after `hello`, e.g. `subscribe_games`; optional `--count N` to wait for N matching frames; print each matching frame), `scripts/smoke-test.sh`, `scripts/persistence-check.sh` (listener path)

- [ ] **Step 1: Extend the smoke test** (keeping all existing checks): `hello` with `unreadCount`; no cookie → close 4401; `GET /api/v1/games?date=$(date -u +%F)&tz=UTC` lists a simulator game id; `GET /api/v1/games/<id>` has `lineScore`; `GET /api/v1/leagues/NFL/alerts` 200 with `TOUCHDOWN`; subscribe to that game → first `game_update` within 5 s, a later one with a higher `version`; Carol's socket gets `"type":"notification"` with `TOUCHDOWN` then `unread_count`; `POST /notifications/seen` → `unread_count` 0 on her socket.
- [ ] **Step 2: Fresh cluster** `make down && make cluster up smoke` → `SMOKE PASS`; `scripts/persistence-check.sh` → `PERSISTENCE OK`.
- [ ] **Step 3: Commit** `"Smoke test on /api/v1/ws: game_update, unread_count, /games, catalog"`.

### Task 11: Docs and quiz

**Files:** `docs/api/openapi.yaml`, `docs/api/websocket.md`, `docs/api/README.md`, `CLAUDE.md`, `AGENTS.md`, `docs/study/phase-3-quiz.md`

- [ ] **Step 1:** Per spec section 11: `/games` range and 422 text; `periodLabel` examples gain `Final`, `Final/OT`, `End Q1`, and "null for scheduled games"; `websocket.md` — subscribe sends current state at once (after the server is listening), idle close code 1000, backend notes name the accept script as publisher and the unread counter; README "Served by the backend since 3.4: everything"; CLAUDE.md/AGENTS.md: Gateway row (`/api/v1/ws`, reference endpoints), Game State (`/games`), poller schedule loop, new keys, diagram line for `live:<gameId>`, test count, "New in Phase 3.4" bullet, next step 3b.
- [ ] **Step 2:** `npx @redocly/cli lint docs/api/openapi.yaml` → only the three expected warnings.
- [ ] **Step 3:** 3.4 section in `docs/study/phase-3-quiz.md` (8–10 questions: why the script publishes, subscribe-before-read, the unread race, why 10 s is safe, schedule vs score authority).
- [ ] **Step 4: Commit** `"Docs: Phase 3.4 live API"`.
