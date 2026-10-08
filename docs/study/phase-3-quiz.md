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
