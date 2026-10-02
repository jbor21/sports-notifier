# Phase 2 quiz: Correctness and security

Interview-style questions on Phase 2: event ordering, the stateless poller, fan-out, and the security fixes. Each question has a model answer under a fold. Try answering out loud before you open it.

To take it: ask Claude, "quiz me on Phase 2." Questions marked **Review** come first, then **Not yet taken**.

| # | Topic | First run |
|---|---|---|
| 1 | Why the Lua script checks the event id before `seq` | **Not yet taken** |
| 2 | What the `seq` digits mean, and why score total is last | **Not yet taken** |
| 3 | Why `GAME_SYNC` exists | **Not yet taken** |
| 4 | Why the poller keeps no memory of its own | **Not yet taken** |
| 5 | Set operations instead of one `SISMEMBER` per user, and their cost | **Not yet taken** |
| 6 | Signup: why write the user first, then claim the name | **Not yet taken** |
| 7 | Why the first `X-Forwarded-For` entry can't be trusted | **Not yet taken** |
| 8 | What a network policy does, and how the first check fooled us | **Not yet taken** |
| 9 | Why the simulator's `seq` is `runId × 1000 + n` | **Not yet taken** |
| 10 | What can still go wrong before Phase 3 | **Not yet taken** |

---

## 1. Why the Lua script checks the event id before `seq`

> The script does three things: check whether the event id was already processed, check that `seq` is newer than the saved one, then claim the id and write the state. Why that order? What breaks if `seq` is checked first?

<details>
<summary>Model answer</summary>

A retry of an event that was already accepted has the same id **and the same `seq`**. If `seq` were checked first, the retry would fail the "newer than saved" test and be called *stale*. That loses information: "stale" means "an older moment", but this is "the same moment again". Phase 3 needs the difference. Its two-state claim (DESIGN.md decision 5) says "if the id is claimed but the Kafka produce never happened, produce again". That branch can only run if the script recognizes the retry as the same event.

The id check is a read, not a claim. The claim happens only after `seq` passes, so a stale event never burns an id. That property was the course's original TOCTOU fix, and it is kept. All three steps are one script, so nothing can run between the read and the claim.

**Model answer:** "Id first, so a retry is reported as a duplicate instead of stale; Phase 3's 'claimed but not produced, produce again' depends on that. The id check is read-only and the claim comes after the seq check, so stale events never consume an id. It's one Lua script, so the check and the claim can't be interleaved."

</details>

---

## 2. What the `seq` digits mean, and why score total is last

> `seq` for "NFL, 4th quarter, 2:00 left, 24–21" is `10403480000045`. Read it. Why is the score total the last block and not the first?

<details>
<summary>Model answer</summary>

The blocks, most significant first: phase `1` (live) · period `04` · half `0` (MLB only) · clock `34800` (36000 minus 1200 tenths left, so less time left is bigger) · outs `0` (MLB only) · stage `0` (in play; 1 delayed, 2 end of period, 3 halftime) · score total `045`.

Comparing two such numbers compares game progress field by field, because each field has a fixed number of digits. Score total only breaks ties when everything before it is equal, meaning the clock is stopped at the same spot: a touchdown (6–0) and then the extra point (7–0).

If the score came first, a correction would break the order. An overturned touchdown *lowers* the score, so the corrected snapshot would look "older" than the wrong one forever. With score last, a correction after the clock has moved has a bigger `seq` and is accepted with the lower score, which is the truth. The cost: a correction at exactly the same position is rejected. Phase 3's detectors handle corrections.

**Model answer:** "Each block is a field of game position with fixed width, so numeric order is game order. Score total is a tie-breaker for a stopped clock. Putting it first would make score corrections look like going backwards. The rule is 'state never goes back in game time', not 'score never goes down'."

</details>

---

## 3. Why `GAME_SYNC` exists

> The poller sends a fourth event type that notifies nobody. What problem does it solve?

<details>
<summary>Model answer</summary>

The poller decides what changed by comparing ESPN with the saved game record. On a fresh start (a new cluster, AWS just came up in Phase 5, or a record that expired), there is no record, so every game in progress and every finished game looks new. Without `GAME_SYNC`, followers would get "has started" for games that started an hour ago and "Final" for games that ended this afternoon.

`GAME_SYNC` lets the Lua script save the state as usual, but Event Processing never puts it on the stream, so fan-out never sees it. The same type covers status-only changes (halftime, a delay) and score corrections. The UI sees them, but they aren't alerts in Phase 2.

**Model answer:** "We only alert on changes we saw happen. First sight of a game, a status-only change, or a correction updates the record quietly. That makes a cold start, like the on-demand AWS bring-up, silent instead of a burst of stale alerts."

</details>

---

## 4. Why the poller keeps no memory of its own

> The course poller kept the last score per game in a `HashMap`. What went wrong, what replaced it, and what happens now when (a) the poller restarts, (b) a POST fails, (c) two pollers run at once?

<details>
<summary>Model answer</summary>

The `HashMap` was lost on every restart, so a restart re-sent "game started" for every live game. It was also updated *before* the POST, so a failed POST meant the play was marked seen and never sent.

Now the poller compares ESPN with the game record the Lua script wrote. The script writes it only when an event is accepted.

- **(a) Restart:** the record is in Redis, so nothing is re-sent.
- **(b) Failed POST:** the record didn't change, so the next poll (5 s later) sees the same difference and sends the same event again. Same position means the same `seq` and the same id, so the retry is safe. There's no separate retry loop.
- **(c) Two pollers:** both read the same record and may both POST the same event. The script accepts one, and the other is a duplicate. If one is a poll behind, its event is stale.

If Redis restarts, the record survives (append-only file on a persistent volume). If Redis comes back empty, every game is first-seen and quiet.

**Model answer:** "One source of truth, the record the script owns. The poller is stateless, so restarts are free, the next poll is the retry, and overlapping pollers are harmless because ids and seq come from ESPN's data."

</details>

---

## 5. Set operations instead of one `SISMEMBER` per user, and their cost

> Fan-out used to loop over candidates calling `SISMEMBER` for each. What does it do now, and what's the downside at very large scale?

<details>
<summary>Model answer</summary>

Now: `SUNIONSTORE fanout:<eventId>` over both teams', the game's, and the league's follower sets; `SINTERSTORE` with the set of users who enabled this event type; `EXPIRE 60` as a safety net; then `SSCAN` the result in pages of 1,000 and `DEL` it. That's a handful of Redis calls per event instead of one round trip per subscriber, and the union de-duplicates a user who follows both teams.

The downside is that Redis runs one command at a time. A `SUNIONSTORE` over millions of members runs as one command, and nothing else (sessions, scores) runs until it finishes. At friends scale that's microseconds. At millions of followers it's a noticeable pause, which Phase 4's load test should measure. The fixes would be chunking or moving the union elsewhere.

**Model answer:** "Redis builds the audience with union then intersect, so it's O(1) round trips instead of O(subscribers). It's single-threaded, so a giant union blocks everything for its duration. Fine now, measured later."

</details>

---

## 6. Signup: why write the user first, then claim the name

> Signup now writes `user:<id>`, then does `SET username:<name> <id> NX`, and deletes the user record if the claim fails. Why `SET NX`, and why this order?

<details>
<summary>Model answer</summary>

The course code did `GET username:<name>`, then later `SET`. Two signups arriving together both saw "free", and both saved. It's the same check-then-act race the professor deducted for on the score. Measured before the fix: **20 of 20** simultaneous signups for one name succeeded. `SET NX` checks and claims in one command, so exactly one wins.

Order: if the process dies between the two writes, "user first" leaves an orphan user record that nothing points to, which is harmless. "Name first" could leave a name claimed forever with no account behind it, and nobody could ever use that name.

**Model answer:** "SET NX makes check-and-claim atomic. Write the unreachable part first and the claim last, so a crash in between leaves garbage, not a locked name."

</details>

---

## 7. Why the first `X-Forwarded-For` entry can't be trusted

> The login throttle limits failures per client address. The first spec draft used the first `X-Forwarded-For` entry. What was wrong with that?

<details>
<summary>Model answer</summary>

Any client can send an `X-Forwarded-For` header. Each proxy *appends* the address it received the request from. So the first entry is whatever the client wrote, and only the last one was added by our proxy (Traefik), which also sets `X-Real-Ip`. Using the first entry, an attacker sends a new made-up address with every guess and never reaches the 20-per-address limit.

The gateway uses `X-Real-Ip`, else the last entry, else the socket address. A test sends 20 failures, each with a different fake first entry, and checks the 21st is still blocked. On AWS, with a load balancer in front of Traefik, the trusted hop moves, and Phase 5 has to set it.

**Model answer:** "XFF is client-writable; only the entry our own proxy appended is trustworthy. Trusting the first one turns a per-IP limit into no limit."

</details>

---

## 8. What a network policy does, and how the first check fooled us

> What do the network policies in the chart do? The first version of the smoke check passed for the wrong reason. Why?

<details>
<summary>Model answer</summary>

A NetworkPolicy selects pods by label and lists who may open connections to them. Once any policy selects a pod, everything not listed is refused. The chart starts with "deny all incoming" for every pod, then opens: Traefik to the gateway, the gateway to user-pref, game-state, and notification, the poller and simulator to event-processing, and our own pods to Redis. Outgoing traffic stays open, because ESPN is needed.

The smoke check starts a stranger pod and expects its call to user-pref to fail. A control run, a pod labeled as the gateway, also failed, instantly. k3s adds a new pod's address to the firewall's allow lists a moment after the pod starts, so an immediate call is refused regardless of policy. The check now waits 5 s, and it runs the control too: the gateway-labeled pod must get through, and the stranger must not.

**Model answer:** "Label-based firewall rules between pods: default deny, then the exact paths the app uses. A negative test needs a positive control. Without one, 'refused' might mean 'not set up yet', not 'forbidden'."

</details>

---

## 9. Why the simulator's `seq` is `runId × 1000 + n`

> The simulator reuses the same game ids on every run. Why not just count 1, 2, 3?

<details>
<summary>Model answer</summary>

Game records outlive a run. A second `make simulate` counting from 1 would be "older" than the first run's final `seq` and rejected as stale, so `make smoke` would fail every second time. `runId` is the run's start time in milliseconds, so every run is newer than every earlier run, and within a run `n` counts up. When two runs overlap, the later one wins. The value is about 1.8 × 10^15, under 2^53, so Lua's numbers compare it exactly.

**Model answer:** "Seq must be monotonic across runs, not just within one. Prefixing with the run's start time makes each run newer than the last, and it still fits Lua's exact-integer range."

</details>

---

## 10. What can still go wrong before Phase 3

> Name three failures the system still has after Phase 2, and what fixes each.

<details>
<summary>Model answer</summary>

- **Crash between the script and the stream write:** the event is accepted but never published. Phase 3's two-state claim (`claimed` → `produced`) plus a retry fixes it.
- **Worker dies mid-event:** the stream entry sits in the pending list forever. Kafka's consumer-group rebalance hands that partition to another worker, and per-user dedup stops a second notification.
- **Every gateway reads every notification** (`notify:*`). Phase 3 routes to the gateway holding the socket (decision 13).
- Also: accounts vanish with `make down` until Postgres, and EKS ignores the network policies unless a policy engine is enabled (Phase 5).

**Model answer:** "The script-to-publish window, stuck pending entries, and broadcast to all gateways. Kafka with the two-state claim, consumer-group rebalances, and per-gateway routing close them in Phase 3."

</details>
