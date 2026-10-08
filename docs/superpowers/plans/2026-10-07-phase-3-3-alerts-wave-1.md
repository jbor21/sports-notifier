# Phase 3.3 Alerts, Wave 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stage 1 detects decision 15's wave 1 alerts from ordered game snapshots, picks each user's switches by the most specific follow, and sends one push per moment per user (the final always its own push, sent last), with deterministic alert ids.

**Architecture:**
- **Plays carry the whole position.** The poller sends one on every position change, and the script saves the position in `game:<id>`.
- **Pure detectors in a new `alerts` package** turn (memory, play) into alerts plus new memory. Stage 1 keeps that memory in `det:<gameId>`, written set-if-higher after the messages are confirmed.
- **The audience is built in Redis** from the `sub:*` sets plus new `:ANY` sets. It is grouped per user, a page at a time, into one message each.

**Tech Stack:** Java 17, Jedis 4.4.7 (SMISMEMBER needs Redis 6.2+; tests and chart use 7.4), Gson, kafka-clients 4.3.1, Testcontainers 2.0.5. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-07-phase-3-3-alerts-wave-1-design.md`. Read it with this plan; section numbers below refer to it. Parent: `DESIGN.md` decision 15.

## Global Constraints

- **Redis keys only in `util/RedisKeys.java`.**
  - New: `DETECTOR_PREFIX = "det:"` and `SUB_ANY = "ANY"`. The latter is a set-name suffix: `sub:<kind>:<target>:ANY`.
  - Inning sets are `sub:<kind>:<target>:END_OF_INNING:<n>`, n 1–9.
  - Removed at Task 10: `STAGE1_LAST_PREFIX`.
- **TTLs and sizes:** `det:` 8 days (`691_200` s). Fan-out temporary keys 60 s. Audience pages 1,000.
- **Regulation:** NFL 4, NBA 4, NHL 3 periods; MLB 9 innings.
- **Season type:** `seasonType` 1 is preseason, 2 regular season, 3 postseason. 2 when ESPN omits it.
- **Clock:** `clockTenths` is tenths of a second left. 5:00 is `3000`. `-1` means ESPN sent no clock, and no clock rule may treat it as late.
- **Alert ids (spec 2.4):**
  - `<gameId>:SCORE:<home|away>:<n>`
  - `<gameId>:TRY_RESULT:<home|away>:<n>`, and `…:late` for the late follow-up
  - `<gameId>:LEAD_CHANGE:<n>`
  - `<gameId>:END_OF_PERIOD:<p>`, `<gameId>:END_OF_INNING:<i>`, `<gameId>:OVERTIME:<p>`
  - `<gameId>:DELAYED:<n>`, `<gameId>:RESUMED:<n>`
  - Once per game: `<gameId>:GAME_START`, `:HALFTIME`, `:CLOSE_LATE`, `:SHOOTOUT`, `:FINAL`
- **Headline priority** (lower number first, spec 5.3): OVERTIME/SHOOTOUT 1; scores 2; TRY_RESULT 3; LEAD_CHANGE 4; CLOSE_LATE 5; HALFTIME/END_OF_PERIOD/END_OF_INNING 6; GAME_START/DELAYED/RESUMED 7. Ties go HOME before AWAY (no side last), then by id. FINAL is never in a moment.
- **Close game late (spec 3.7):** NBA gap ≤ 5, Q4, ≤ 3000 tenths. NFL gap ≤ 8, Q4, ≤ 3000. NHL gap ≤ 1, 3rd period, ≤ 3000. MLB gap ≤ 2, inning 8 or 9. Clock sports also need the previous play late.
- **Behavior:** every Jedis handle in try-with-resources. Config through env vars with defaults. Comment density as in the surrounding code: explain *why* at the tricky spots.
- **Every task keeps `make test` green**, and `make cluster up smoke` green from Task 10 on. The old Stage 1 path (`AlertMapping`, `Stage1Progress`, `Audience`) stays until Task 10.
- **Test setup:**
  - Redis is localhost:6379 DB 1, flushed.
  - Kafka comes from `testsupport/KafkaTestSupport` (`createTopic` waits for leaders).
  - Postgres comes from `testsupport/PostgresTestSupport`.
  - Pure `alerts` tests touch no Redis.

## Review Focus

1. **ESPN sends no clock on a live game.** It must read as `clockTenths = -1`, never as 0:00. Close game late and "try no good" must not fire from it. Tests: Task 1 (`missingClockIsMinusOne`) and Task 4 (`missingClockIsNeverLate`, `missingClockNeverFailsATry`).
2. **The detectors see a game for the first time already final, or jump from before the game straight to final** (poller or Stage 1 down for the whole game). Expected: only the final, or nothing on a baseline. No "Bears score 24", no game start, no lead change. Test: Task 4 (`preToFinalSendsOnlyTheFinal`).
3. **A team that is not in `teams.json`** (All-Star teams, an ESPN abbreviation change). Text falls back to the abbreviation, the audience skips that team's sets, and nothing throws. Tests: Task 2 (`unknownTeamUsesAbbreviation`) and Task 7 (`unknownTeamIsSkipped`).
4. **One play where one team's score goes up and the other's goes down** (a correction landing with a score). One score alert, for the riser, and the lead is judged on the scores as they are now. Test: Task 4 (`riseAndCorrectionInOnePlay`).
5. **A snapshot with no `season` or no `shortDetail`** (older fixtures, other endpoints). It defaults to season type 2 and a null detail, without failing the game. Test: Task 1 (`missingSeasonAndDetailDefault`).

---

### Task 1: Plays carry the whole position

**Files:**
- Modify: `src/main/java/model/GameSnapshot.java` (add `int seasonType, String detail` at the end; keep a 14-arg constructor defaulting to `2, null`)
- Modify: `src/main/java/feed/EspnScoreFeed.java` (read `event.season.type` and `status.type.shortDetail`; a missing `status.clock` on a game that isn't PRE becomes `-1`; make `parse(String, String)` public for Task 5)
- Modify: `src/main/java/feed/Seq.java` (no change in value: `clamp(-1)` is already 0. Add one comment line saying so)
- Modify: `src/main/java/model/GameEvent.java`, `src/main/java/feed/PollDecision.java`, `src/main/java/service/impl/EventProcessingServiceImpl.java`
- Modify: `scripts/trim-espn-fixture.py` (keep `season` and the event `date`)
- Test: `EspnScoreFeedTest`, `PollDecisionTest`, `EventProcessingServiceImplTest`

**Interfaces:**
- Produces: `GameEvent` fields and getters. Strings for enum values:
  - `getPhase()`: `PRE`, `LIVE`, or `FINAL`
  - `getPeriod()` (int) and `getClockTenths()` (int, `-1` unknown)
  - `getHalf()`: `TOP`, `MIDDLE`, `BOTTOM`, or `END`
  - `getOuts()` (int)
  - `getStage()`: `IN_PLAY`, `DELAYED`, `END_PERIOD`, or `HALFTIME`
  - `getSeasonType()` (int) and `getDetail()` (String, nullable)
- Produces: `GameEvent withPosition(String phase, int period, int clockTenths, String half, int outs, String stage, int seasonType, String detail)`, which returns a copy. `withVersion` copies the position too.
- Produces: `game:<id>` gains fields `phase`, `period`, `clockTenths`, `half`, `outs`, `stage`, `seasonType`, `detail` (`""` when null).
- Ruling: `phase` is added to the play though spec 1.1 doesn't list it. The detectors must not decode `seq`, and spec 2.2's memory holds the phase.

- [ ] **Step 1: Write the tests.**
  - `EspnScoreFeedTest.readsSeasonTypeAndDetail` (inline JSON with `"season":{"type":3}` and `shortDetail:"2nd OT"` → `seasonType()==3`, `detail()=="2nd OT"`)
  - `missingSeasonAndDetailDefault` (→ `2`, `null`)
  - `missingClockIsMinusOne` (live game without `clock` → `clockTenths()==-1`; `Seq.of` equals the same snapshot with clock 0)
  - `PollDecisionTest.clockOnlyChangeSendsGameSync` (record matches the snapshot except the clock → `GAME_SYNC`)
  - `sameSeqSendsNothing`
  - `eventCarriesPosition` (every new field copied from the snapshot)
  - `firstSightStillQuiet` (empty record → `GAME_SYNC`, as before)
  - `EventProcessingServiceImplTest.acceptedPlaySavesPosition` (`hgetAll("game:<id>")` has all eight fields)
- [ ] **Step 2: Run** `mvn -q test -Dtest='EspnScoreFeedTest,PollDecisionTest,EventProcessingServiceImplTest'` → the new tests FAIL.
- [ ] **Step 3: Implement.**
  - `PollDecision.decide`: after the existing status/score sync rule, `if (Seq.of(snap) != longOf(record.get("lastSeq"))) return GAME_SYNC`. `event(...)` calls `withPosition` from the snapshot.
  - Script: ARGV[17..24] are the eight fields, written in the existing `HSET` of the record. Update the ARGV list in the Javadoc.
- [ ] **Step 4: Run** `make test` → PASS. `PollerTest` and the heal tests must still pass. A lower-seq snapshot is now sent and answered `stale`, which is the 3.1 heal's input.
- [ ] **Step 5: Commit** `git commit -m "Plays carry period, clock, half, outs, stage, season type; the poller sends every position change"`

### Task 2: Labels and the detector framework

**Files:**
- Create in `src/main/java/alerts/`: `Play.java`, `Side.java`, `Alert.java`, `AlertIds.java`, `GameMemory.java`, `Detector.java`, `Detectors.java`, `SportRules.java`, `GameLabels.java`
- Modify: `scripts/fetch-teams.py` (add `"shortName": t["shortDisplayName"]`), regenerate `src/main/resources/catalog/teams.json`, `src/main/java/catalog/Teams.java` (`Team` gains `shortName`; a missing one falls back to the abbreviation)
- Test: `src/test/java/alerts/GameLabelsTest.java`, `GameMemoryTest.java`, `DetectorsTest.java`; `catalog/TeamsTest` (every team has a short name)

**Interfaces:**
- Produces: `record Play(String gameId, long version, String league, String homeTeam, String awayTeam, int homeScore, int awayScore, String phase, String status, String stage, int period, String half, int clockTenths, int outs, int seasonType, String detail, long detectedAtMillis)`.
  - Factory: `static Play of(GameEvent e)`.
  - Helpers: `boolean live()`, `boolean isFinal()` (status `FINAL`), `int score(Side s)`, `String team(Side s)`.
- Produces: `enum Side { HOME, AWAY }` with `String key()` (`"home"`/`"away"`) and `Side other()`.
- Produces: `record Alert(String id, String type, List<String> switches, int priority, Side side, String text)`, plus `static final Comparator<Alert> ORDER` (Global Constraints) and the priority constants `P_OVERTIME=1, P_SCORE=2, P_TRY=3, P_LEAD=4, P_CLOSE=5, P_PERIOD=6, P_FLOW=7`.
- Produces: `AlertIds` static methods `score(g, Side, n)`, `tryResult(g, Side, n)`, `tryLate(g, Side, n)`, `leadChange(g, n)`, `endOfPeriod(g, p)`, `endOfInning(g, i)`, `overtime(g, p)`, `delayed(g, n)`, `resumed(g, n)`, `once(g, String type)`.
- Produces: `GameMemory`, a Gson-serializable mutable class.
  - Last position: `phase, status, stage, period, half, clockTenths, homeScore, awayScore, boolean wasLate`.
  - `Set<String> fired`. Counters: `homeScores, awayScores, leadChanges, delays, resumes`. `Side lastLeader`.
  - Try state: `TryState pending` (`side, n, period, clockTenths, playsBelow`) and `boolean homeTryNoGood, awayTryNoGood`.
  - Methods: `copy()`, `toJson()`, `static fromJson(String)`, `boolean fired(String flag)`, `void fire(String flag)`, `int nextScore(Side)` (adds 1, returns the new count), `void takePosition(Play p)` (sets the position and `wasLate = SportRules.isLate(p)`).
- Produces: `interface Detector`:
  - `void detect(GameMemory before, Play now, GameMemory after, List<Alert> out)`
  - `default void baseline(Play now, GameMemory after) {}`
  - `default Alert finalAlert(GameMemory before, Play now, GameMemory after) { return null; }`. Only the final detector overrides it.
- Produces: `Detectors(List<Detector> chain)` and `static Detectors standard(GameLabels labels)`. Tasks 3–4 add their detectors to `standard`.
- Produces: `Result run(GameMemory before /* null → baseline */, Play now)` with `record Result(List<Alert> moment /* sorted by ORDER */, Alert finalAlert /* nullable */, GameMemory after)`.
  - Order inside `run`: `after = before.copy()`; each detector `detect`; collect `finalAlert`; then `after.takePosition(now)`.
  - Baseline: `after = new GameMemory()`; each detector `baseline(now, after)`; `takePosition`; no alerts.
- Produces: `SportRules` static methods `regulation(league)`, `closeGap(league)`, `isLate(Play)` (spec 3.7 late condition only, clock `-1` → false), `endOfPeriodPeriods(league)` (NFL/NBA `[1,3]`, NHL `[1,2]`, MLB `[]`), `hasHalftime(league)` (NFL, NBA), `isShootout(Play)` (NHL, `seasonType != 3`, `period >= 5` or `detail` contains `SO`).
- Produces: `GameLabels(Teams teams)` with:
  - `static String periodLabel(Play)`: null before the game; spec 2.6. NHL regular season period 5 is `SO`. Postseason NHL p≥5 is `(p-3)+"OT"`. NFL/NBA p≥6 is `(p-4)+"OT"`.
  - `static String clock(Play)`: null for MLB, END_PERIOD, HALFTIME, PRE, FINAL, or `-1`. `m:ss` from 600 tenths up, `s.t` below.
  - `String shortName(String league, String abbr)`.
  - `String scoreLine(Play)`: leader first, away first when tied, then ` · <label> <clock>` with the parts that exist. Example: `Raiders 20, Bears 17 · Q4 4:12`.

- [ ] **Step 1: Write the tests.**
  - `GameLabelsTest`:
    - `nflLabels` (Q1, Half, OT, 2OT)
    - `nhlLabels` (3rd, OT, SO regular season, 2OT postseason)
    - `mlbLabels` (Top 4th, Mid 4th, Bot 4th, End 4th, 11th)
    - `clockFormats` (2520 → `4:12`, 423 → `42.3`, MLB null, END_PERIOD null, -1 null)
    - `scoreLineLeaderFirst`
    - `scoreLineTiedAwayFirst`
    - `unknownTeamUsesAbbreviation`
  - `GameMemoryTest`:
    - `jsonRoundTrip` (every field, including the try state)
    - `copyIsIndependent`
    - `nextScoreCounts` (1, 2, 3)
  - `DetectorsTest`:
    - `nullMemoryIsBaselineWithNoAlerts`
    - `momentIsSortedByOrder`
- [ ] **Step 2: Run** `mvn -q test -Dtest='GameLabelsTest,GameMemoryTest,DetectorsTest,TeamsTest'` → FAIL.
- [ ] **Step 3: Implement** the files above. Run `scripts/fetch-teams.py`. If ESPN is unreachable, stop and tell the user rather than hand-editing `teams.json`.
- [ ] **Step 4: Run** the same tests → PASS. Then `make test` → PASS.
- [ ] **Step 5: Commit** `git commit -m "alerts package: Play, memory, ids, labels, detector chain"`

### Task 3: Game flow detectors

**Files:**
- Create in `src/main/java/alerts/`: `GameStartDetector`, `FinalDetector`, `DelayDetector`, `PeriodEndDetector`, `OvertimeDetector`, `ShootoutDetector`; add them to `Detectors.standard`
- Test: one test class per detector in `src/test/java/alerts/`, plus a `PlayBuilder` test helper (`PlayBuilder.nfl("g1").live().period(4).clock(2520).score(17,20).build()` style)

**Interfaces:**
- Consumes: Task 2's types.
- Produces: alerts with these types, switches, priority, and text. `<line>` is `GameLabels.scoreLine(now)`. `<Away>` and `<Home>` are short names.

| Detector | Fires when (spec 3.x) | type / switches | Text |
|---|---|---|---|
| GameStart | `before.phase==PRE && now.phase==LIVE`, flag `GAME_START` | `GAME_START` | `Kickoff: <Away> at <Home>.` (NBA `Tip-off`, MLB `First pitch`, NHL `Puck drop`) |
| Final | `now.isFinal()` and flag `FINAL` unset; returned by `finalAlert`, never in the moment | `FINAL` | `Final: <line without position>. <Winner> win.` `Final (OT)`, `Final (SO)`; tie: `… Tie.` |
| Delay | the status enters DELAYED/POSTPONED/SUSPENDED/CANCELED from a different status | `DELAYED` | `Delayed: <Away> at <Home> · <label clock>` (`Postponed`, `Suspended`, `Canceled`) |
| Delay (resume) | MLB, `before.status` DELAYED or SUSPENDED, now IN_PROGRESS and live | `RESUMED` type, switch `DELAYED` | `Play resumes: <line>` |
| PeriodEnd | spec 3.6; flags `END_OF_PERIOD:<p>`, `HALFTIME`, `END_OF_INNING:<i>` | `END_OF_PERIOD` / `HALFTIME` / `END_OF_INNING` (switches `END_OF_INNING` and, for i ≤ 9, `END_OF_INNING:<i>`) | `End of Q1: <line>`, `Halftime: <line>`, NHL `End of 1st: <line>`, MLB `End of 4th: <line>` |
| Overtime | `now.period > regulation`, not a shootout, flag `OVERTIME:<p>`; MLB only p = 10 | `OVERTIME` | `Overtime: <line>`, `Double overtime: …`, `Triple overtime: …`, then `<n>OT: …`; MLB `Extra innings: <line>` |
| Shootout | `SportRules.isShootout(now)`, flag `SHOOTOUT` | `SHOOTOUT` | `Shootout: <line>` |

- Each detector's `baseline` marks its flags for conditions already true:
  - started → `GAME_START`
  - final → `FINAL`
  - every period, halftime, or inning already ended
  - every overtime period ≤ the current one
  - shootout
- Counters and ids: `delays`/`resumes` go up by one per alert, and the id uses the new count.

- [ ] **Step 1: Write the tests.** Spec 7.1 game flow bullets, one test each:
  - **`PeriodEndDetectorTest`:**
    - `fromEndPeriodStage`
    - `fromPeriodNumberWhenStageMissed`
    - `stageThenNumberFiresOnce`
    - `lastRegulationPeriodSendsNothing`
    - `halftimeNotEndOfQ2`
    - `nhlFirstAndSecond`
    - `inningsOneToEightFire`
    - `ninthNeverFires`
    - `tiedTenthFires`
    - `inningSwitchesIncludeChosenSet` (inning 4 → switches `[END_OF_INNING, END_OF_INNING:4]`; inning 11 → `[END_OF_INNING]`)
  - **`OvertimeDetectorTest`:**
    - `doubleOvertimeFiresTwice`
    - `mlbExtraInningsOnce`
    - `nhlRegularSeasonPeriodFiveIsShootoutNotOvertime`
    - `nhlPostseasonPeriodFiveIsOvertime`
  - **`ShootoutDetectorTest`:**
    - `fromPeriodFive`
    - `fromDetailSO`
    - `preseasonCounts`
  - **`DelayDetectorTest`:**
    - `delayedThenPostponedFiresTwice` (ids `:DELAYED:1`, `:DELAYED:2`)
    - `mlbResumeFires`
    - `pregameDelayThenStartIsNotResume`
    - `canceledIsADelay`
  - **`GameStartDetectorTest`:**
    - `preToLive`
    - `pregameDelayStillStarts`
    - `preToFinalIsNotAStart`
  - **`FinalDetectorTest`:**
    - `winText` (`Final: Bears 20, Raiders 17. Bears win.`)
    - `nflTie`
    - `overtimeSuffix`
    - `shootoutSuffix`
    - `canceledIsNotFinal`
    - `finalFiresOnce`
  - **`DetectorsTest.baselineMarksPassedFlags`** (first play in Q3 → `GAME_START`, `END_OF_PERIOD:1`, `HALFTIME` marked; no alerts)
- [ ] **Step 2: Run** `mvn -q test -Dtest='alerts.*Test'` → FAIL.
- [ ] **Step 3: Implement** the six detectors.
- [ ] **Step 4: Run** `mvn -q test -Dtest='alerts.*Test'` → PASS.
- [ ] **Step 5: Commit** `git commit -m "Game flow detectors: start, final, delays, period ends, overtime, shootout"`

### Task 4: Score, lead change, close game late

**Files:**
- Create in `src/main/java/alerts/`: `ScoreDetector.java`, `LeadChangeDetector.java`, `CloseLateDetector.java`; add them to `Detectors.standard` (score, then lead change, then close late)
- Test: `ScoreDetectorTest.java`, `NflTryTest.java`, `LeadChangeDetectorTest.java`, `CloseLateDetectorTest.java`

**Interfaces:**
- Consumes: Task 2's types, `PlayBuilder` (Task 3).
- Produces: alerts with types `TOUCHDOWN`, `FIELD_GOAL`, `SAFETY`, `SCORE` (switches `[TOUCHDOWN, FIELD_GOAL, SAFETY]`), `TRY_RESULT`, `RUN_SCORED`, `GOAL`, `LEAD_CHANGE`, `CLOSE_LATE`. Each switch equals its type except `SCORE`.

**When scores may alert:** `now.live()`, or `now.isFinal() && before.phase == LIVE`. Otherwise the memory just takes the score (Review Focus 2).

**Score text:**

| Alert | Text |
|---|---|
| Touchdown (+6) | `Touchdown <T>! Extra point pending. <line>` |
| Touchdown + try (+7, +8) | `Touchdown <T>, extra point good. <line>` / `Touchdown <T>, 2-point conversion good. <line>` |
| Field goal | `Field goal <T>. <line>` |
| Safety | `Safety! <T> score 2. <line>` |
| Generic score | `<T> score <d>. <line>` |
| Try result | `Extra point good. <line>` / `2-point conversion good. <line>` / `Try no good. <line>` / `Extra point good after all. <line>` |
| MLB run | `<T> score! <line>` for one run, `<T> score <d>! <line>` for more |
| NHL goal | `Goal <T>! <line>` for one, `<d> goals <T>! <line>` for more |
| Lead change | `<T> take the lead. <line>` |
| Close game late | `Close game late: <line>` |

**NFL try state machine.** Per play, before reading this play's rises:

```
if pending != null:
    s = pending.side
    if rise(s) in {1, 2} and rise(s.other) == 0 -> TRY_RESULT good (id tryResult(s, pending.n)); pending = null; consume rise(s)
    elif rise(s.other) > 0                        -> TRY_RESULT no good; set tryNoGood(s); pending = null; defensive = s.other
    elif now.period != pending.period or stage is END_PERIOD/HALFTIME or now.isFinal()
                                                  -> no good; set tryNoGood(s); pending = null
    elif now.clockTenths >= 0 and now.clockTenths < pending.clockTenths
                                                  -> pending.playsBelow += 1; if playsBelow == 2: no good; set tryNoGood(s); pending = null
    else                                          -> pending.playsBelow = 0
for each side with an unconsumed rise d > 0:
    d == 1 and tryNoGood(side) -> TRY_RESULT "good after all" (id tryLate(side, lastTdN)); clear tryNoGood(side)
    d == 6 -> TOUCHDOWN, n = nextScore; pending = (side, n, period, clock, 0)
    d == 7 / 8 -> TOUCHDOWN (combined text), n = nextScore
    d == 3 -> FIELD_GOAL; d == 2 and side != defensive -> SAFETY; else -> SCORE generic   (a defensive conversion is generic, spec 3.4)
    any score alert for side other than the late case clears tryNoGood(side)
```

`lastTdN` is the try state's `n` kept after it resolves. Add `int homeLastTd, awayLastTd` to `GameMemory`, Task 2's class; it's an additive field.

- [ ] **Step 1: Write the tests.**
  - **`ScoreDetectorTest`:**
    - `scoreReversalScoreGivesTwoAlerts`: NHL home 2→3, then 3→2, then 2→3 → ids `…:SCORE:home:1` and `…:SCORE:home:2`, two `GOAL` alerts. **The user's test.**
    - `mlbOneAlertPerTeamPerPlay` (`Cubs score 2!`)
    - `nhlTwoGoalsOneAlert`
    - `nbaNoScoreAlerts`
    - `bothTeamsScoredTwoAlerts`
    - `riseAndCorrectionInOnePlay` (home +1, away −1 → one home alert)
    - `afterFinalQuiet`
    - `preToFinalSendsOnlyTheFinal` (run the full `Detectors.standard`: memory PRE 0–0, play FINAL 24–17 → `moment` empty, `finalAlert` present)
  - **`NflTryTest`:**
    - `touchdownThenExtraPoint`
    - `touchdownThenTwoPointIsNotSafety`
    - `touchdownAndExtraPointInOnePlay` (+7)
    - `touchdownAndTwoPointInOnePlay` (+8)
    - `clockBelowOnePlayStillPending`
    - `clockBelowTwoPlaysNoGood`
    - `periodEndNoGood`
    - `lateExtraPointAfterNoGood` (id ends `:late`)
    - `otherTeamScoresDuringTry` (no good, plus the other team's alert)
    - `defensiveTwoIsGenericNotSafety` (a try pending for home, away +2 → home `TRY_RESULT` no good + away `SCORE`)
    - `safetyWithoutTry`
    - `oddJumpIsGeneric` (+9 → `SCORE` with three switches)
    - `missingClockNeverFailsATry` (clock −1 on two plays → still pending)
  - **`LeadChangeDetectorTest`:**
    - `throughATie`
    - `withoutATie`
    - `firstLeadIsNotAChange`
    - `nbaBeforeQ4NoAlertButTracked` (a lead taken in Q2 and lost in Q4 → one alert, in Q4)
    - `idsCount`
  - **`CloseLateDetectorTest`:**
    - `oneGlitchedSnapshotNoAlert` (Q2 → Q4 0:00 → Q4 9:00)
    - `twoLatePlaysOneAlert`
    - `healedAboveThenUnderAgainNoSecond`
    - `overtimeNever`
    - `mlbEighthNoTwoPlayRule`
    - `missingClockIsNeverLate`
    - `baselineWhileCloseLateMarksFired`
- [ ] **Step 2: Run** `mvn -q test -Dtest='alerts.*Test'` → the new tests FAIL.
- [ ] **Step 3: Implement** the three detectors.
- [ ] **Step 4: Run** `mvn -q test -Dtest='alerts.*Test'` → PASS.
- [ ] **Step 5: Commit** `git commit -m "Score detector with the NFL try, lead change, close game late"`

### Task 5: Recorded-game replay and golden fixtures (subagent, then walked through)

**Files:**
- Create: `src/main/java/tools/ReplayDetectors.java`
- Create: `scripts/cut-replay-fixture.sh <league> <espnEventId>`. It writes `src/test/resources/espn/replay/<league>-<id>/NNNN.json`, one trimmed board per recording file that has the game, via `trim-espn-fixture.py`.
- Create: `src/test/java/tools/ReplayGoldenTest.java`, `src/test/resources/espn/replay/<league>-<id>/expected.txt` (two games)
- Modify: `Makefile` (`replay-detectors` target)

**Interfaces:**
- Consumes: `EspnScoreFeed.parse` (Task 1), `PollDecision.decide`, `Detectors.standard`, `Play.of`, `GameLabels`.
- Produces: `static List<String> ReplayDetectors.timeline(String league, List<String> boardsJson, String gameId)`.
  - Lines: `<periodLabel or -> <clock or -> <alert id> <text>`.
  - The final goes last for its play.
- Produces: `static void main(String[] args)`. It reads `RECORDINGS_DIR` (default `~/sportsnotifier-recordings`), all four leagues, and prints `== <gameId> ==` then that game's timeline.
- How a recording becomes plays: keep an in-memory record per game the way the script does. A snapshot is accepted when `PollDecision.decide` returns an event and its seq > `lastSeq`. Heals are not emulated (a comment says so). Version = accept count.

- [ ] **Step 1:** `ReplayDetectors` + `make replay-detectors` (`mvn -q -DskipTests package && java -cp target/sports-notifier-*.jar tools.ReplayDetectors`). Run it over the real recordings; it must finish without an exception.
- [ ] **Step 2:** Pick the fixtures.
  - One full NFL game. Choose one with at least one touchdown, one field goal, and a 4th quarter within 8 points if the recordings have one.
  - One NHL overtime game (`401891778` or another from the overtime list).
  - Cut them with `scripts/cut-replay-fixture.sh`.
- [ ] **Step 3:** Write each `expected.txt` from the printed timeline after checking every line by hand against the snapshots: right alert, right id, nothing missing or extra by spec section 3. Then `ReplayGoldenTest` compares `timeline(...)` with `expected.txt` line by line.
- [ ] **Step 4: STOP for the user.** Show both timelines in plain text, one line per alert, each with a short note on why it fired. Wait for approval. If they change any expectation, fix the detector (with a unit test in Tasks 3–4's classes), not the fixture.
- [ ] **Step 5: Run** `mvn -q test -Dtest=ReplayGoldenTest` → PASS. Then `make test` → PASS.
- [ ] **Step 6: Commit** `git commit -m "Replay recorded ESPN games through the detectors; two golden timelines"`

### Task 6: Follow sets: `:ANY`, innings sets, and three PR #13 minors

**Files:**
- Modify: `src/main/java/util/RedisKeys.java` (`SUB_ANY`)
- Modify: `src/main/java/service/impl/FollowSets.java`, `SubRebuilder.java`, `FollowService.java`
- Modify: `src/main/java/catalog/AlertSetting.java` (normalize `innings`)
- Test: `FollowSetsTest`, `SubRebuilderTest`, `FollowServiceTest`, `catalog/AlertCatalogTest`

**Interfaces:**
- Produces: `static Set<String> FollowSets.memberships(Follow f)`. It returns the set names (suffixes) the user belongs in for that follow:
  - always `ANY`;
  - every enabled switch, except `END_OF_INNING`;
  - for `END_OF_INNING`: `END_OF_INNING` if the innings are empty, else `END_OF_INNING:<n>` per chosen inning.
- Produces: `static Set<String> FollowSets.allNames(Follow f)`: every switch, `ANY`, and `END_OF_INNING:1`…`:9`.
- `writeState(f)`: SADD to `memberships`, SREM from `allNames − memberships`. `removeAll(f)`: SREM from `allNames`.
- `SubRebuilder.buildAll` uses `memberships(f)` instead of its own loop. The cleanup patterns already cover the new names.
- Produces: `AlertSetting`'s compact constructor turns `options.innings` into a sorted, distinct `List<Integer>`. This runs on save, on read, and in the rebuild (minor 8).
- `FollowService.save`: if `conn.commit()` throws, call the same "delete the marker, or mark the rebuild owed" path `writeRedis` uses (extract it as `askForRebuild()`), then rethrow (minor 4).
- `SubRebuilder` gains a package-private constructor parameter `Function<Jedis, String> runId` (default: the `run_id:` line of `jedis.info("server")`).
  - `checkOnce` reads it. If an earlier read exists and differs, it deletes `sub:built` and logs `[SubRebuilder] Redis restarted (run_id changed); rebuilding`.
  - It then remembers the value (minor 5).

- [ ] **Step 1: Write the tests.**
  - **`FollowSetsTest`:**
    - `anySetHoldsEveryFollower` (all switches off → still in `…:ANY`)
    - `deleteRemovesFromAny`
    - `chosenInningsGoInInningSets` (innings [3, 7] → in `END_OF_INNING:3` and `:7`, not in `END_OF_INNING`)
    - `everyInningGoesInPlainSet`
    - `switchingFromChosenToEveryMovesSets`
  - **`SubRebuilderTest`:**
    - `rebuildWritesAnyAndInningSets`
    - `runIdChangeDeletesMarker`
    - `firstRunIdReadDoesNot`
  - **`FollowServiceTest`:**
    - `inningsAreWholeNumbers` (PATCH `innings: [9.0, 3]` → GET returns `[3, 9]`; the Postgres row holds `[3, 9]`)
    - `commitFailureDeletesMarker` (a `DataSource` wrapper whose `Connection.commit()` throws → `sub:built` gone, and the call fails)
  - **`AlertCatalogTest.noAlertTypeIsCalledAny`**
- [ ] **Step 2: Run** `mvn -q test -Dtest='FollowSetsTest,SubRebuilderTest,FollowServiceTest,AlertCatalogTest'` → FAIL.
- [ ] **Step 3: Implement** as in Interfaces.
- [ ] **Step 4: Run** `make test` → PASS. The old `Audience` ignores `:ANY` and inning sets, so the old path is unaffected.
- [ ] **Step 5: Commit** `git commit -m "Follow sets: ANY and chosen innings; whole-number innings; rebuild on commit failure and Redis restart"`

### Task 7: `AlertAudience`: most specific follow wins

**Files:**
- Create: `src/main/java/service/impl/AlertAudience.java`
- Test: `src/test/java/service/impl/AlertAudienceTest.java`

**Interfaces:**
- Consumes: `Play`, `Alert` (Task 2), `FollowSets.prefix`, `TeamIds.of`, `RedisKeys.FANOUT_PREFIX`, `RedisKeys.SUB_ANY`.
- Produces: `AlertAudience()` (stateless) and `Built build(Jedis jedis, Play play, List<Alert> alerts)`.
  - `record Built(List<String> keys /* one audience key per alert, same order */, List<String> temps)`.
  - `void drop(Jedis jedis, Built b)` deletes `keys` and `temps`.
  - Every key is under `fanout:<gameId>:v<version>:<uuid>:`, has a 60 s TTL, and is built in one pipeline checked with `RedisPipelines.syncChecked`.
- Per alert i, with S = its switches (spec 5.1):

```
g = SUNIONSTORE base:i:g   sub:game:<gid>:s            for s in S
t = SUNIONSTORE base:i:t   sub:team:<H>:s, sub:team:<A>:s   for s in S    (skip null or duplicate team ids)
    SDIFFSTORE  base:i:t   base:i:t  sub:game:<gid>:ANY
l = SUNIONSTORE base:i:l   sub:league:<L>:s            for s in S
    SDIFFSTORE  base:i:l   base:i:l  sub:game:<gid>:ANY  sub:team:<H>:ANY  sub:team:<A>:ANY
    SUNIONSTORE base:i     base:i:g base:i:t base:i:l
    EXPIRE each 60
```

- [ ] **Step 1: Write `AlertAudienceTest`.**
  - `gameFollowWithAlertOffSilencesTeamFollow`
  - `bothTeamsUnionTheirSwitches` (home team TD off, away team TD on → in)
  - `leagueSkippedWhenUserFollowsGame`
  - `leagueSkippedWhenUserFollowsEitherTeam`
  - `reachedOnceThroughSeveralFollows`
  - `chosenInningReaches` (user with innings [4] gets inning 4's alert, not inning 5's)
  - `genericScoreReachesAnyOfThreeSwitches`
  - `unknownTeamIsSkipped`
  - `tempKeysExpireAndDropDeletesThem`
- [ ] **Step 2: Run** `mvn -q test -Dtest=AlertAudienceTest` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `mvn -q test -Dtest=AlertAudienceTest` → PASS.
- [ ] **Step 5: Commit** `git commit -m "Alert audience: the most specific follow wins, built in Redis"`

### Task 8: `MomentFanout`: one push per moment, the final last

**Files:**
- Create: `src/main/java/service/impl/MomentFanout.java`
- Modify: `src/main/java/model/NotificationBody.java` (add `of(Play, List<Alert> headlineFirst, GameLabels)`), `src/main/java/model/NotificationMessage.java` (add `of(Play, String userId, JsonObject body)`)
- Modify: `src/main/java/service/impl/HistoryWriter.java` (minor 2)
- Test: `MomentFanoutTest.java`, `HistoryWriterTest`

**Interfaces:**
- Consumes: `AlertAudience` (Task 7), `Detectors.Result` (Task 2).
- Produces: `MomentFanout(JedisPool pool, AlertAudience audience, GameLabels labels)` and `void send(Play play, Detectors.Result r, BiConsumer<String, NotificationMessage> sink)`.
  - Moment messages for every user come first (spec 5.2). Then the final's audience, one `FINAL`-only message each (spec 5.4).
  - Per page of 1,000 (SSCAN of the SUNIONSTORE of all keys): pipelined `smismember(key_i, page)` for each alert. A user's alerts keep the moment's `ORDER`.
  - Bodies are cached per distinct alert subset within one `send`.
  - Temporary keys are dropped in `finally`.
- Produces: the `NotificationBody.of(Play, List<Alert>, GameLabels)` JSON:
  - `id` = first alert id, `kind: "game_moment"`, `leagueId`;
  - `headline {type, text}` and `extras [{type, text}…]`;
  - `game {id, home, away, periodLabel, clock}` (sides as today);
  - `detectedAt`.
- Produces: `NotificationMessage.of(Play, String userId, JsonObject body)`.
  - `messageId` = the body's `id`, `eventType` = the headline type, `message` = the headline text.
  - The rest come from the play.
  - The old `of(GameEvent, …)` factories stay (tests use them).
- `HistoryWriter.toRow`: return null (skipped, logged as today) when `notification.game` is present but not an object.

- [ ] **Step 1: Write the tests.**
  - **`MomentFanoutTest`** (Redis DB 1; the sink collects `(userId, message)` in order):
    - `touchdownAndLeadChangeIsOnePush` (headline `TOUCHDOWN`, extras `[LEAD_CHANGE]`, `messageId` = the TD alert id)
    - `userWithOnlyLeadChangeGetsItAsHeadline`
    - `finalComesAfterMomentForEveryUser` (5 users following the team; for each user, the moment index < the final index; all moment messages come before any final)
    - `finalAloneWhenNoMomentAudience`
    - `bodyHasPeriodLabelAndClock`
    - `nobodyFollowsNoMessages`
  - **`HistoryWriterTest.gameNotAnObjectIsSkipped`**
- [ ] **Step 2: Run** `mvn -q test -Dtest='MomentFanoutTest,HistoryWriterTest'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same tests → PASS. Then `make test` → PASS.
- [ ] **Step 5: Commit** `git commit -m "Moment fan-out: one push per user per moment, headline and extras, the final sent last"`

### Task 9: The simulator as a score feed (subagent, then walked through)

**Files:**
- Create: `src/main/java/simulator/SimulatedScoreFeed.java`, `src/main/java/simulator/GameScript.java`
- Modify: `src/main/java/simulator/EventSimulatorMain.java` (rewrite)
- Check: `deploy/helm/sports-notifier/templates/simulator-cronjob.yaml`. It already has `REDIS_HOST` (via `baseEnv`) and the `part-of` label that `redis-from-app` admits. Change nothing unless `make simulate` shows otherwise.
- Test: `src/test/java/simulator/SimulatedScoreFeedTest.java`, `src/test/java/simulator/SimulatorRunsEndToEndTest.java`

**Interfaces:**
- Consumes: `Poller`, `ScoreFeed`, `GameIds.of`, `Teams`, `EventProcessingServiceImpl`, `Stage1Worker`, `Stage2Worker` and the test gateway channel pattern of `Stage2WorkerTest`.
- Produces: `SimulatedScoreFeed(long runStartSeconds, List<GameScript> wave, LongSupplier clock, long stepMillis)`, implementing `ScoreFeed`.
  - It returns the current snapshot of every game in the league.
  - Each game advances one step per `stepMillis` from its start (start = feed creation + jitter).
  - `boolean done()` is true once every game has reached its last step.
- Produces: game ids `GameIds.of(league, "sim" + runStartSeconds + "-" + slot)`.
- Produces: `GameScript`, fixed step lists per sport, built with `GameScript.nfl(home, away)` etc. Each step is a full position plus scores. Required content:
  - **NFL** (`KC` home, `SF` away in wave 1):
    - a KC touchdown (+6) with the extra point (+1) one step later;
    - an SF field goal;
    - a KC touchdown with a 2-point try in one step (+8);
    - halftime; end of Q3;
    - an SF lead change in Q4;
    - Q4 under 5:00 within 8 points on two steps;
    - final.
  - **MLB** (`BOS` home, `HOU` away): an HOU run in the 2nd, BOS runs in the 5th, innings 1–9, final.
  - **NHL:** a goal per team, ends of the 1st and 2nd, a 1-goal 3rd under 5:00, final.
  - **NBA:** quarters, halftime, final.
- Waves keep today's matchups with ESPN abbreviations from `teams.json`: `GS`, `NY`, `NO`, `UTAH`, `SA` as needed (minor 9).
- `EventSimulatorMain`:
  - `runStart = now/1000`;
  - each wave runs `Poller.pollLeague` for the four leagues every `SIM_POLL_MS` (default 1000) until `feed.done()` and every game's `game:<id>` status is FINAL;
  - 3 s between waves;
  - `SIM_WAVES` and `SIM_INTERVAL_MS` (step length) keep their meaning;
  - it exits 0 when done.
  - `pending` needs nothing extra: the next poll re-sends (issue #7).

- [ ] **Step 1: Write the tests.**
  - **`SimulatedScoreFeedTest`:**
    - `twoRunsHaveDifferentGameIds`
    - `everySimulatedTeamIsInTeamsJson`
    - `nflScriptHasTouchdownThenExtraPointNextStep`
    - `stepsAdvanceWithTheClock`
    - `doneAfterLastStep`
  - **`SimulatorRunsEndToEndTest`** (Kafka + Redis + Postgres-free):
    - Setup: one user follows `mlb-hou` (via `FollowSets.writeState` and `sub:built` set). The test runs the poller over a `SimulatedScoreFeed` (manual clock) with an `EventSink` calling `EventProcessingServiceImpl.processEvent` directly, publishing to a test topic, then Stage 1, then Stage 2, then a test gateway channel.
    - **`twoRunsReachTheSameUserTwice`:** run one MLB game, then a second run with a new `runStartSeconds` → the channel receives each run's game start, run, and final.
    - Before Task 10 this passes on the old path. After Task 10 it guards the `det:` reuse problem the user found.
- [ ] **Step 2: Run** `mvn -q test -Dtest='SimulatedScoreFeedTest,SimulatorRunsEndToEndTest'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `make test` → PASS. Then `make up && make simulate`: the job completes and `make logs` shows plays accepted for `*-sim<runStart>-*` ids.
- [ ] **Step 5: Commit** `git commit -m "Simulator drives the real poller through a scripted score feed; unique game ids per run"`

### Task 10: Stage 1 switch-over

**Files:**
- Create: `src/main/java/service/impl/DetectorMemory.java`
- Modify: `src/main/java/service/impl/Stage1Worker.java`, `Stage1WorkerMain.java`, `util/RedisKeys.java`, `src/main/resources/catalog/alerts.json`
- Delete: `AlertMapping.java` + `AlertMappingTest.java`, `Stage1Progress.java`, `Audience.java` + `AudienceTest.java`, `NotificationBody.of(GameEvent, String, Teams)` (move `HistoryWriterTest` to the new factory)
- Test: `DetectorMemoryTest` (new), `Stage1WorkerTest` (rewritten around the new messages), `Stage1WaitsForSetsTest` (constructor), `SimulatorRunsEndToEndTest` (one more test), `AlertCatalogTest`

**Interfaces:**
- Produces: `class DetectorMemory` (not final; the crash test overrides `writeIfHigher`):
  - `DetectorMemory(JedisPool pool)`;
  - `Stored read(String gameId)`, with `record Stored(long version, GameMemory memory /* null if none */)`;
  - `boolean writeIfHigher(String gameId, long version, GameMemory m)`. A Lua script on hash `det:<gameId>`, fields `version` and `memory`: write both only when the new version is higher, then `EXPIRE 691200`.
- Produces: `Stage1Worker(JedisPool pool, DetectorMemory memory, Detectors detectors, MomentFanout fanout, Consumer<String,String> consumer, Producer<String,String> producer, String notificationsTopic, BooleanSupplier setsReady)`.
  - `handle`: parse → `memory.read` → skip if `version <= stored.version` → `detectors.run(stored.memory, Play.of(play))` → `fanout.send(...)`, with the sink calling `producer.send(new ProducerRecord<>(topic, userId, json))` and recording errors as today → `flush` → throw if any send failed → `memory.writeIfHigher`.
  - Everything else (pause, rewind, health) is unchanged.
- `alerts.json`: these types become `"availability": "coming_soon"` in every league that has them: `STARTING_SOON`, `COMEBACK`, `BIG_RUN`, `BIG_LEAD`, `TIE_LATE`, `BIG_INNING`, `WALK_OFF`, `SHUTOUT_WATCH`, `TWO_MINUTE_WARNING`.

- [ ] **Step 1: Write the tests.**
  - **`DetectorMemoryTest`:**
    - `olderVersionDoesNotOverwrite` (write v5, then v4 → read gives v5's memory; a stalled old worker)
    - `missingIsNullMemory`
    - `ttlIsEightDays`
  - **`Stage1WorkerTest`** (Kafka + Redis; read the notifications topic):
    - `touchdownAndLeadChangeOneMessage`
    - `duplicateVersionSendsNothing`
    - `crashBeforeMemoryWriteResendsSameIds` (`writeIfHigher` throws once → re-read → same `messageId`s; through `Delivery`, one push per user)
    - `rereadFromStartSendsNoSecondPush` (reset the group's offsets to the beginning)
    - `redisRollbackRedetectsSameIds` (overwrite `det:` with the previous play's stored memory → the next play's messages have the ids already sent)
    - `twoThousandFollowersOneMessageEach` (kept)
    - `waitsWhileSubBuiltMissing` (kept, via `Stage1WaitsForSetsTest`)
  - **`SimulatorRunsEndToEndTest.finalArrivesAfterTheGoal`:** an NHL game fed straight to `processEvent`; the last play has a home goal in OT and status FINAL. For 3 users following the home team, the gateway channel shows the `GOAL` push, then the `FINAL` push.
  - **`AlertCatalogTest.unbuiltAlertsAreComingSoon`**
- [ ] **Step 2: Run** `mvn -q test -Dtest='DetectorMemoryTest,Stage1WorkerTest,Stage1WaitsForSetsTest,SimulatorRunsEndToEndTest,AlertCatalogTest'` → FAIL.
- [ ] **Step 3: Implement.** Wire `Stage1WorkerMain`: `new DetectorMemory(pool)`, `Detectors.standard(new GameLabels(Teams.load()))`, `new MomentFanout(pool, new AlertAudience(), labels)`. Remove `STAGE1_LAST_PREFIX` and the deleted classes.
- [ ] **Step 4: Run** `make test` → PASS. `twoRunsReachTheSameUserTwice` now runs on the new path.
- [ ] **Step 5: Rebuild the laptop cluster:** `make down && make cluster up`. Then `make simulate`, and check one follower's history by hand: `TOUCHDOWN` and `TRY_RESULT` for the NFL game, with `periodLabel` filled.
- [ ] **Step 6: Commit** `git commit -m "Stage 1 runs the detectors: det: memory, most-specific audience, one push per moment"`

### Task 11: Smoke test and persistence check

**Files:**
- Modify: `scripts/smoke-test.sh`, `scripts/persistence-check.sh`

**Interfaces:**
- Consumes: the wave 1 simulator matchups (Task 9): MLB `BOS`–`HOU`, NFL `KC`–`SF`.

- [ ] **Step 1: Smoke test.**
  - Replace every `game=game-BOS-HOU` query with `team=mlb-hou`. Bob's check stays: a `GAME_START` and a `RUN_SCORED` within the wait, which gets longer if a wave now takes ~90 s.
  - Add Carol, who follows `nfl-kc`: her history has a `TOUCHDOWN` headline and, later in time, a `TRY_RESULT`.
  - Add Dave, who follows `nfl-kc` and PATCHes `TOUCHDOWN` off: his history has no `TOUCHDOWN` headline or extra, and he still gets the `FINAL`.
  - The second-run part counts `GAME_START`s by `team=mlb-hou`. It now passes because the second run has new game ids.
- [ ] **Step 2: Persistence check.** `starts()` queries `team=mlb-hou`. The same-user replay must see one more start after the rebuild.
- [ ] **Step 3: Run** `make down && make cluster up smoke` → `SMOKE OK`. Then `scripts/persistence-check.sh` → `PERSISTENCE OK`.
- [ ] **Step 4: Commit** `git commit -m "Smoke and persistence checks for wave 1 alerts and per-run simulator ids"`

### Task 12: Docs and quiz

**Files:**
- Modify: `docs/api/openapi.yaml` (`Notification.id`: "the headline alert's id, e.g. `nfl-401872948:SCORE:away:3`"; example updated; `periodLabel`/`clock` now filled), `docs/api/websocket.md` (example ids)
- Modify: `CLAUDE.md` and `AGENTS.md`. Keep them identical except the AI-features lines that already differ.
  - Remove open problem 1.
  - Update "What the system does".
  - Diagram: Stage 1 runs the detectors with `det:` memory and builds the audience with the most-specific follow.
  - Add a "New in Phase 3.3" bullet: `alerts/`, `det:`, `:ANY` and inning sets, `make replay-detectors`, simulator ids.
  - Update the test count and the status line.
- Modify: `DESIGN.md` (build order 3.3 **Built**, date)
- Modify: `docs/study/phase-3-quiz.md` (3.3 section, same style as 3.1/3.2)

- [ ] **Step 1:** Write the docs.
- [ ] **Step 2:** `make test` → PASS. Check the test count.
- [ ] **Step 3: Commit** `git commit -m "Docs: Phase 3.3 alerts wave 1"`
