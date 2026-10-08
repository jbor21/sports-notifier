# Phase 3.3: Alerts, wave 1 — design

Status: draft for review, 2026-10-07. Parent document: `DESIGN.md` (decision 15; decisions 3, 4, 5, 8). If this spec and `DESIGN.md` disagree, `DESIGN.md` wins and this spec gets fixed. `DESIGN.md` was updated with this spec where it refines decision 15: where the detectors run and keep their memory, the poller sending every position change, score alert ids built from a counter that never goes down, the push id, and the `:ANY` sets.

Phase 3 is four sub-phases: 3.1 Kafka pipeline (merged, PR #6), 3.2 Postgres (merged, PR #13), **3.3 alerts wave 1 (this spec)**, 3.4 live API.

## Goal

Replace the interim mapping in Stage 1 (three course play types mapped onto catalog switches) with decision 15's wave 1 alerts, detected from the scoreboard: game start, final, scores (with the NFL try follow-up), end of period and halftime, end of inning, lead change, close game late, overtime, shootout, delayed or postponed. Make the most specific follow win, combine every alert of one moment into one push per user (the final always its own push), and give every alert a deterministic id.

Not in 3.3: the scheduler (starting soon, daily league summary) and the other wave 2 and 3 alerts. Their switches stay saved and show as `coming_soon` (section 3.10).

### Done when

1. `make test` passes, including every test in section 7.
2. `make cluster up smoke` passes on the laptop and in CI, with the smoke test's alert checks (section 7.4).
3. `scripts/persistence-check.sh` passes on the laptop, including its same-user replay with the new simulator.
4. `make replay-detectors` prints an alert timeline for every recorded game, and the golden fixtures match (section 7.3).
5. Docs updated (section 9), and `docs/study/phase-3-quiz.md` has a 3.3 section. The user is quizzed only on request.
6. The PR from `phase-3-3-alerts` is merged.

### Branch

`phase-3-3-alerts`, worktree `.worktrees/phase-3-3-alerts`, from main at `98ba398`. Rebuild the laptop cluster at the Stage 1 switch-over (task 10): `det:` memory replaces `s1:last` and message ids change.

---

## 1. Plays carry the whole game position

**Problem.** A play carries the score and the status. Period and clock exist only inside `seq`, and the poller sends nothing while only the clock moves. Close game late (clock crosses 5:00), a failed NFL try (clock runs again after a touchdown), and a period end whose short END_PERIOD status fell between two polls would all wait for the next score.

### 1.1 New fields

`GameSnapshot` gains `seasonType` (ESPN `event.season.type`: 1 preseason, 2 regular season, 3 postseason; 2 if missing) and `detail` (ESPN `status.type.shortDetail`, e.g. `Bot 4th`, `End of OT`, `Final/SO`; null if missing). `EspnScoreFeed` reads both.

`GameEvent` gains `period`, `clockTenths`, `half`, `outs`, `stage`, `seasonType`, and `detail`, copied from the snapshot by `PollDecision`. The detectors read these fields, never `seq`.

### 1.2 The poller sends every position change

`PollDecision` keeps its checks in order (first sight, final, start, score, status change). One rule is added at the end: if the snapshot's `seq` differs from the record's `lastSeq`, send a `GAME_SYNC`. While a clock runs, that is one play per game per 5-second poll; about 10 plays a second at a busy peak. A game seen for the first time is still recorded quietly.

The event type stays for the poller's logs. Stage 1 no longer reads it.

### 1.3 The script saves the position

The accept script writes the new fields into `game:<id>` with the score, in the same atomic step. 3.4's `/games` reads them from there.

### 1.4 The simulator becomes a score feed

`SERVICE=simulator` runs the real `Poller` over a `SimulatedScoreFeed` instead of posting events itself. It now needs Redis (to read `game:<id>`, as the ESPN poller does): the chart gives it `REDIS_HOST` and the Redis network policy allows it.

- **Scripted games.** Each game slot follows a fixed script per sport: the clock runs, periods end, and scores land the way the sport scores. The NFL script has a touchdown with the extra point one snapshot later, a field goal, a touchdown with a 2-point try in one snapshot, a lead change, and a close finish. The MLB script has runs in several innings, and the NHL script has a 1-goal 3rd period. Scripts are deterministic, so tests and the smoke test can expect exact alerts. Only the start jitter is random.
- **Unique game ids per run.** `<league>-sim<run start, epoch seconds>-<slot>`, e.g. `mlb-sim1760000000-5`. Alert ids no longer carry the clock-seeded version (section 2.4), so a reused id would find last run's `det:` memory (start and final already fired), Stage 2's dedup keys (2 days), and history's primary key, and the second run would alert nobody.
- **ESPN abbreviations.** Teams come from `teams.json` (`GS`, `NY`, not `GSW`, `NYK`), so every simulated team can be followed (PR #13 minor 9).
- **Pending.** The poller re-sends on its next poll when Event Processing answers `pending`, so no play is dropped (issue #7). A run ends when every game's record says FINAL.
- `SIM_WAVES` and `SIM_INTERVAL_MS` keep their meaning. A wave takes about 90 seconds.

---

## 2. Detectors

### 2.1 Shape

A detector is a pure function: `(GameMemory before, Play now) → (alerts, GameMemory after)`. One class per alert family, each tested without Redis or Kafka. A `Detectors` chain runs them in a fixed order over one memory and collects their alerts. `Play` is a read-only view of an accepted `GameEvent`.

### 2.2 Memory: `det:<gameId>`

One Redis hash per game, `det:<gameId>` (`RedisKeys.DETECTOR_PREFIX`), with two fields: `version` (the last play handled) and `memory` (JSON). It replaces `s1:last:<gameId>` and keeps its 8-day TTL (longer than `game-events` retention, so a re-read from the start of the topic still finds it).

The memory holds:

- **Last position:** phase, status, stage, period, half, clock, both scores, and whether the previous play's clock met the late-game condition (section 3.7).
- **Once-per-game flags:** game start, halftime, each period end, each end of inning, close game late, each overtime period, shootout, final.
- **Counters that only go up:** scoring alerts fired per team, lead changes fired, delays fired, resumes fired. A score reversal never lowers them.
- **Last leader:** the last team that was strictly ahead.
- **NFL try state:** pending or not; the scoring team; that touchdown's scoring counter value; the period and clock at the touchdown; the number of plays since the clock dropped below it; and, per team, whether its last try was called no good.

### 2.3 Order per play and what failures do

For each play, on Stage 1's single thread:

1. Read `det:<gameId>`. If the play's version is at or below the stored version, skip it (a duplicate).
2. Run the detectors.
3. Send the messages (section 5) and wait for every confirmation.
4. Write the new memory with a set-if-higher script: write `version` and `memory` only if the new version is higher, then refresh the TTL.

| What happens | Result |
|---|---|
| Crash after step 3, before step 4 | The play is re-read against the same memory: same alerts, same ids, and Stage 2's dedup drops the copies |
| Crash after step 4, before the offset commit | Step 1 finds the version and skips the play |
| A stalled old worker finishes a play after the lane's new owner moved on | It computes the identical result; set-if-higher keeps it from writing older memory |
| Redis loses its last second (AOF) | The memory can be one play behind. The next play is compared with the older memory and detects the lost play's alerts again, with the same ids (the counters rolled back too), so dedup drops them. Not covered: the same second also losing those `notif:` keys |
| No memory for a game (Stage 1 never saw it, or it expired) | The play sets the baseline (section 2.5) and alerts nobody |

### 2.4 Alert ids

`<gameId>:<TYPE>[:<what makes it distinct>]`:

| Alert | Id |
|---|---|
| Score (any sport, any kind, and its overturn) | `…:SCORE:<home\|away>:<n>`, n = that team's scoring-alerts-fired counter after this alert |
| NFL try result | `…:TRY_RESULT:<home\|away>:<n>`, n = its touchdown's counter value; `…:late` appended for the late follow-up |
| Lead change | `…:LEAD_CHANGE:<n>` |
| End of period | `…:END_OF_PERIOD:<period>` |
| End of inning | `…:END_OF_INNING:<inning>` |
| Overtime | `…:OVERTIME:<period>` (MLB: `…:OVERTIME:10`, once) |
| Delayed, resumed | `…:DELAYED:<n>`, `…:RESUMED:<n>` |
| Once per game | `…:GAME_START`, `…:HALFTIME`, `…:CLOSE_LATE`, `…:SHOOTOUT`, `…:FINAL` |

**Why a counter of alerts fired, not the score.** A goal overturned (3 → 2) and then a real goal makes the score "home's 3rd goal" again. An id built from the score would repeat, and dedup would drop a real alert. The counter is 3 after the first goal and 4 after the real one.

**Why not the play's version.** The version is unique, but a re-detected alert after a Redis rollback (section 2.3) arrives on a newer play with a newer version. Built from the counters, it keeps its id and is dropped as the duplicate it is.

### 2.5 Baseline

When a game has no memory, the first play is recorded as the baseline. Every once-per-game condition already true is marked fired: the game started, periods already ended, close game late already holding, overtime periods already reached, final. A Stage 1 that loses a game's memory mid-game can miss one moment, never send stale ones.

### 2.6 Labels and text

`GameLabels` (shared with 3.4) builds the display parts:

- **periodLabel.** NFL and NBA: `Q1`–`Q4`, `Half` at halftime, `OT`, `2OT`, … NHL: `1st`, `2nd`, `3rd`, `OT`, `2OT`, … and `SO` for a shootout. MLB: `Top 4th`, `Mid 4th`, `Bot 4th`, `End 4th`.
- **clock.** `m:ss`, also under a minute (`0:42`, not ESPN's `42.3`; user, 2026-10-08). Null for MLB and between periods (END_PERIOD, HALFTIME).

Every in-game alert's text ends with the score and the position: `Touchdown Raiders! Extra point pending. Raiders 20, Bears 17 · Q4 4:12`. Team names are `teams.json` short names.

---

## 3. Wave 1 rules

Regulation is 4 quarters (NFL, NBA), 3 periods (NHL), 9 innings (MLB). "Live" means phase LIVE. Score alerts fire only on live plays or on the play that turns the game final; after the final, changes update the memory quietly.

### 3.1 Game start (`GAME_START`)

The phase goes from before to live. A game delayed before its start still gets its start when it goes live.

### 3.2 Final (`FINAL`)

The status becomes FINAL (not CANCELED). Text: `Final: Bears 20, Raiders 17. Bears win.` with `(OT)` when the game went past regulation, `(SO)` after a shootout, and `Tie.` for an NFL tie. It names the winner, not "your team": the push body is built once per moment, not per user. Always its own push (section 5.4).

### 3.3 Delayed, postponed, canceled, resumed (`DELAYED` switch)

- **Delayed:** the status enters DELAYED, POSTPONED, SUSPENDED, or CANCELED from any other status, including from one of the others (delayed, then postponed, is news). Id `…:DELAYED:<n>`.
- **Resumed:** MLB only. The status goes from DELAYED or SUSPENDED back to IN_PROGRESS on a live game. Id `…:RESUMED:<n>`.

### 3.4 Scores

Per team, on its score going up:

- **NFL**, by the team's rise:
  - **+6: touchdown** (`TOUCHDOWN`), "Extra point pending." The try is now pending for that team.
  - **+7 or +8: touchdown and try in one alert** (`TOUCHDOWN`): "Touchdown, extra point good" or "… 2-point conversion good."
  - **+3: field goal** (`FIELD_GOAL`).
  - **+2 with no try pending for that team: safety** (`SAFETY`).
  - **With a try pending for that team, +1 or +2: try result** (`TRY_RESULT`): extra point good, or 2-point conversion good. Not a new score alert, and its id uses the touchdown's counter value.
  - **Try no good:** the try is pending, the scoring team added nothing, and either the period changed or ended, or the clock has been below the touchdown's clock for two plays in a row. Two plays rather than one, because a single snapshot can carry a wrong clock (seen in the recordings), and because ESPN can post the extra point a few seconds after the clock restarts.
  - **The game goes final with the try pending:** nothing is sent. The try was not attempted (an overtime touchdown ends the game; the NFL skips a try that can't matter), so "no good" would be false (user, 2026-10-08; seen in nfl-401872966).
  - **Late extra point:** a +1 for a team whose last try was called no good, with nothing else scored by that team since. Sends `TRY_RESULT` "Extra point good after all", id `…:TRY_RESULT:<side>:<n>:late`. Any other +1 is the generic case.
  - **The other team scores while a try is pending:** the try is called no good in the same moment, and that score is handled by these rules (a +2 there is a defensive conversion, sent as the generic case).
  - **Anything else** (+4, +5, +9, …): a generic score alert, headline type `SCORE`, reaching anyone with `TOUCHDOWN`, `FIELD_GOAL`, or `SAFETY` on: "Bears score 9."
- **MLB:** one `RUN_SCORED` per team per play: "Cubs score 2!"
- **NHL:** one `GOAL` per team per play; "2 goals" if two landed between polls.
- **NBA:** no score alerts. Scores still update the memory.
- If both teams scored in one play, each gets its own alert.
- **A score going down** (user, 2026-10-08) is an overturn only once it holds: 60 s of game clock (MLB: two more plays), a new period, or the final. Then it alerts under the scoring switch it undoes, with the corrected score and the next scoring-counter id: NHL `GOAL_OVERTURNED` ("Rangers goal overturned."), MLB `RUN_OVERTURNED`, NFL `TOUCHDOWN_OVERTURNED` (a drop of 6–8), `FIELD_GOAL_OVERTURNED` (3), otherwise `SCORE_OVERTURNED` ("Bears score corrected.") under every NFL scoring switch. NBA: none. A drop that comes back first is an ESPN glitch and sends nothing; alerts count from the last believed score, so the goal is not announced again. A touchdown's pending try waits while its drop is unconfirmed and is dropped silently with the touchdown. Seen in the recordings: real overturns drop at the stopped clock of the review and stay down (nhl-401891803, -892432, -891782, -891822); glitches drop while the clock runs and are back within seconds, up to 27 s (nhl-401892443), so 60 s leaves room.

### 3.5 Lead change (`LEAD_CHANGE`)

The leader is the team strictly ahead, or nobody when tied. A lead change fires when a team takes the lead and the last leader was the other team, with or without a tie in between. Taking the first lead of the game is not a change. NBA: only in Q4 and overtime (the last leader is still tracked all game). Text: "Raiders take the lead."

### 3.6 End of period, halftime, end of inning

- **NFL, NBA:** `END_OF_PERIOD` for Q1 and Q3, `HALFTIME` for the end of Q2.
- **NHL:** `END_OF_PERIOD` for the 1st and 2nd.
- **Detected** from whichever comes first: the END_PERIOD or HALFTIME stage for that period, or the period number going up. One flag per period stops the second.
- **Last regulation period and overtime periods:** no end alert. Overtime or the final covers them (decision 15: overtime absorbs the end of regulation's last period).
- **MLB `END_OF_INNING`:** inning n is complete when the half is END for n, or the inning number goes past n. Innings 1–8 fire. The 9th never does: the game is either final or tied, and extra innings absorb it. From the 10th on, an inning that ends tied fires for "every inning" followers. Which followers hear it is section 4.2.

### 3.7 Close game late (`CLOSE_LATE`)

Once per game, never in overtime or extra innings.

| Sport | Score gap | Late |
|---|---|---|
| NBA | 5 or fewer | Q4, 5:00 or less |
| NFL | 8 or fewer | Q4, 5:00 or less |
| NHL | 1 or fewer | 3rd period, 5:00 or less |
| MLB | 2 or fewer | 8th or 9th inning, either half |

Fires on the first play where the gap holds and the late condition holds. For the clock sports, the late condition must also have held on the previous play. One wrong clock in one snapshot (seen in the recordings: 0:00 mid-period) must not start a close-game alert. The cost is one poll, about 5 seconds.

### 3.8 Overtime (`OVERTIME`)

The period goes past regulation. Each further period fires again ("Double overtime"). MLB fires once, when the 10th starts ("Extra innings"). An NHL period that is a shootout (section 3.9) does not fire overtime.

### 3.9 Shootout (`SHOOTOUT`)

NHL, season type not postseason, and either period 5 or `SO` in `detail`. Once. In the postseason, period 5 is double overtime. **Unconfirmed:** the recordings have 5+ overtimes and no shootout, so the rule is a best guess. An NHL `detail` the parser has not seen before is logged once, as unknown statuses are today.

### 3.10 Catalog availability

Alerts with no detector after 3.3 become `coming_soon` in `alerts.json`; the API already shows those switches disabled. They are STARTING_SOON, COMEBACK, BIG_RUN, BIG_LEAD, TIE_LATE, BIG_INNING, WALK_OFF, SHUTOUT_WATCH, and TWO_MINUTE_WARNING, joining the ones already `coming_soon`. Saved switches are untouched; wave 2 turns them back to `available`.

---

## 4. Follow sets

### 4.1 `:ANY` sets

`sub:<kind>:<target>:ANY` holds every user with a follow of that target, whatever its switches. "Most specific wins" subtracts these (section 5.1). Writing a follow's state adds the user. Deleting the follow removes them. The rebuild writes them like the others. `ANY` is reserved: no catalog type may use it (checked by `AlertCatalogTest`).

### 4.2 Innings sets

`END_OF_INNING` with no innings chosen (the API's "empty means every inning") puts the user in `…:END_OF_INNING`. With innings chosen, the user goes in `…:END_OF_INNING:<n>` for each chosen n (1–9) and out of the plain set. At fan-out, inning n's alert reaches both `END_OF_INNING` and `END_OF_INNING:<n>`. Options stay in the sets, so delivery looks nothing up.

### 4.3 Minors from PR #13 carried here

- **Innings are whole numbers (minor 8).** Saving a follow normalizes `innings` to integers 1–9 (Gson reads JSON numbers as `9.0`). Reading back returns `9`, and other values are a 422.
- **Delete the marker when a commit throws (minor 4).** If the Postgres commit throws, user-pref deletes `sub:built` before answering, with the existing in-memory retry. The server may have committed even so. Without the delete, Redis stays stale until the daily rebuild, and with `:ANY` a stale game follow also silences that user's team alerts.
- **Rebuild when Redis restarts (minor 5).** user-pref's 30-second check also reads Redis's `run_id` (`INFO server`). When it differs from the last one seen (not on the first read), it deletes `sub:built`, the sets are rebuilt, and Stage 1 waits meanwhile. This catches an AOF restore that kept `sub:built` but lost the last second of `SADD`/`SREM`.

---

## 5. Audience and combining

### 5.1 Most specific follow wins

An alert reaches the switches listed for it (usually one; the NFL generic score lists three; end of inning lists two). For a game g with teams H and A in league L, write X:s for the union of the alert's switch sets at X. Its audience is:

> game:s ∪ ((teamH:s ∪ teamA:s) − game:ANY) ∪ (league:s − game:ANY − teamH:ANY − teamA:ANY)

Built inside Redis, pipelined: SUNIONSTORE and SDIFFSTORE into temporary keys under `fanout:`. Names are unique per call, and keys expire after 60 s as today. Following both teams unions their switches. A game follow with the alert off removes the user even if a team follow has it on.

### 5.2 One push per moment per user

A play's alerts, minus the final, are one moment. For each alert, an audience key is built as in 5.1. Then:

1. SUNIONSTORE every audience key into the moment's audience.
2. SSCAN it in pages of 1,000.
3. For each page, one SMISMEMBER per alert, pipelined, says which alerts each user gets.
4. Each user with at least one alert gets one message.

Memory stays one page at a time, however big the audience.

### 5.3 Headline and extras

The user's alerts are ordered by priority. The first is the headline and the rest ride along as `extras`. Priority, highest first (decision 15's list, wave 1 part):

1. Overtime, shootout
2. Scores (touchdown, field goal, safety, generic score, run, goal), then the try result
3. Lead change
4. Close game late
5. Halftime, end of period, end of inning
6. Game start, delayed, resumed

Ties within a level go home before away, then by id. Example: touchdown + lead change = one push, headline `TOUCHDOWN`, extra `LEAD_CHANGE`.

### 5.4 The final is its own push, and comes last

When a play also produces the final, the final's audience (section 5.1, switch `FINAL`) gets its own message, never merged. Stage 1 sends every moment message for the play before any final message. Both are keyed by user id, so for a user who gets both, the final lands after the moment push in the same lane, and Stage 2 delivers them in that order. An overtime-winning goal is two pushes: the goal, then "Final (OT)".

### 5.5 Message and body

- **Message id** = the user's headline alert id (the final's is `…:FINAL`). Stage 2's dedup key becomes `notif:<alertId>:<userId>`, and history's primary key `(user_id, message_id)` holds it.
- **Body** = the API's `Notification`:
  - `id` (the message id) and `kind: game_moment`;
  - `headline` and `extras` as `{type, text}`;
  - `game` with both sides, `periodLabel`, and `clock`;
  - `detectedAt`.
- **Built once per distinct set of alerts in the moment,** usually one or two, and reused across users.
- **The message's older flat fields** (`eventType`, `message`, scores, status) are filled from the headline and the play.

### 5.6 History reads a bad body safely (minor 2)

`HistoryWriter.toRow` checks the body's shape (e.g. `game` must be an object) and skips a malformed record with a log line, like the other skips. A `ClassCastException` can no longer escape.

---

## 6. Stage 1 in 3.3

`Stage1Worker.handle` becomes: parse; read `det:`; skip if the version isn't new; run the detectors; build the moment and final audiences; send; flush and check confirmations; write `det:` set-if-higher. Unchanged:
- one thread per worker;
- commit after the batch;
- rewind on failure;
- the `sub:built` wait (3.2), now also holding while user-pref rebuilds after a Redis restart.

Removed: `AlertMapping`, `Stage1Progress`, `RedisKeys.STAGE1_LAST_PREFIX`, `AlertCatalog.scoringTypes` (if nothing else uses it). `Audience` is replaced by the section 5 classes: `AlertAudience` (5.1) and `MomentFanout` (5.2–5.4).

---

## 7. Tests

### 7.1 Detector unit tests (pure)

One class per detector family, plus `DetectorsTest` for the chain and the baseline. Must include:

- **The user's test:** score, reversal, score again → two alerts, `…:SCORE:home:1` and `…:SCORE:home:2`.
- **Every NFL try path:**
  - +6 then +1;
  - +6 then +2 (a 2-point conversion, not a safety);
  - +7 and +8 in one play;
  - +6 then the clock drops below the touchdown's clock on one play → still pending, two plays → no good;
  - +6 then the period ends → no good;
  - no good, then a late +1 → `…:late`;
  - the other team scores during a try;
  - +2 with no try pending → safety;
  - an odd jump → generic.
- **Lead change:** through a tie, without a tie, the first lead (no alert), and NBA before Q4 (no alert, leader still tracked).
- **Close game late:**
  - one glitched snapshot at 0:00 → no alert;
  - two plays at or under 5:00 → one alert;
  - healed back above 5:00, then under again → no second alert;
  - overtime → never;
  - MLB in the 8th → fires without the two-play rule.
- **Period ends:** from the END_PERIOD stage, from the period number with the stage missed, both (one alert), the last regulation period (none), halftime.
- **Overtime and shootout:**
  - double overtime fires twice;
  - MLB extra innings fires once;
  - NHL regular-season period 5 → shootout, not overtime;
  - NHL postseason period 5 → overtime.
- **End of inning:** innings 1–8 fire, the 9th never does, and a tied 10th fires.
- **Delayed and resumed:** delayed → postponed fires twice; an MLB resume fires; a pre-game delay then the start → game start, not resumed.
- **Final:** win, NFL tie, `(OT)`, `(SO)`; CANCELED is a delay, not a final; a score after the final is quiet.
- **Baseline:** a first play mid-Q4 with a close score marks close-late fired and alerts nobody.

### 7.2 With Redis and Kafka

- **Set math** (`AlertAudienceTest`):
  - a game follow with the alert off silences a team follow;
  - following both teams unions their switches;
  - a league follow is skipped when the user follows the game or either team;
  - the user is reached once through several follows;
  - innings sets work for inning n.
- **Follow sets:** `:ANY` is written on save, removed on delete, and rebuilt; innings are normalized; the marker is deleted when a commit throws; the marker is deleted when `run_id` changes, and not on the first read.
- **Stage 1:**
  - **one push per moment:** touchdown + lead change → one message, with headline and extra;
  - **the final comes last:** an overtime-winning goal on the play that turns final → two messages per user, the goal first, then the final. Checked for several users from what a test gateway channel receives.
  - **crash and duplicates:** a crash after the sends, before the `det:` write → re-read → each user gets one push (Stage 2 dedup on alert ids); a duplicate version → nothing.
  - **re-read from the start of `game-events`:** no user gets a second push.
  - **a Redis rollback:** `det:` reset to the previous play's memory → the next play re-detects with the same ids → no second push.
  - **paging:** 2,000 followers get one message each (the existing test, kept).
- **Simulator:**
  - two runs in a row have different game ids;
  - end to end through the poller, Event Processing, Stage 1, and Stage 2, two simulator runs in a row reach the same user with both runs' alerts (start, scores, final each time).
- **History:** a body whose `game` is not an object is skipped without an exception.
- **The poller:** a clock-only change sends a `GAME_SYNC`; a first sight is still quiet; the new fields arrive in `game:<id>`.

### 7.3 Recorded games

`make replay-detectors` reads `~/sportsnotifier-recordings` (or `RECORDINGS_DIR`), turns each snapshot into a play the way the poller would, runs the detectors per game in order, and prints each game's alert timeline. It is a test tool only: no Redis, Kafka, or cluster.

Two golden fixtures are cut from the recordings with `scripts/trim-espn-fixture.py` and checked into `src/test/resources/espn/replay/`: one full NFL game and one NHL game that went to overtime. A test compares their timelines with an expected list written by hand and reviewed by the user. The recorder polled every 30 seconds, coarser than the live poller, so the fixtures test the rules, not the timing.

### 7.4 Smoke test and persistence check

- Find simulated games by team (`?team=`), not by the old fixed game ids.
- **Bob follows `mlb-hou`** and gets the game start and a run, as today. A second user follows the simulated NFL home team and gets `TOUCHDOWN` followed by `TRY_RESULT`.
- **A third user** follows the same NFL team with `TOUCHDOWN` off and gets no touchdown.
- **The persistence check's same-user replay** still sees a new game start after the rebuild, because the second run has new game ids.

---

## 8. Order of work

CI stays green after every task: the old path stays until its replacement lands (as in 3.1). "Inline" means built in small steps the user reads (CLAUDE.md, core distributed code).

| # | Task | How |
|---|---|---|
| 1 | Position fields: `GameSnapshot`, `EspnScoreFeed`, `GameEvent`, the script saves them, `PollDecision` sends every position change | inline |
| 2 | `GameLabels`, the detector framework (`Play`, `GameMemory`, `Detector`, `Alert`, ids, baseline) | inline |
| 3 | Game flow detectors: start, final, delayed and resumed, period ends, halftime, end of inning, overtime, shootout | inline |
| 4 | Score detectors with the NFL try; lead change; close game late | inline |
| 5 | `make replay-detectors` and the two golden fixtures (expected timelines reviewed by the user) | subagent, then walked through |
| 6 | Follow sets: `:ANY`, innings sets, rebuild; minors 8, 4, 5 | inline |
| 7 | `AlertAudience`: most specific follow wins | inline |
| 8 | `MomentFanout`: grouping, headline and extras, message ids, the final last; `NotificationBody`; minor 2 | inline |
| 9 | Simulator as `SimulatedScoreFeed`: scripts, unique run ids, `teams.json` abbreviations, chart env and network policy, the two-runs test | subagent, then walked through |
| 10 | Stage 1 switch-over: `det:` replaces `s1:last`, detectors and `MomentFanout` wired in, `AlertMapping` and `Stage1Progress` removed, catalog `coming_soon`. Rebuild the laptop cluster | inline |
| 11 | Smoke test and persistence check (section 7.4); fresh-cluster smoke | inline |
| 12 | Docs (section 9) and the 3.3 quiz section | inline |

## 9. Docs to update

- `DESIGN.md`: updated with this spec (decision 15 ids, detection, fan-out; build order). At task 12, mark 3.3 merged after the PR.
- `docs/api/openapi.yaml`: the `Notification.id` description and example (an alert id), `periodLabel` and `clock` now filled.
- `docs/api/websocket.md`: the notification example, if it shows an id.
- `CLAUDE.md` and `AGENTS.md`: open problem 1 removed, "New in Phase 3.3", the test count, `det:`, `:ANY`.
- `docs/study/phase-3-quiz.md`: a 3.3 section.

## Known gaps accepted in 3.3

- **Between snapshots:** a lead that flips twice inside one poll, or a period that starts and ends unseen, is missed or merged (decision 15's limits).
- **Shootout rule unconfirmed** in live data (section 3.9).
- **The final names the winner,** not "your team won."
- **A late extra point** gives two pushes ("no good", then "good after all"), and only when ESPN posts the point more than two plays after the clock restarts.
- **Switches changed during a crash window:** if a user changes switches between Stage 1's sends and a re-read, the replay can pick a different headline, so a different message id, and the user gets a second push for that moment. The window is seconds.
- **Redis losing a second** of both `det:` memory and the matching `notif:` keys can repeat one push (section 2.3).
- **An early "final" from ESPN** is still the bar for the rest of the game (CLAUDE.md open problem 2, unchanged).
