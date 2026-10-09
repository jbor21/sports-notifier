# Phase 4 quiz

Interview-style questions on Phase 4, one section per sub-phase. Each question has a model answer under a fold. Try answering out loud before you open it.

To take it: ask Claude, "quiz me on Phase 4" (or "on 4.1"). Questions marked **Review** come first, then **Not yet taken**.

## 4.1 Replay and the correctness checker

| # | Topic | First run |
|---|---|---|
| 1 | Why the checker recomputes instead of reading Stage 1's output | **Not yet taken** |
| 2 | What the oracle shares with Stage 1, and what that costs | **Not yet taken** |
| 3 | Why follows are frozen, and why `updated_at` | **Not yet taken** |
| 4 | Why "zero lag" must hold for 10 seconds | **Not yet taken** |
| 5 | When a push missing from a socket is allowed | **Not yet taken** |
| 6 | Why every check is scoped to the run's game ids | **Not yet taken** |
| 7 | Why a self-test, and what each fault proves | **Not yet taken** |
| 8 | What the `sub:built` marker got wrong, and the fix | **Not yet taken** |
| 9 | What the first cluster run found | **Not yet taken** |
| 10 | Why the replay sets are per-game timelines | **Not yet taken** |

---

### 1. Why the checker recomputes instead of reading Stage 1's output

> Stage 1 writes every push to the `notifications` topic. Why not take that as "what should have been delivered" and compare it with what arrived?

<details>
<summary>Model answer</summary>

Because that only tests the half after Stage 1. If Stage 1 crashes and loses a moment, or sends one twice, or reads a `sub:*` set that lost a write, the `notifications` topic is already wrong, and a checker that trusts it agrees with the mistake. The checker goes back to the two sources of truth instead: the accepted plays on `game-events` (only plays the Lua script accepted ever reach it) and the follows in Postgres. From those it computes, single-threaded and with no failures, what every user should get. Then whatever went wrong between the log and the browser, Stage 1 included, shows up as a difference.

**Model answer:** "Comparing with Stage 1's output would only prove Stage 2 delivered what Stage 1 sent. The checker replays the accepted plays against the Postgres follows itself, so a Stage 1 crash or a stale Redis set shows up as a missing or extra push."

</details>

---

### 2. What the oracle shares with Stage 1, and what that costs

> The oracle calls the same detectors and the same combining function as Stage 1. Isn't that testing the code with itself?

<details>
<summary>Model answer</summary>

For those two parts, yes, and the spec says so. A detector bug would be in both, so the checker can't see it; the golden replays (four recorded games with hand-reviewed timelines) are what test detectors. Everything else is independent. The audience is computed in plain Java from Postgres follows (`Audience`), not read from the Redis sets Stage 1 uses. Dedup, routing, delivery, and history are checked against real arrivals. The combining step was pulled out into a pure function (`MomentPushes`) so the two can't drift apart by copy-paste. The checker is about the distributed part (who gets what, once, in order, through crashes), not about whether a goal is a goal.

**Model answer:** "It shares the detectors and the combining step on purpose: those are pure functions tested by golden replays. The audience, delivery, dedup and history are checked independently. I'd say plainly that a detector bug is out of the checker's reach."

</details>

---

### 3. Why follows are frozen, and why `updated_at`

> The checker refuses a run if any follow changed after T0. Why so strict, and why did the table need a new column?

<details>
<summary>Model answer</summary>

The expected pushes are a function of the follows. If a switch flips mid-run, "should this user have got the goal at 14:31?" depends on exactly when the flip reached Postgres versus Redis versus Stage 1. That's a real question, but it isn't the one this run asks, and an ambiguous expected set can hide a real loss. So follows are created before T0 and must not change. Comparing a snapshot at the start and at the end catches changes, deletes and additions, but not a switch flipped and flipped back; `updated_at` (set on every switch change) catches that. The one allowed change is the expiry job marking a finished game's follow to expire a day later, which doesn't change any audience during the run. Both T0 and the timestamps come from Postgres's clock, so there is one clock to compare.

</details>

---

### 4. Why "zero lag" must hold for 10 seconds

> The run ends when the feed is done, every game is final, and the consumer groups have zero lag. Why isn't one zero-lag reading enough?

<details>
<summary>Model answer</summary>

Lag is "end offset minus committed offset". It can read zero while work is still in flight: a worker has polled records but not committed them yet, a rebalance is moving lanes, or Stage 1 is still producing a fan-out whose records haven't landed. And Stage 2's last step is Redis pub/sub to the gateways, which happens after the commit point, so a socket can still be a few milliseconds from receiving a push. Requiring all three groups (Stage 1, Stage 2, history) to stay at zero on every partition for 10 seconds straight, with any blip resetting the clock, turns "probably done" into "done". A run that never settles fails with a timeout that names the games and partitions still busy.

</details>

---

### 5. When a push missing from a socket is allowed

> A user's tab drops for a few seconds and misses a push. Is that a failure?

<details>
<summary>Model answer</summary>

Not by itself: decision 13 says an offline user gets the notification in history, not on the socket. But "the tab dropped at some point" can't excuse every miss, or a real loss would hide behind any reconnect. The rule is tight: a push missing from a socket is allowed only if history has it, and the socket was disconnected at some time between that push's `notifications` record timestamp and the arrival of the first later push on that socket. One user's pushes are in one Kafka lane and Stage 2 delivers them in order, so if a later push arrived and the socket never dropped in between, the missing one was published to an open socket and lost. That's a finding.

</details>

---

### 6. Why every check is scoped to the run's game ids

> Synthetic users follow whole leagues. What stops live ESPN games from failing a run?

<details>
<summary>Model answer</summary>

Every replayed game gets a fresh id carrying the run id, `nhl-rp<runId>-401891803`. The oracle reads only plays with that marker, history is queried for those game ids only, and anything a socket hears about another game (live ESPN, an earlier run) is counted as "outside the run" and ignored. Fresh ids also mean each run starts from empty game state, so runs repeat without wiping Redis. Synthetic usernames carry the run id too (`ck_<runId>_<n>`), so cleanup deletes exactly that run's users, history, and counters, and nobody else's.

</details>

---

### 7. Why a self-test, and what each fault proves

> The checker passed. Why should anyone believe it?

<details>
<summary>Model answer</summary>

A checker that always passes is worth nothing, so `make check-selftest` breaks one thing per run through the real paths and requires the checker to report exactly that. **srem**: removes one user from one Redis `sub:*` set after T0, a lost Redis write. Stage 1 never sends that user's game start, and the checker must report it missing from history and socket. This is the end-to-end proof that a Redis/Postgres disagreement is caught, because the oracle reads Postgres. **dup**: republishes a push the user already got on their gateway's channel, and the checker must report a duplicate on that socket. **follow**: flips a switch and back after T0, and the freeze rule must catch it. Each fault run passes only if its finding appears and every finding is about the faulted user.

</details>

---

### 8. What the `sub:built` marker got wrong, and the fix

> Stage 1 waits while `sub:built` is missing. What was wrong with a marker that just exists?

<details>
<summary>Model answer</summary>

It said "the sets were built" but not "in this shape" or "by this Redis". Two bugs followed. **#16:** a `helm upgrade` that changes set shapes (3.3 added `ANY` and innings sets) keeps Redis and the marker, so the rebuild is skipped and Stage 1 reads missing sets until the daily rebuild. **#18:** user-pref noticed a Redis restart by comparing Redis's `run_id` with one it held in memory. If both restarted together there was nothing to compare, and an AOF restart can keep the marker but lose the last second of writes. The fix: the marker is a hash `{version, runId}`. A different `SHAPE_VERSION` or a `run_id` that differs from Redis's live one means rebuild, and Stage 1 waits unless the version matches. The run id lives in Redis, not in the process, so restarting both together is still caught.

</details>

---

### 9. What the first cluster run found

> The very first `make check` on the cluster failed with 837 wrong bodies. What was it, and who was wrong?

<details>
<summary>Model answer</summary>

Every one was an end-of-period push. The oracle's body had `"clock": null` (between periods there's no clock), but history's body had no `clock` key at all, because Stage 1 serializes with Gson's defaults, which leave nulls out. The API spec makes `clock` and `periodLabel` optional and nullable, so both forms mean the same push: the pipeline was within its contract, and the checker was too strict. The fix compares bodies with null members removed on both sides, with a test that a real value (say `"4:12"` vs. missing) still counts as different. The lesson: an independent oracle finds representation differences as well as logic bugs, and each finding has to be judged against the contract, not "fixed" on whichever side is easier.

</details>

---

### 10. Why the replay sets are per-game timelines

> The recorder saved whole scoreboards every 30 seconds. Why not replay them as recorded?

<details>
<summary>Model answer</summary>

Recorder time is mostly waiting. A game's recording starts hours before kickoff, NHL intermissions are 18 minutes with nothing changing, and the four golden games were recorded on three different days, so replayed as recorded they'd never overlap, and the chaos runs need many lanes busy at once. So `pack-replay.py` gives each game its own timeline: it keeps a snapshot only when that game changed, cuts any gap over 60 s to 60 s, and starts each game at an offset given on the command line. The `evening` set is 10 games across four leagues in about 3 hours of replay time, 19 minutes at 10×. The cost: the script's wall-clock heals see different timing at 10× than live, which doesn't matter to the checker, because its truth is whatever the script accepted.

</details>
