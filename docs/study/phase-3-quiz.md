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

**Problem:** `seq` is "how far into the game", and the script only accepts a play with a bigger `seq` than the saved one. The 4:01 snapshot was accepted, so its `seq` became the bar, and every real snapshot from 11:45 down to 4:01 counted as older and was rejected. A goal in those minutes would have sent no alert. It happened in 2 of 23 NHL games. **Fixes:** the **goal heal** accepts an older-looking play in the same period if it has more points, because an older snapshot never has more points, so a slow poller can't abuse it. The **no-score heal** accepts the same period and score after it has kept arriving "older" for 30 seconds, so the stuck clock heals even if nobody scores. Either one lowers the bar to the real clock. Both apply only to real game positions, not the simulator's run-ordered numbers. **Cost:** a goal taken back at the same moment as a slow overlapping poller can produce one false "scores!" alert.

**Model answer:** "A snapshot ahead of reality became the floor and blocked real plays. More points in the same period always means later, so that heals at the next score, and the same score for 30 seconds heals without one. The one risk is a goal reversal racing an overlapping poller."

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

At least once means Stage 2 will sometimes see a message twice, for example when a worker crashes after delivering but before committing its offset. With two separate steps, the order picks the failure: **mark done, then deliver** loses the notification if you crash in between; **deliver, then mark done** duplicates it. Stage 2 does both in **one Lua script**: claim `notif:<messageId>:<userId>` with set-if-absent, add the history entry, and publish to the user's gateways. Redis runs it as one step, so all of it happens or none of it does, and a redelivered message finds the claim and does nothing. Kafka's at-least-once plus an idempotent, atomic consumer gives exactly once in effect. The honest limit: the publish to the gateway is fire-and-forget pub/sub, so a gateway dying at that instant misses the live push, but history has it.

**Model answer:** "At-least-once delivery plus an atomic, idempotent consumer. The dedup claim, the history write, and the publish are one Lua script, so a redelivery is a no-op and there's no gap between 'marked' and 'delivered'."

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
