# Phase 3 quiz

Interview-style questions on Phase 3, one section per sub-phase. Each question has a model answer under a fold. Try answering out loud before you open it.

To take it: ask Claude, "quiz me on Phase 3" (or "on 3.1"). Questions marked **Review** come first, then **Not yet taken**.

## 3.1 Kafka pipeline

| # | Topic | First run |
|---|---|---|
| 1 | Why Kafka and not Redis Streams | **Not yet taken** |
| 2 | What partitions and consumer groups buy here | **Not yet taken** |
| 3 | The crash window between the script and Kafka, and why "retry" didn't close it | **Not yet taken** |
| 4 | The outbox | **Not yet taken** |
| 5 | One unsent play per game, and why not a lock | **Not yet taken** |
| 6 | The two clock heals | **Not yet taken** |
| 7 | Why `version` is separate from `seq` | **Not yet taken** |
| 8 | Exactly once in effect | **Not yet taken** |
| 9 | Why `s1:last` is "set if higher" | **Not yet taken** |
| 10 | `earliest`, and why dedup outlives the topic | **Not yet taken** |
| 11 | `conn:<userId>`: routing to the right gateway | **Not yet taken** |
| 12 | What a Kafka outage does | **Not yet taken** |
| 13 | Shutting a gateway down gracefully | **Not yet taken** |
| 14 | What the chaos check proved, and what it didn't | **Not yet taken** |

---

### 1. Why Kafka and not Redis Streams

> The course version used a Redis Stream and it worked. Throughput wasn't a problem. Why switch?

<details>
<summary>Model answer</summary>

What was missing wasn't speed, it was **lanes with an owner that moves**. A game's plays have to be handled in order, so one worker must own a game at a time, and when that worker dies, someone else must take its games over. Kafka does exactly that: a topic is split into partitions (lanes), a game's id always hashes to the same lane, and a consumer group gives each lane to one worker and moves it when a worker stops heartbeating. With Streams you can hand-shard into 12 stream keys, but then you write the "who owns which shard, and what happens when they die" logic yourself, which is the leader-election project from the fall, inside the app. Splitting the log out of Redis also keeps a backlog from competing with session lookups.

**Model answer:** "Throughput was fine on Streams. What I couldn't get was key-based lanes with automatic reassignment when a worker dies. Building that myself means writing a coordinator; Kafka's consumer groups are that coordinator."

</details>

---

### 2. What partitions and consumer groups buy here

> `game-events` has 12 partitions keyed by game id; `notifications` has 48 keyed by user id. What does each choice give you?

<details>
<summary>Model answer</summary>

**Key by game on `game-events`:** every play of a game lands in one lane, and a lane is read in order by one Stage 1 worker. So a game's plays are handled in order without any lock. **Key by user on `notifications`:** a user's messages share a lane, so Stage 2 delivers them in order, while different users spread across 48 lanes and are delivered in parallel. That's why there are two stages: one game's audience can be huge, and delivering it all from the game's single Stage 1 worker would cap the speed. **Consumer group:** the workers of a stage share the lanes; a dead worker's lanes move to the others. The partition count is the most workers that can do useful work, and it can't change later without moving games to different lanes.

**Model answer:** "Game id as the key keeps a game's plays in one lane, in order, owned by one worker. User id as the key on the second topic keeps each user's notifications in order while spreading delivery over 48 lanes. The group reassigns lanes when a worker dies."

</details>

---

### 3. The crash window between the script and Kafka, and why "retry" didn't close it

> The script accepts a play and saves the new score. Then Event Processing sends it to Kafka. What if it crashes in between? The design doc said the produce would be "retried." Who retries it?

<details>
<summary>Model answer</summary>

Nobody, as first written. The poller keeps no memory: it compares what ESPN shows with the game record the script saved. The script already saved the new score before the crash, so on the next poll ESPN and the record agree and the poller sends nothing. The play is accepted but never reaches Kafka, and the followers never hear about it. That was the same hole as the course version (crash between the script and `XADD`), just moved. Finding it is what led to the outbox.

**Model answer:** "The retry was supposed to come from the poller, but the poller compares ESPN with the saved record, and the record already had the new score. So a crash after the script would lose the play silently. Something had to remember the play itself: that's the outbox."

</details>

---

### 4. The outbox

> What is the outbox, and how does it close the window?

<details>
<summary>Model answer</summary>

In the same atomic Lua script that accepts the play and writes the score, the play is also written into the game's outbox slot in Redis (`outbox:<gameId>`, holding the play and its `version`). Event Processing sends it to Kafka; when Kafka confirms (`acks=all`, so it's stored), a small script clears the slot, but only if it still holds that version. A retry loop on every Event Processing pod re-sends anything still in the outbox after 5 seconds. Because the play and the score are written in one step, Redis keeps both or loses both, so there's no state where the score moved and the play is forgotten. This is the transactional outbox pattern: "in the outbox" is claimed, "cleared" is produced.

**Model answer:** "The accept script writes the play to an outbox in the same atomic step as the score. Only Kafka's confirmation clears it, and a retry loop sends whatever is left after a few seconds. A crash can delay a play, never lose it."

</details>

---

### 5. One unsent play per game, and why not a lock

> Why does the script answer "pending" when a game already has a play in the outbox? Why not use a per-game lock instead?

<details>
<summary>Model answer</summary>

**The problem it fixes:** Stage 1 drops any play at or below the last version it handled for a game. Suppose play 10 is stuck in the outbox after a crash, and play 11 is accepted and sent. When the retry loop finally sends 10, Stage 1 has already handled 11 and throws 10 away. The play is lost, which is the very thing the outbox was for. So a game's plays must reach Kafka oldest first. **The rule:** while a game has an unsent play, the script refuses newer ones with "pending", and the poller sends them again next poll. Normally a play sits in the outbox for milliseconds, so this costs nothing. **Why not a lock:** a lock in Redis has a timeout. A pod can take the lock, stall (a long garbage-collection pause), lose the lock to its timeout, and then wake up and send its older play after another pod sent a newer one. The script can't be interleaved, because Redis runs one script at a time, so the rule holds with no timeout at all. The same rule also fixed a Phase 2 leftover: two plays accepted at once used to be published out of order, and the burst test caught it (version 3 before 2).

**Model answer:** "A late re-send of an older play would be dropped as old, so plays must go out in order. The script refuses a game's next play until its previous one is confirmed. A timed lock can't guarantee that because a stalled holder can outlive its lease; Redis's one-script-at-a-time can."

</details>

---

### 6. The two clock heals

> In the recordings, ESPN once showed 4:01 left in a period and then went back to 11:45. Why was that a problem, and what does the script do now?

<details>
<summary>Model answer</summary>

**Problem:** `seq` is "how far into the game", and the script only accepts a play with a bigger `seq` than the saved one. The 4:01 snapshot was accepted, so its `seq` became the bar, and every real snapshot from 11:45 down to 4:01 counted as older and was rejected. A goal in those minutes would have sent no alert. It happened in 2 of 23 NHL games. **Fixes:** the **goal heal** accepts an older-looking play in the same period if it has more points, because an older snapshot never has more points, so a slow poller can't abuse it. The **no-score heal** accepts the same period and score after it has kept arriving "older" for 30 seconds. The poller only sends when something changed, so in practice this is a status change at the same score (an end of period, a delay) that would otherwise be blocked until the next goal. Either one lowers the bar to the real clock. Both apply only to real game positions, not the simulator's run-ordered numbers. **Cost:** a goal taken back at the same moment as a slow overlapping poller can produce one false "scores!" alert.

**Model answer:** "A snapshot ahead of reality became the floor and blocked real plays. More points in the same period always means later, so that heals at the next score; a status change at the same score is let through after 30 seconds. The one risk is a goal reversal racing an overlapping poller."

</details>

---

### 7. Why `version` is separate from `seq`

> Each game now has a `version` counter as well as `seq`. Why two numbers?

<details>
<summary>Model answer</summary>

After the heals, an accepted play can have a **lower** `seq` than the play before it. Anything downstream that ordered by `seq` would break: Stage 1's "drop if old" would drop the healing play, and the UI would ignore a real update as stale. So the script keeps a per-game counter that goes up by one on every accepted play, in the same step as the score. `seq` decides *whether* to accept; `version` says *what came first*. Stage 1 dedups by it, ids after the script are `<gameId>:v<version>`, and the API's `version` is this counter.

**Model answer:** "One number decides acceptance, which can be healed and go down; a separate counter that only goes up orders what was accepted. Everything after the script uses the counter."

</details>

---

### 8. Exactly once in effect

> Kafka delivers at least once. How do users get each notification exactly once?

<details>
<summary>Model answer</summary>

At least once means Stage 2 will sometimes see a message twice, for example when a worker crashes after delivering but before committing its offset. With two separate steps, the order picks the failure: **mark done, then deliver** loses the notification if you crash in between; **deliver, then mark done** duplicates it. Stage 2 does both in **one Lua script**: check `notif:<messageId>:<userId>`, add the history entry, publish to the user's gateways, and only then claim the key. Redis runs it as one step, so nothing lands in between, and a redelivered message finds the claim and does nothing. The claim is last because Redis does **not** roll back a script that fails halfway: with the claim first, a failed history write would leave a claim and the retry would skip a notification nobody got (found in the final review, along with the client library silently swallowing errors from pipelined scripts). Kafka's at-least-once plus an idempotent, atomic consumer gives exactly once in effect. The honest limit: the publish to the gateway is fire-and-forget pub/sub, so a gateway dying at that instant misses the live push, but history has it.

**Model answer:** "At-least-once delivery plus an atomic, idempotent consumer. The dedup check, the history write, the publish, and the claim are one Lua script, claim last because Redis doesn't roll back a failed script, so a redelivery is a no-op and there's no gap between 'marked' and 'delivered'."

</details>

---

### 9. Why `s1:last` is "set if higher"

> Stage 1 records the last version it finished for each game. Why can't it just `SET` it?

<details>
<summary>Model answer</summary>

Because of a "zombie" worker. Worker A stalls and loses its lanes in a rebalance. Worker B takes over and finishes versions 6 and 7. Then A wakes up, finishes version 5, and would write `s1:last = 5`, lowering it. If Kafka later redelivered 6 or 7, Stage 1 would re-send their messages. Stage 2's dedup would absorb them, but the value would be wrong. A three-line script only writes when the new version is higher. Also, the version is recorded only **after** Kafka confirms every message for that play: recording first and crashing before the sends would skip the play for good.

**Model answer:** "A stalled worker can finish an older play after the new owner finished newer ones. Set-if-higher means it can't move the bookmark backwards. And I record it only after the messages are confirmed, so a crash re-sends rather than skips."

</details>

---

### 10. `earliest`, and why dedup outlives the topic

> Why set `auto.offset.reset=earliest`, and what did that force about the dedup keys' lifetimes?

<details>
<summary>Model answer</summary>

A consumer group with no saved bookmark starts from `latest` by default, so on a fresh install the first plays, sent before the workers joined, would simply be skipped. `earliest` makes a new group read from the start. The catch: any time Kafka forgets a group's bookmark (a renamed group, or idle longer than Kafka keeps bookmarks), the group re-reads the whole topic. That's harmless only if the dedup records outlive the data: `s1:last` lasts 8 days against `game-events`' 7-day retention, and `notif:` claims last 2 days against `notifications`' 1 day. Otherwise a re-read would re-send week-old touchdowns. It also explains the switch-over rule: on a reused laptop cluster, Stage 1's first start would re-alert everything produced before it existed, so the cluster was rebuilt.

**Model answer:** "earliest so a new group doesn't skip what was sent before it started; and every dedup record outlives its topic's retention, so a full re-read is wasted work, not duplicate alerts."

</details>

---

### 11. `conn:<userId>`: routing to the right gateway

> How does Stage 2 know which gateway to publish to, and what happens when a gateway dies?

<details>
<summary>Model answer</summary>

When a user's socket opens, the gateway adds itself to `conn:<userId>`, a Redis **sorted set** whose members are gateway ids (pod names) and whose scores are expiry times. It's a set because a user can be on two gateways (laptop and phone). The gateway renews its users every 30 seconds for 90 seconds, and the key itself also expires after 90 seconds, so a user who never returns leaves nothing behind. Stage 2's script drops expired entries and publishes on `gw:<gatewayId>` for each one left; each gateway subscribes only to its own channel. Times come from Redis's own clock, so pods with drifting clocks can't expire each other's entries. **A gateway that dies** stops renewing; its entries lapse within 90 seconds. Publishes to it in that window reach nobody, and history has them. The browser reconnects elsewhere, and that gateway adds itself at once. **Why bother:** with the old `notify:*` broadcast, every gateway read every notification, so adding gateways took no work off any of them.

**Model answer:** "A sorted set per user of gateway ids with expiry scores, renewed every 30 s for 90. Stage 2 publishes only to live entries. A dead gateway just stops renewing and falls out within 90 seconds."

</details>

---

### 12. What a Kafka outage does

> The broker goes down mid-game. Walk through what users see.

<details>
<summary>Model answer</summary>

The first play after the outage begins is accepted: the score moves, and the play waits in the outbox because Kafka can't confirm it. After that the game answers "pending", so its live score freezes and the poller keeps retrying. The producer gives up on a down broker within 2 seconds (`max.block.ms`), so the poller's request never hangs. Nothing accepted is lost. When Kafka comes back, the retry loop sends the waiting play first; then the next poll is accepted and carries everything that happened during the outage as one change, so three goals become one "scores!" with the current score. A test pauses the broker container and checks exactly this order.

**Model answer:** "One play is accepted and held in the outbox; the game then goes pending and its score freezes; nothing accepted is lost; on recovery the held play goes first and the next poll catches the game up in one step."

</details>

---

### 13. Shutting a gateway down gracefully

> Kubernetes is replacing a gateway pod. In what order does it shut down, and why that order?

<details>
<summary>Model answer</summary>

1. Remove this gateway from every connected user's `conn:` set, in one pipelined batch.
2. Close every socket with code 4000, which the client reads as "reconnect now".
3. Stop renewing, unsubscribe, and exit.

Removing first means Stage 2 stops publishing to a gateway that's about to disappear, instead of wasting publishes for up to 90 seconds. Closing with 4000 makes browsers reconnect right away, to another gateway, which adds itself. A notification in the small gap between removal and reconnect reaches nobody live but is in history. Checked on the cluster: deleting the gateway pod closed an open socket with 4000 and left no `conn:` entry.

**Model answer:** "Unregister from routing first, then close sockets with 4000 so clients reconnect elsewhere at once, then exit. Anything in the gap is in history."

</details>

---

### 14. What the chaos check proved, and what it didn't

> You killed workers during a simulated game. What did that show, and what's the honest limit?

<details>
<summary>Model answer</summary>

For a test user following three leagues, I compared the distinct notification messages Stage 1 wrote for them on the `notifications` topic with their history after the run. Killing Stage 1 gracefully, then hard-killing Stage 2 and Stage 1 mid-simulation, gave 305/305, 306/306, and 308/308: nothing lost, nothing doubled. **The limit:** no message was even sent twice, which means the kills landed between batches, so this run didn't exercise the mid-batch crash. That case is covered by automated tests: a worker that dies halfway through delivering a batch, and a Stage 1 worker that dies after sending but before recording. It's also why Phase 4 builds a real checker and a chaos suite that kills things repeatedly instead of once by hand.

**Model answer:** "Three kills, each user's history matched what Stage 1 sent exactly. But the kills fell between batches, so the mid-batch crash is proven by tests, not by that run; the Phase 4 checker is what makes this systematic."

</details>

## 3.2 Postgres

| # | Topic | First run |
|---|---|---|
| 1 | Why Postgres, when Redis worked in the course | **Not yet taken** |
| 2 | Commit to Postgres first, then Redis | **Not yet taken** |
| 3 | The two locks on a follow save | **Not yet taken** |
| 4 | Writing the follow's whole state, not a diff | **Not yet taken** |
| 5 | The `sub:built` marker | **Not yet taken** |
| 6 | Why Stage 1 pauses instead of stopping | **Not yet taken** |
| 7 | Rebuilding with a temporary key and RENAME | **Not yet taken** |
| 8 | Why history is its own consumer group | **Not yet taken** |
| 9 | `created_at` from Kafka, and `cleared_at` | **Not yet taken** |
| 10 | The BOS bug | **Not yet taken** |
| 11 | Why the laptop's Postgres password is fixed | **Not yet taken** |
| 12 | A frozen Postgres and the socket timeout | **Not yet taken** |

---

### 3.2-1. Why Postgres, when Redis worked in the course

> The course kept accounts, preferences, and history in Redis, and it worked. Why add a database?

<details>
<summary>Model answer</summary>

**Problem:** Redis was the only copy of a person's account. In the course that was fine; here, `make down` deleted every account, and on AWS the whole environment is destroyed on purpose (decision 9). **Fix:** Postgres is the source of truth for accounts, follows, and history; Redis keeps what is fast and rebuildable: sessions, the live score, the `sub:*` sets, pub/sub. **Why this direction:** Redis can be rebuilt from Postgres; Postgres can't be rebuilt from Redis. And the data is now relational: a user has follows, a follow has switches, history is paged and filtered, which is what SQL is for.

**Model answer:** "Redis was the only copy of accounts, so deleting the cluster deleted users. Now Postgres is the truth and Redis is a cache I can throw away and rebuild."

</details>

---

### 3.2-2. Commit to Postgres first, then Redis

> A follow save writes Postgres and the Redis sets. Why that order, and what happens when the Redis write fails?

<details>
<summary>Model answer</summary>

**Problem:** two stores can't be updated atomically. **Fix:** commit Postgres first; then write Redis; if Redis fails, delete the `sub:built` marker so the sets are rebuilt from Postgres. **Why not the other order, or "undo Postgres if Redis fails":** if Redis were written first and the commit then failed, Redis would hold a follow that doesn't exist, and undoing a Redis write can fail too. With Postgres first, the only bad state is "Postgres has it, Redis doesn't", and one action, a rebuild, repairs it. If even the marker delete fails (Redis unreachable), user-pref remembers it owes one and retries every 30 s; a daily rebuild bounds the worst case.

**Model answer:** "The truth is written first. Any failure after that leaves Redis behind Postgres, never ahead of it, and 'behind' is fixed by rebuilding."

</details>

---

### 3.2-3. The two locks on a follow save

> Each save takes two Postgres advisory locks. What does each one prevent?

<details>
<summary>Model answer</summary>

**The rebuild lock, held shared by saves and exclusive by a rebuild.** Without it: the rebuild reads Bob's follow, Bob unfollows (commit, `SREM`), then the rebuild renames its copy over the live set and Bob is back. **The user's lock.** Without it: two tabs save the same follow; tab A commits, tab B commits, B's Redis write lands, then A's, so Redis matches A while Postgres matches B. Holding the user's lock from before the transaction until the Redis write finishes makes one user's saves happen one at a time, in commit order. Both are always taken in the same order (rebuild lock, then user lock), so two saves can't deadlock. They are session-level locks: they outlive the transaction, so they still cover the Redis write after the commit. A test replaced the user lock with a no-op and the two-tabs test failed.

**Model answer:** "One lock keeps a rebuild from resurrecting an unfollow; the other keeps one user's two tabs from reaching Redis in the opposite order of their commits."

</details>

---

### 3.2-4. Writing the follow's whole state, not a diff

> After a PATCH, why does Redis get every switch's state instead of just the switches that changed?

<details>
<summary>Model answer</summary>

Because the whole state is **idempotent**: writing it twice gives the same sets, so a retry, a duplicate, or a rebuild never conflicts with it, and Redis never depends on what it held before. A diff ("add to LEAD_CHANGE") is only right if applied exactly once, on top of exactly the state it was computed from. The PATCH merge itself happens in SQL (`alerts = alerts || patch`), so there is no read-modify-write in Java either.

**Model answer:** "The state can be applied any number of times and the result is the same. A diff can't."

</details>

---

### 3.2-5. The `sub:built` marker

> What is `sub:built`, who deletes it, and who reacts when it's missing?

<details>
<summary>Model answer</summary>

It says "the sets match Postgres". It goes missing when Redis comes back empty (the marker is gone with the sets), when a save's Redis write fails (the save deletes it), or when someone flushes Redis. Two things react: **user-pref** checks every 30 s and rebuilds the sets from Postgres, then sets the marker; **Stage 1** checks it once per batch and waits while it's missing, because an audience built from empty sets would silently lose alerts. One signal, three causes, one repair.

**Model answer:** "It's the 'the cache is trustworthy' flag. Missing means user-pref rebuilds and Stage 1 holds plays until it's back."

</details>

---

### 3.2-6. Why Stage 1 pauses instead of stopping

> While the marker is missing, Stage 1 doesn't handle plays. Why not just stop polling Kafka?

<details>
<summary>Model answer</summary>

**Problem:** a consumer that stops calling `poll()` for longer than `max.poll.interval.ms` is thrown out of the group, and its lanes move (a rebalance); a loop that stops beating fails `/health` and Kubernetes restarts the pod. A long rebuild would cause both. **Fix:** rewind the batch (nothing committed), `pause()` every assigned lane, and keep polling and beating. A paused consumer's `poll()` returns nothing but keeps it in the group. When the marker is back, `resume()` and carry on from the rewound position. Lanes gained in a rebalance are paused too. A test held the marker away for twice `max.poll.interval.ms`: no rebalance, healthy, and the play arrived exactly once afterwards.

**Model answer:** "Paused, not stopped: the consumer stays in the group and healthy, it just doesn't take records. Alerts are late, not lost."

</details>

---

### 3.2-7. Rebuilding with a temporary key and RENAME

> Why doesn't the rebuild just delete the sets and refill them?

<details>
<summary>Model answer</summary>

Between the delete and the refill, Stage 1 could read an empty set and send an alert to nobody. The rebuild writes each set under `sub:tmp:<buildId>:…` and `RENAME`s it over the live name; `RENAME` is atomic, so a reader sees the old set or the new one, never half of one. A test read a followed set's size in a tight loop through 20 rebuilds and never saw zero. Leftover temporary keys from a crashed build are deleted at the end of the next one.

**Model answer:** "Build beside, swap in one step. Readers never see a half-built set."

</details>

---

### 3.2-8. Why history is its own consumer group

> In the course, the worker that delivered a notification also wrote history. Why split them?

<details>
<summary>Model answer</summary>

**Problem:** if Stage 2 pushes and also inserts into Postgres, a slow or stopped Postgres stops it from finishing a batch, so it can't commit, so pushes stop too: a history outage silences alerts. **Fix:** history writers are a second consumer group on the same `notifications` topic. Each group has its own bookmark, so Stage 2 pushes at full speed while history falls behind and catches up. The insert is one statement per batch with `ON CONFLICT (user_id, message_id) DO NOTHING`, so a re-read batch inserts nothing new. Decision 3 anticipated this: each delivery channel can be its own group.

**Model answer:** "Two groups, two bookmarks: pushes never wait for Postgres, and history catches up after an outage without duplicates."

</details>

---

### 3.2-9. `created_at` from Kafka, and `cleared_at`

> Why isn't a history row's time just the insert time? And why does clearing history record a time?

<details>
<summary>Model answer</summary>

**`created_at`:** after a Postgres outage, the writer inserts an hour of history at once. With insert time, those rows would jump ahead of newer ones and count as unread. The Kafka timestamp is when Stage 1 sent the message, so catch-up keeps the real order; if Stage 1 sends a message twice, the first copy (and its time) wins. **`cleared_at`:** "clear all" deletes the rows, but the writer may still be catching up and insert older rows afterwards. Reads hide rows older than `cleared_at`, so cleared alerts don't come back. A test also found that clear must delete *all* the user's rows, not only those before Postgres's `now()`: `created_at` is Stage 1's clock, which can run slightly ahead.

**Model answer:** "Time comes from when the alert happened, not when it was saved, and 'cleared' is a moment, not just a delete, because history can arrive late."

</details>

---

### 3.2-10. The BOS bug

> What was wrong with `sub:team:BOS`, and how does the smoke test prove it's fixed?

<details>
<summary>Model answer</summary>

Team ids were bare abbreviations, so `BOS` was the Celtics, the Red Sox, and the Bruins: following one followed all three. Team ids are now league-qualified (`nba-bos`, `mlb-bos`, `nhl-bos`), built in one place (`TeamIds.of`), and the sets with them (`sub:team:nhl-bos:GOAL`). In the smoke test, Alice follows `nhl-bos` while the simulator plays two Boston games in other leagues (`game-LAL-BOS`, NBA; `game-BOS-HOU`, MLB). Under the old ids she'd hear both; her history must stay empty.

**Model answer:** "An abbreviation isn't an id across leagues. Ids are league-qualified, and a test follows the Bruins during a Celtics and a Red Sox game and expects silence."

</details>

---

### 3.2-11. Why the laptop's Postgres password is fixed

> On AWS the chart generates the Postgres password. Why not on the laptop?

<details>
<summary>Model answer</summary>

Postgres reads its password only when it first creates its data folder. On the laptop that folder is on the Mac and survives `make down`; the Kubernetes Secret doesn't. A generated password would change with every new cluster, while the folder kept the old one, and no service could log in. So the laptop uses a fixed password in `values-local.yaml`. Postgres is only reachable inside the cluster, behind a network policy, so a known laptop password costs nothing. On AWS the chart generates one and keeps it across upgrades with Helm's `lookup`.

**Model answer:** "The data outlives the cluster but the Secret doesn't, and Postgres only reads the password once. The persistence check caught exactly that kind of mismatch."

</details>

---

### 3.2-12. A frozen Postgres and the socket timeout

> The history writer's test froze Postgres (`docker pause`). What did that reveal?

<details>
<summary>Model answer</summary>

A query against a frozen database never returns: the TCP connection stays open and nothing answers. The writer's loop would hang instead of failing the batch and retrying, and its `/health` would eventually fail and restart the pod, with the same hang waiting. **Fix:** a JDBC socket timeout (30 s by default) on every pool, so a stuck query fails, the batch is rewound, and the loop retries. After unpausing, every message was in history exactly once, in lane order.

**Model answer:** "Timeouts turn 'stuck forever' into 'failed, retry'. Without one, a frozen dependency freezes you too."

</details>

---

## 3.3 Alerts, wave 1

| # | Topic | First run |
|---|---|---|
| 1 | Why the detectors run in Stage 1 | **Not yet taken** |
| 2 | Why every position change is a play | **Not yet taken** |
| 3 | `det:<gameId>`: written last, set if higher | **Not yet taken** |
| 4 | The baseline: why a game's first play alerts nobody | **Not yet taken** |
| 5 | Alert ids: a counter, not the score, not the version | **Not yet taken** |
| 6 | The most specific follow wins | **Not yet taken** |
| 7 | One push per moment | **Not yet taken** |
| 8 | Why the final is its own push, and arrives last | **Not yet taken** |
| 9 | The NFL try, and the two-play clock rule | **Not yet taken** |
| 10 | Overturns versus ESPN glitches | **Not yet taken** |
| 11 | What replaying the recordings caught | **Not yet taken** |
| 12 | Why the simulator gets new game ids every run | **Not yet taken** |

---

### 3.3-1. Why the detectors run in Stage 1

> "Close game late" isn't in ESPN's feed. Where is it worked out, and why there?

<details>
<summary>Model answer</summary>

**Problem:** ESPN gives facts (score, clock, status); moments like a lead change come from comparing this snapshot with the last one, so something must remember each game. **Fix:** Stage 1 runs small detectors, each a pure function of (memory, play) → (alerts, new memory). **Why there:** `game-events` is keyed by game id, so one Stage 1 worker owns a game's lane and sees its plays in order (decision 4); that ordering is exactly what a "compare with the previous play" detector needs. The poller stays dumb, and the Lua script stays the score authority only.

**Model answer:** "Detection needs every play of a game, in order, in one place. Kafka's per-game lanes give Stage 1 exactly that, so the detectors live there as pure functions I can unit-test."

</details>

---

### 3.3-2. Why every position change is a play

> Before 3.3 the poller only sent starts, scores, finals, and status changes. What broke, and what was the fix?

<details>
<summary>Model answer</summary>

**Problem:** "Close game late" needs the clock crossing 5:00; a failed NFL try needs the clock running again; a period end whose short status fell between polls needs the period number. None of those reached Stage 1 until the next score. **Fix:** the poller sends a `GAME_SYNC` whenever the snapshot's `seq` changed, about one play per game per 5-second poll. **Why it's fine:** ~10 plays a second at a busy peak is nothing for Kafka, the poller still knows nothing about alerts, and the topic becomes the game's full history (useful for Phase 4's replay and 3.4's live updates).

**Model answer:** "Detectors can only see what arrives. Sending every position change costs a few plays a second and keeps the poller ignorant of alert rules."

</details>

---

### 3.3-3. `det:<gameId>`: written last, set if higher

> When does Stage 1 save a game's detector memory, and why not earlier?

<details>
<summary>Model answer</summary>

**Problem:** If the memory were saved before the notifications were confirmed and the worker crashed, the re-read would compare against memory that already includes the goal, and the goal alert would be lost. **Fix:** read memory, detect, send, wait for every confirmation, then write `det:<gameId>` (version + memory) with a script that only writes a higher version. **Why:** a crash before the write means the play is re-read against the same memory, so the same alerts with the same ids, and Stage 2 drops the copies. Set-if-higher stops a stalled old worker from putting older memory back after the lane moved.

**Model answer:** "Memory is written after the sends are confirmed, so a crash replays the same detection with the same ids, and dedup absorbs the copies."

</details>

---

### 3.3-4. The baseline: why a game's first play alerts nobody

> Stage 1 has no memory for a game. What does the first play do?

<details>
<summary>Model answer</summary>

**Problem:** With no previous state there's nothing to compare: a game first seen at 0–3 would look like three goals, and a game seen mid-4th like a game start. **Fix:** the first play only becomes the baseline: every once-per-game condition already true (started, periods ended, close game late holding) is marked fired. **Why:** losing memory (expiry, a Redis wipe) then costs at most one moment and never sends stale ones.

**Model answer:** "No memory means no comparison, so the first play just sets the baseline. A lost memory can miss a moment, never invent one."

</details>

---

### 3.3-5. Alert ids: a counter, not the score, not the version

> A score alert's id is `nhl-1:SCORE:home:3`. What is the 3, and why not the score or the play's version?

<details>
<summary>Model answer</summary>

**Problem:** Built from the score, an overturned goal (3 → 2) followed by a real goal makes "the 3rd goal" twice, and dedup drops the real one. Built from the version, a re-detection after a Redis rollback arrives on a newer play with a new id, so the user gets it twice. **Fix:** the number is the team's count of scoring alerts fired, kept in the detector memory, never lowered. **Why:** it's unique per real alert, and it rolls back together with the memory, so a re-detection gets the same id and Stage 2's `notif:<alertId>:<user>` drops it.

**Model answer:** "The id counts alerts fired, which never goes down. That survives overturns, and it rolls back with the memory, so re-detections keep their ids."

</details>

---

### 3.3-6. The most specific follow wins

> A user follows the Bears with touchdowns on, and this Bears game with touchdowns off. Do they get the touchdown, and how is that computed?

<details>
<summary>Model answer</summary>

**No.** The game follow decides. Per alert, inside Redis: game:s ∪ ((teamH:s ∪ teamA:s) − game:ANY) ∪ (league:s − game:ANY − teamH:ANY − teamA:ANY). **Why `:ANY`:** "follows this game" must include users with the alert off, and the per-alert sets don't hold them; `sub:<kind>:<target>:ANY` does. **Why in Redis:** a handful of SUNIONSTORE/SDIFFSTORE per alert instead of one lookup per follower, and the union de-duplicates users reached through several follows.

**Model answer:** "Set math in Redis: game followers decide for themselves, team followers next, league followers only if they follow neither. The ANY sets say who follows a target at all."

</details>

---

### 3.3-7. One push per moment

> A touchdown also gives the lead. How does a user get one push, and how does that scale to a huge audience?

<details>
<summary>Model answer</summary>

**Fix:** one audience set per alert; the union is paged with SSCAN (1,000 at a time); per page, one SMISMEMBER per alert says which alerts each user gets. Their highest-priority alert is the headline, the rest are extras, and the push id is the headline's alert id. **Why:** memory stays one page however big the audience, and bodies are built once per distinct alert combination, not per user.

**Model answer:** "Page the union, ask Redis which alerts each user is in, headline the most important, attach the rest. One page in memory at a time."

</details>

---

### 3.3-8. Why the final is its own push, and arrives last

> An overtime goal ends the game. What does the user get, and in what order?

<details>
<summary>Model answer</summary>

Two pushes: the goal, then "Final (OT)". The final is never merged (decision 15). **Order:** Stage 1 sends every moment message before any final message; both are keyed by user id, so they land in the same `notifications` lane in that order, and Stage 2 handles a lane one message at a time. Tested end to end through Kafka, Stage 2, and a gateway channel.

**Model answer:** "Same user, same lane; the moment is produced first, so it is delivered first."

</details>

---

### 3.3-9. The NFL try, and the two-play clock rule

> After a touchdown, when is the extra point "no good"?

<details>
<summary>Model answer</summary>

The try is pending after +6. +1 or +2 from the scorer: good (a +2 is a conversion, not a safety). No good when the other team scores, the period ends, or the clock has been below the touchdown's clock for **two plays in a row**. **Why two:** one snapshot can carry a wrong clock (the recordings show 0:00 mid-period), and ESPN can post the point a few seconds after the clock restarts. The same rule guards "close game late". A try pending when the game ends says nothing: it wasn't attempted.

**Model answer:** "A small state machine in the memory; the clock must stay below the touchdown's time for two plays, because single snapshots lie."

</details>

---

### 3.3-10. Overturns versus ESPN glitches

> The score goes down. Overturn or glitch, and what does the user get?

<details>
<summary>Model answer</summary>

**Evidence:** in the recordings, real overturns drop at the stopped clock of the review and stay down; glitches drop while the clock runs and come back within 27 s. **Fix:** alerts count from the last believed score. A drop is believed after 60 s of game clock (MLB: two more plays), a new period, or the final; then "Rangers goal overturned" goes out with the corrected score, under the scoring switch, with the next counter id. A drop that comes back first sends nothing, and the goal is not announced again.

**Model answer:** "Don't believe a drop until it has held for a minute of game clock; glitches come back in under 30 s, real overturns stay."

</details>

---

### 3.3-11. What replaying the recordings caught

> The unit tests passed. What did running 60 hours of real ESPN data through the detectors find?

<details>
<summary>Model answer</summary>

Three real bugs: ESPN shows HALFTIME with the period already at 3, which sent "End of Q3" at the half; ESPN glitches that dropped a goal and restored it sent the goal twice; and the NHL scoreboard calls Utah `UTA` while the team list says `UTAH`, so Utah followers never got alerts. **Why it matters:** each came from data no hand-written test imagined; four golden timelines now pin the behavior on real games.

**Model answer:** "Real data finds the cases you didn't think of. Replay is cheap because the detectors are pure functions."

</details>

---

### 3.3-12. Why the simulator gets new game ids every run

> The old simulator reused `game-BOS-HOU`. Why did that have to change?

<details>
<summary>Model answer</summary>

**Problem:** alert ids no longer contain the play's version, so a second run of the same id would find last run's detector memory (start and final already fired), Stage 2's dedup keys, and history's primary key, and alert nobody. **Fix:** `<league>-sim<run start>-<slot>`, and the simulator now drives the real poller through a scripted `ScoreFeed`. **Test:** two runs in a row reach the same user twice.

**Model answer:** "Deterministic ids mean a reused game id is the same game. Each simulator run is a new game, so it gets a new id."

</details>

---

## 3.4 Live API

| # | Topic | First run |
|---|---|---|
| 1 | Who publishes `game_update`, and why there | **Not yet taken** |
| 2 | Subscribe first, then read | **Not yet taken** |
| 3 | Per game, not per user | **Not yet taken** |
| 4 | The unread badge as a counter | **Not yet taken** |
| 5 | The 0:00 freeze and the 10-second heal | **Not yet taken** |
| 6 | The schedule is not the score | **Not yet taken** |
| 7 | One builder for every `GameSummary` | **Not yet taken** |
| 8 | Why idle close isn't Jetty's timeout | **Not yet taken** |
| 9 | A tab that leaves while subscribing | **Not yet taken** |

---

### 3.4-1. Who publishes `game_update`, and why there

> Every accepted play becomes a score-card update in the browser. Which component publishes it, and what would go wrong anywhere else?

<details>
<summary>Model answer</summary>

**Problem:** Two Event Processing pods can accept plays of the same game back to back. If each published after its script returned, the two publishes could cross, and a tab would see the older score last. If Stage 1 published, scores would freeze whenever Stage 1 pauses (while `sub:built` is missing) or lags. **Fix:** the accept script itself runs `PUBLISH live:<gameId> {version, play}` as its last step. **Why:** Redis runs one script at a time, so publishes leave in version order and only for state that was saved.

**Model answer:** "The Lua script that accepts the play publishes it, in the same atomic step as the score write. Order comes for free from Redis being single-threaded, and the browser still drops any version it already has."

</details>

---

### 3.4-2. Subscribe first, then read

> A tab loads the game list over HTTP, then sends `subscribe_games`. What can it miss, and in what order does the gateway do things to prevent that?

<details>
<summary>Model answer</summary>

**Problem:** a play accepted between the HTTP fetch and the subscription would not show until the game's next play. If the gateway read `game:<id>` first and subscribed after, a play between those two steps is lost. **Fix:** the gateway sends `SUBSCRIBE live:<gameId>`, waits for Redis's confirmation (`onSubscribe`), and only then reads `game:<id>` and sends the current state. **Why:** after the confirmation every new play is delivered live; anything before it is in the read. Worst case the same version arrives twice, which the UI ignores. A test lands a play exactly between the confirmation and the read.

**Model answer:** "Subscribe, wait for the confirmation, then read. Duplicates are harmless because of versions; gaps are not."

</details>

---

### 3.4-3. Per game, not per user

> A Super Bowl has 100,000 people watching its score card. How many Redis messages does one touchdown's score update cost?

<details>
<summary>Model answer</summary>

**Problem:** sending score updates per user (like notifications) would mean 100,000 publishes per play. **Fix:** one channel per game, `live:<gameId>`. Each gateway subscribes once while any of its tabs shows the game (a reference count), and fans the message out to its own sockets. **Why:** the cost is one publish, delivered once per gateway: 100,000 viewers on 10 gateways is 10 deliveries. `PSUBSCRIBE live:*` would also work at today's few dozen games, but per-game subscriptions keep a gateway's work tied to what its tabs show as the game count grows.

**Model answer:** "One publish per play, one delivery per gateway that has a viewer. Viewers cost the gateway a socket write, not Redis anything."

</details>

---

### 3.4-4. The unread badge as a counter

> Why not count unread notifications in Postgres every time a push goes out? And what keeps the Redis counter honest?

<details>
<summary>Model answer</summary>

**Problem:** one count query per user per moment is a Postgres query storm at a big moment, and the count could miss the push itself, because history is written in batches by another consumer group. **Fix:** `unread:<userId>` in Redis, raised by Stage 2's delivery script in the same step as the delivery, so a redelivered message counts once. Seen and clear set it to 0 and tell every gateway holding the user. **Honesty:** it is only raised if it exists (INCR on nothing would start at 1, not the real count); a missing counter is filled from Postgres on the next connect; it expires daily, so any drift heals. Known gap: two narrow races can leave it off by one until then.

**Model answer:** "A cache of a Postgres count, kept by the same script that delivers, rebuilt from Postgres when missing. Exact enough for a badge, cheap at any scale."

</details>

---

### 3.4-5. The 0:00 freeze and the 10-second heal

> In the Bruins at Jets recording, ESPN showed 0:00 in the middle of the 2nd period for 90 seconds. What did that do to our score card, and what changed?

<details>
<summary>Model answer</summary>

**Problem:** `seq` treats 0:00 as later in the period, so the script accepted it, and every real snapshot after it (1:20, 1:15...) looked older and was rejected as stale. The no-score heal lowers the bar only after the same period and score keep arriving "older" for a while: 30 seconds, so the card showed 0:00 for 30 seconds after ESPN fixed itself. **Fix:** the window is 10 seconds. **Why that's safe:** 30 s was meant for two pollers overlapping in a deploy, but any accepted play resets the timer, so it only runs out when nothing newer arrives at all. Scores can't change on this path, so a wrong heal can't fire an alert. Waiting one poll to confirm a 0:00 wouldn't help: ESPN repeated it three times.

**Model answer:** "We measured it in the recordings: the glitch lasted 90 s, and our recovery added 30. Two polls of only-older plays is enough evidence, so the heal waits 10."

</details>

---

### 3.4-6. The schedule is not the score

> `/games?date=` shows next Sunday's games. Where do they come from, and why can't that path change a score?

<details>
<summary>Model answer</summary>

**Problem:** the poller only sees ESPN's current scoreboard, and `game:<id>` (written only by the accept script) expires a day after a final. **Fix:** a schedule loop in the ESPN poller fetches 11 UTC dates for 4 leagues every 15 minutes (44 requests) into `sched:<gameId>`. **Why it can't change a score:** it never writes `game:<id>`, sends no play, fires no alert. The game lists prefer `game:` for everything it has and use `sched:` only for games with no record (future games, old finals) and for venue and broadcast. A schedule-only game has version 0, so the first live update replaces it.

**Model answer:** "Two sources with one rule: the score authority's record wins; the schedule is a listing beside it."

</details>

---

### 3.4-7. One builder for every `GameSummary`

> The game list, the game page, and the live socket all describe a game. How do we keep them from disagreeing?

<details>
<summary>Model answer</summary>

**Problem:** three code paths building the same JSON would drift: a label, a status mapping, or a team logo fixed in one place but not the others. **Fix:** `games/GameSummaries` builds every `GameSummary`. A live update is turned into the same field map the script wrote for that play and goes through the same function, and a test asserts the two are equal. Labels come from the same `GameLabels` the alerts use, so a push and a score card say `Final/OT` the same way.

**Model answer:** "One function, and a test that a live update equals the summary of the record it produced."

</details>

---

### 3.4-8. Why idle close isn't Jetty's timeout

> The contract says the server closes a socket that has sent nothing for 60 seconds. Why write our own sweeper?

<details>
<summary>Model answer</summary>

**Problem:** Jetty's idle timeout resets on any traffic, including our own sends. A dead tab on a busy game keeps receiving `game_update`s, so Jetty would never call it idle, and the gateway would keep writing to a socket nobody reads. **Fix:** each session remembers when the client last sent anything; a sweeper every 10 s closes those silent for 60 s. Browsers ping every 25 s. **Also:** pub/sub threads, the socket's own thread, and the sweeper can all write to one tab, and Jetty refuses two blocking sends at once, so every send takes a per-session lock.

**Model answer:** "Idle means the client went quiet, not the connection. Only the client's messages count."

</details>

---

### 3.4-9. A tab that leaves while subscribing

> A tab sends `subscribe_games` and closes while the gateway is still waiting for Redis's confirmation. What stops the gateway from listening to that game forever?

<details>
<summary>Model answer</summary>

**Problem:** the subscribe is in flight; if the close only removed the tab and never unsubscribed, or unsubscribed before the subscribe was sent, the gateway could keep a channel nobody needs. **Fix:** tabs per game and the subscribe/unsubscribe decisions change together under one lock, and the channel only sends `UNSUBSCRIBE` when the last tab leaves. Both commands go down the same Redis connection in order, so an unsubscribe after the subscribe wins. After the wait, the gateway reads current state only for games the tab still shows. A test closes the tab between the confirmation and the read and checks Redis reports zero subscribers.

**Model answer:** "Reference counts under one lock, commands in order on one connection, and a re-check after any wait."

</details>
