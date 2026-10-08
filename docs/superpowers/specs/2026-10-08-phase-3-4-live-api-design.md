# Phase 3.4: Live API — design

Status: draft for review, 2026-10-08. Parent document: `DESIGN.md` (decisions 5, 8, 13, 15). If this spec and `DESIGN.md` disagree, `DESIGN.md` wins and this spec gets fixed. `DESIGN.md` is updated with this spec where it refines a decision: the no-score heal's window (decision 5, 30 s → 10 s), and live game updates as a second path beside decision 13's routing.

Phase 3 is four sub-phases: 3.1 Kafka pipeline (merged, PR #6), 3.2 Postgres (merged, PR #13), 3.3 alerts wave 1 (merged, PR #14), **3.4 live API (this spec)**.

## Goal

Serve the rest of `docs/api/` so the 3b UI can be built against the real backend instead of the mock: the reference endpoints (`/leagues`, `/leagues/{id}/teams`, `/leagues/{id}/alerts`), the game endpoints (`/games`, `/games/{id}`), and the WebSocket at `/api/v1/ws` with `hello`, `notification`, `unread_count`, `game_update`, `subscribe_games`, and `ping`/`pong`. Two carry-overs from 3.3: ESPN's 0:00 clock glitch freezing the score card, and a `Final` period label.

Not in 3.4: the UI (3b), Web Push (Phase 7), the wave 2 scheduler. The deferred 3.3 minors are GitHub issues #15–#20; #15 is due before Phase 4 and #16 before Phase 5, not here.

### Done when

1. `make test` passes, including every test in section 9.
2. `make cluster up smoke` passes on the laptop and in CI, with the smoke test's new socket and game checks (section 9.4).
3. `scripts/persistence-check.sh` passes on the laptop.
4. `make replay-detectors` passes; golden timelines updated only for the label change in section 1, with the diff shown in the PR.
5. `npx @redocly/cli lint docs/api/openapi.yaml` gives only the three expected warnings.
6. Docs updated (section 11), and `docs/study/phase-3-quiz.md` has a 3.4 section. The user is quizzed only on request.
7. The PR from `phase-3-4-live-api` is merged.

### Branch

`phase-3-4-live-api`, worktree `.worktrees/phase-3-4-live-api`, from main at `23c3ff5`. Rebuild the laptop cluster once, at the socket switch-over (task 8): the old `/ws` goes away and the accept script changes.

---

## 1. Period labels: `Final` and `End`

**Problem.** `GameLabels.periodLabel` returns null unless the game is live, so a finished game's card has nothing to show beside the score, and between periods it says `Q1` with no clock, which reads like the period is still on.

**Fix.** One function, used by `/games`, `game_update`, and the `game` block of a notification:

| Game state | `periodLabel` | `clock` |
|---|---|---|
| Scheduled, postponed, canceled | null | null |
| Live, in play | `Q4`, `OT`, `2OT`, `3rd`, `SO`, `Top 7th` (unchanged) | `4:12` (unchanged) |
| Halftime | `Half` (unchanged) | null |
| Between periods (stage `END_PERIOD`) | `End Q1`, `End 2nd`, `End OT`; MLB keeps `Mid 7th` / `End 7th` | null |
| Final, regulation | `Final` | null |
| Final, past regulation | `Final/OT`, `Final/2OT`, `Final/SO`; MLB `Final/10` | null |

The suffix is the live label of the last period: NFL and NBA period 5 is `OT`, 6 is `2OT`; NHL period 4 is `OT`, a shootout (`SportRules.isShootout`) is `SO`, playoff period 5 is `2OT`; MLB past the 9th is the inning number.

**Alert texts do not change for finals.** `GameLabels.position` (the ` · Q4 4:12` part of an alert's text) stays null for finals, so a final's text keeps reading `Final (OT): Bruins 4, Jets 3`. Between periods, a text that carries a position now reads ` · End Q1` instead of ` · Q1`. The golden timelines are re-run; any line that changes is that wording only, and the diff goes in the PR.

## 2. The 0:00 freeze: no-score heal 30 s → 10 s

**Problem.** Seen in the recordings, Bruins at Jets (`nhl-401891831`), 2nd period: ESPN went 3:55 → **0:00 for 90 seconds** → 1:20 → 0:30 → 0:11. `seq` counts 0:00 as later in the period, so the script accepted it and every real snapshot after it looked older and was rejected. The no-score heal (3.1 spec, section 1.4) lowers the bar only after the same period and score have kept arriving "older" for 30 seconds. So the score card would show 0:00 for 30 seconds after ESPN fixed itself. Alerts are not affected: close game late and a failed try already need two late plays in a row (3.3).

**Fix.** `NO_SCORE_HEAL_MILLIS` becomes 10,000. Nothing else in the script's heal changes.

**Why 10 s is safe.** 30 s was chosen for two pollers overlapping during a rolling deploy. In an overlap, the newer poller's plays keep being accepted, and any accepted play clears `staleSince`, so the timer only runs out when nothing newer arrives at all. Two polls (5 s each) of nothing but older plays at the same score is enough. Scores can't change on this path, so a wrong heal can't fire an alert; the next fresh poll moves the clock forward again.

**Cost.** The score card shows ESPN's glitch for as long as ESPN shows it, plus up to 10 s. Waiting one poll to confirm a 0:00 was rejected: ESPN repeated the bad 0:00 three times in a row.

## 3. Plays carry the start time and line score

**Problem.** `game:<id>` has the score and position but no start time, and nothing has the line score `GameDetail` returns.

**Fix.**
- `GameSnapshot` already has `startMillis` from ESPN's `date`. `GameEvent` carries it as `startTime` (epoch millis), and the accept script saves it in `game:<id>`.
- `EspnScoreFeed` reads each competitor's `linescores` into `GameSnapshot.lineScore`: a list of `{period, home, away}`. `GameEvent` carries it, and the script saves it as one JSON field, `lineScore`, in the same step as the score, so the two always add up. The simulator's `ScoreFeed` builds it from its script.
- The accept script also adds the game to `games:start` (a sorted set, score = start millis) when `startTime > 0`, so the game lists find games the schedule doesn't know, such as the simulator's. It trims `games:start` to start times newer than 3 days ago in the same step, the way it already trims `games:recent`, so the set stays bounded on the laptop too, where the ESPN poller is off and every simulator run adds new ids.

The poller does not send a play for a line score change alone: the line score only changes with the score, and a correction that moves points between periods without changing the total is rare enough to wait for the next play.

## 4. The schedule: yesterday through 7 days ahead

**Problem.** `/games?date=` needs games that haven't started (to follow Sunday's game on Wednesday) and finished games whose `game:<id>` has expired (24 h after the final). The poller only sees ESPN's default scoreboard, and only the score authority (the accept script) writes `game:<id>`.

**Fix.** A schedule loop in the ESPN poller process, beside the live loop. It runs only with the ESPN feed (`espnPoller.enabled`), never with the simulator's `ScoreFeed`:

- Every 15 minutes, for each league and each UTC date from yesterday − 1 to today + 8 (11 dates, wide enough for any timezone's "yesterday to +7"), it fetches `scoreboard?dates=YYYYMMDD` through `EspnClient` (`scoreboard(league, date)`, same host and User-Agent). That is 44 requests per 15 minutes.
- For each game it writes `sched:<gameId>`, a hash: league, home and away abbreviations, start time, ESPN's status mapped as the poller maps it, scores, period, venue, broadcast, `fetchedAt`. TTL 10 days. And `ZADD games:start <startMillis> <gameId>`.
- A failed league or date is logged and skipped; the previous entries stay until their TTL.

**`sched:` is a listing, not the score.** It never touches `game:<id>`, sends no play, and fires no alert. When both exist, `game:<id>` wins for everything it has (score, status, position, version); `sched:` fills in venue and broadcast, and stands in alone only for games the poller has no record of (future games, and finals past their record's TTL). A schedule-only game has `version: 0`, so the first real update replaces it in the UI.

Key names go in `RedisKeys` (`SCHED_PREFIX`, `GAMES_START`). The poller's Deployment and network policy already reach Redis and ESPN.

## 5. Game endpoints

### 5.1 Where they run

The Game State service (8082, "game state reads only") serves `GET /games` and `GET /games/{id}`. The gateway forwards to it, the way it forwards `/follows` to User Preference. The old `/api/games`, `/api/games/{id}`, `/api/upcoming`, `GameListingHandler`, and `EspnProxyHandler` are deleted; the course UI has been broken since 3.2 and 3b replaces it.

### 5.2 One builder

A new package `games/` holds `GameSummaries`, which turns a game into the API's `GameSummary` JSON. It is used by `/games`, `/games/{id}`, and the gateway's `game_update` (section 7), so a card and a live update can't disagree. Inputs: the `game:<id>` fields (or a play plus its version, for live updates), the `sched:<id>` fields, and `Teams`. Mapping:

- `status`: SCHEDULED → `scheduled`; IN_PROGRESS, END_PERIOD, HALFTIME → `in_progress`; FINAL → `final`; DELAYED → `delayed`; POSTPONED → `postponed`; CANCELED → `canceled`; SUSPENDED → `suspended`.
- `home` / `away`: `TeamRef` from `teams.json` by `TeamIds.of(league, abbreviation)`, with `logoUrl` and `primaryColor` (section 6); a team not in the file keeps the id from `TeamIds.of` and uses its abbreviation as display name.
- `period`, `periodLabel`, `clock`: from the position through `GameLabels` (section 1). Null for scheduled games.
- `version`: the game's counter; 0 for schedule-only games. `updatedAt`: the play's timestamp, or `fetchedAt`.
- `GameDetail` adds `lineScore` (labels `Q1`…`Q4`, `OT`, `2OT` for NFL/NBA; `1`, `2`, `3`, `OT`, `SO` for NHL; `1`…`9`, `10` for MLB), `venue`, `broadcast`.

### 5.3 `GET /games`

- `date` (default today in `tz`), `tz` (default: the user's timezone when signed in, else UTC). The gateway fills in the user's timezone from the session's account when `tz` is missing.
- Supported dates: yesterday through 7 days ahead, in `tz`. Anything else → 422 `date out of range`, with the range in the message. A bad `tz` or `date` → 422.
- The day is `[local midnight, next local midnight)` turned into UTC millis; Game State reads `ZRANGEBYSCORE games:start` for that range, then pipelines `HGETALL game:<id>` and `HGETALL sched:<id>` for each id. Ids with neither are skipped.
- Sorted by start time, then id. Filters `league`, `team`, `status` are applied in Game State.
- `following=true` needs a session (401 without one). The gateway fetches the user's follows from User Preference and keeps the games whose id, league, or either team id is followed. Filtering in the gateway keeps Game State free of user data.

### 5.4 `GET /games/{id}`

`GameDetail` from `game:<id>` and `sched:<id>`; 404 when neither exists.

## 6. Reference endpoints

- `GET /leagues`: the four leagues from a small `catalog/leagues.json` (id, name, sport, `periodName`, `regulationPeriods`, `logoUrl`).
- `GET /leagues/{id}/teams`: from `teams.json`, alphabetical by display name. `scripts/fetch-teams.py` is re-run to add `location`, `name`, `logoUrl`, and `colors` (ESPN's `color` and `alternateColor`, with `#`). Ids must come out identical to today's 124; the script already checks.
- `GET /leagues/{id}/alerts`: `AlertCatalog.forLeague` as the API's `AlertType` list, in catalog order. Unknown league → 404 for both.

The gateway serves all three itself from the classpath; there is no Redis or service hop. Responses carry `Cache-Control: public, max-age=3600`. No session needed.

## 7. Live game updates

### 7.1 Who publishes

**Problem.** Something must send each accepted play to the gateways showing that game. If Event Processing published after the script returned, two plays of the same game accepted back to back by two pods could publish in the wrong order. If Stage 1 published, live scores would stop whenever Stage 1 pauses (`sub:built` missing) or lags.

**Fix.** The accept script publishes, as the last step of an accept:

```
PUBLISH live:<gameId> {"version": <version>, "play": <the play JSON>}
```

Redis runs scripts one at a time, so publishes for a game go out in version order, and only for state that was saved. A play the script answers `pending` or `stale` publishes nothing. While Kafka is down, games go `pending` and live scores freeze, as `DESIGN.md` already says. Pub/sub is fire-and-forget: a gateway that misses a message catches up on the next play or the next subscribe (7.3).

### 7.2 Gateways subscribe per game

Each gateway keeps, per game id, the set of its sockets that asked for it. The first socket that asks makes the gateway `SUBSCRIBE live:<gameId>`; when the last one leaves (a new `subscribe_games` without it, or the socket closes), it `UNSUBSCRIBE`s. The subscriptions share `GatewayChannel`'s dedicated connection with `gw:<gatewayId>`; one `JedisPubSub` routes by channel prefix.

Jedis writes subscribe commands on the caller's thread with no lock, so every `SUBSCRIBE` / `UNSUBSCRIBE` goes through one lock in the channel class. After a reconnect (Redis restarted), the channel re-subscribes `gw:<id>` and every game in the map in one command, then sends each subscribed socket the current state (7.3).

**Why per game and not `PSUBSCRIBE live:*`.** Load is the same today (a few dozen live games), but a gateway that hears only the games its tabs show keeps working when the game count grows (more leagues, college sports), and it is what `docs/api/websocket.md` already promises. A Super Bowl with 100,000 viewers is one message per gateway either way.

### 7.3 Current state on subscribe

**Problem.** The UI fetches `/games` over HTTP, then sends `subscribe_games`. A play accepted between the two is missed until the game's next play, about 5 seconds later.

**Fix.** For every id a socket adds, the gateway sends a `game_update` with the current state right away, built by `GameSummaries`. Order matters:

1. If the gateway is not yet listening to `live:<gameId>`, it sends `SUBSCRIBE` and **waits for Redis to confirm** (`JedisPubSub.onSubscribe` for that channel; up to 2 s, then it reads anyway and logs).
2. Only then it reads `game:<id>` (pipelined across the added ids) and sends the state.

Read first and a play accepted between the read and the subscription is lost until the game's next play. Subscribe first and that play arrives twice at worst (once live, once in the read), which is harmless: the UI drops any update whose version is not higher than what it has. Ids with no record (scheduled games, unknown ids) get no current state. If the gateway was already listening (another tab), it reads at once. This is an addition to `websocket.md` (section 11).

### 7.4 The message

The gateway turns `{version, play}` into `{"type": "game_update", "game": <GameSummary>}` and sends it to each socket subscribed to that game. `startTime` comes from the play (section 3); venue and broadcast are not in `GameSummary`.

## 8. The socket: `/api/v1/ws`

Replaces `/ws` (deleted). Follows `docs/api/websocket.md`:

- **Connect.** No session → close with **4401**. Otherwise register (routing as today: `conn:<userId>`, `gw:<gatewayId>`) and send `hello` with `userId`, `serverTime`, `unreadCount` (8.2).
- **Messages in.** `ping` → `pong`. `subscribe_games` replaces the socket's list: ids must match the game id format (`<league>-<id>`); at most 100 are kept (the first 100, the rest ignored and logged); anything else is ignored. Unknown `type` and unreadable JSON are ignored.
- **Idle.** A sweeper every 10 s closes sockets that have sent nothing for 60 s (code 1000, "idle"). Server sends don't count; that's why it isn't Jetty's idle timeout, which resets on writes too.
- **Sends.** Pub/sub threads, the request thread (`pong`, current state), and the sweeper can all write to one socket, and Jetty does not allow two blocking sends at once on a session. `ClientSocket.send` takes a per-socket lock.
- **Shutdown.** Unchanged: remove `conn:` entries, close with 4000.

### 8.1 `notification`

Stage 2's delivery script publishes the API shape: `{"type": "notification", "notification": <body>}`, where the body is the `NotificationBody` Stage 1 built (what history stores) plus `createdAt`, the Kafka timestamp of the notifications record (what history saves as `created_at`, 3.2). The 3.1 shape is gone.

### 8.2 `unread_count`

**Problem.** The badge must update on every notification, and on every tab when one tab marks all seen. Counting in Postgres on every push would mean one count query per user per moment, and the count could miss the push itself, because history writes it in batches.

**Fix.** A Redis counter, `unread:<userId>`, as a cache of Postgres's count:

- **On delivery.** Stage 2's delivery script, after it publishes the notification and before it claims the dedup key, runs `INCR unread:<userId>` **only if the key exists**, and if it does, publishes `{"type": "unread_count", "count": N}` to the same gateways. Same script, so the claim and the increment happen once together.
- **On seen and clear.** The Notification service, after its Postgres commit, runs one script: `SET unread:<userId> 0 EX 86400`, then publish `unread_count` 0 to every gateway in `conn:<userId>`. Every tab on every gateway clears.
- **On connect.** The gateway reads `unread:<userId>`. If it is missing, it asks the Notification service (`/users/{id}/notifications/unread-count`, Postgres) and `SET … NX EX 86400` the answer.
- **Rebuildable.** A missing counter is never incremented from nothing (that would start at 1 instead of the real count); it waits for the next connect to be filled from Postgres. The 1-day TTL is set only when the counter is created, so any drift heals within a day.

The Notification service gets Redis access (pool, env vars, and the network policy to Redis).

**Known gap.** Two narrow races leave the count off by one until the next seen, clear, or the daily expiry: a push delivered between the gateway's Postgres read and its `SET NX` isn't counted, and a push sent before a "seen" but delivered after it is counted though Postgres calls it seen.

## 9. Tests

### 9.1 Unit

- Labels: each row of section 1's table per league, including `Final/SO`, MLB `Final/10`, NHL playoff `Final/2OT`, `End Q1`, `End 2nd`; a final's alert text has no position.
- `GameSummaries`: every status mapping; a game with only `sched:`; a game with both (record wins on score, schedule gives venue); unknown team; line score labels per league.
- Date range: `tz` America/Los_Angeles at 23:30 UTC (still "yesterday" there); 8 days ahead → 422; bad `tz` → 422.
- `EspnScoreFeed`: line score and start time from a real fixture; missing `linescores` → empty list.
- Socket message handling: `subscribe_games` with 101 ids keeps 100; bad ids dropped; unknown type ignored.

### 9.2 With Redis

- Accept script: an accepted play publishes `{version, play}` on `live:<id>`; `pending` and `stale` publish nothing; ten accepts on one game publish versions in order; `startTime` and `lineScore` saved; `games:start` gets the game and loses starts older than 3 days.
- No-score heal: at 9 s still `stale`, at 10 s accepted (replaces the 29/30 s test). Replay the Bruins 0:00 sequence: accepted 0:00, then older plays heal 10 s later.
- Schedule writer: a recorded `dates=` response writes `sched:` and `games:start`; a game with a `game:` record keeps its record untouched.
- `/games`: day boundaries in two timezones; filters; `following=true` without a session → 401.
- Delivery script: with `unread:` present → `INCR` and an `unread_count` publish after the notification; absent → no key created, no `unread_count`; a redelivered message → neither.
- Seen/clear: counter 0 and an `unread_count` 0 publish to every gateway in `conn:`.

### 9.3 Gateway, in process

A test gateway with a real Redis and fake services:
- No session → close 4401. Session → `hello` with the count from Redis, or from the Notification service when the key is missing (then the key exists).
- Two sockets subscribe to the same game: one `SUBSCRIBE`; one leaves: still subscribed; both leave: `UNSUBSCRIBE`.
- `subscribe_games` sends current state at once for ids with a record, and only after the gateway's `SUBSCRIBE` is confirmed.
- A play published between the confirmed subscription and the read of `game:<id>` still reaches the socket (a test hook runs between the two steps).
- A `live:` publish reaches only sockets subscribed to that game, as a valid `GameSummary`.
- Redis connection killed: the gateway resubscribes games and sends current state.
- `ping` → `pong`; 60 s with nothing from the client → closed (injectable clock).
- Two threads sending to one socket at once: no exception, both messages arrive.

### 9.4 Smoke test and persistence check

`scripts/smoke-test.sh` and `scripts/ws-listen.py` move to `/api/v1/ws`:
- `hello` arrives with `unreadCount`.
- Subscribe to a simulator game: a `game_update` arrives at once, and later ones have higher versions.
- The touchdown arrives as `{"type": "notification"}` in the API shape, followed by `unread_count`; `POST /notifications/seen` → `unread_count` 0.
- `GET /games?date=<today>` lists the simulator's games; `GET /games/{id}` has a line score; `GET /leagues/NFL/alerts` returns the catalog.
- No session → 4401.

`scripts/persistence-check.sh` keeps its checks and its socket listener moves to the new path.

## 10. Order of work

1. Labels (section 1), replay goldens re-run.
2. No-score heal 10 s (section 2).
3. Start time and line score on plays; script saves them, adds to `games:start`, publishes `live:` (sections 3, 7.1).
4. Schedule loop (section 4).
5. `games/GameSummaries`, Game State `/games` and `/games/{id}`, gateway forwarding and `following` (section 5).
6. `fetch-teams.py`, `leagues.json`, the three reference endpoints (section 6).
7. Unread counter: delivery script, Notification service reset, network policy (section 8.2).
8. `/api/v1/ws`: hello, 4401, ping, idle, send lock, notification shape; delete `/ws` and the old endpoints (section 8). Rebuild the laptop cluster here.
9. Per-game subscriptions and current state on subscribe (sections 7.2–7.4).
10. Smoke test, `ws-listen.py`, persistence check on a fresh cluster (section 9.4).
11. Docs and the quiz section (section 11).

## 11. Docs to update

- `docs/api/openapi.yaml`: `/games` date range (yesterday through 7 days ahead in `tz`, else 422); `periodLabel` examples gain `Final`, `Final/OT`, `End Q1`; `clock` null for finals.
- `docs/api/websocket.md`: `subscribe_games` sends current state right away; idle close code; the backend notes name the accept script as the publisher and the unread counter.
- `docs/api/README.md`: "Served by the backend since 3.4: everything."
- `DESIGN.md`: decision 5 and the "runs ahead of reality" lines say 10 seconds, and drop "the poller sends nothing when only the clock differs" (untrue since 3.3); decision 13 gains a paragraph on live game updates; build order marks 3.4.
- `CLAUDE.md` / `AGENTS.md`: the Gateway row (`/api/v1/ws`), Game State (`/games`), the poller's schedule loop, new keys.

## Known gaps accepted in 3.4

- The score card shows an ESPN clock glitch for as long as ESPN does, plus up to 10 s (section 2).
- A future game's start time can be up to 15 minutes behind an ESPN change.
- The unread count can be off by one until the next seen, clear, or daily expiry (section 8.2).
- Live scores freeze while Kafka is down (unchanged from 3.1).
- An early "final" from ESPN is still the bar for the rest of the game (CLAUDE.md open problem 1, unchanged).
