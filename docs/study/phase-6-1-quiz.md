# Phase 6.1 quiz

Interview-style questions on Phase 6.1, the chaos suite on EKS. Each question has a model answer under a fold. Try answering out loud before you open it.

To take it: ask Claude, "quiz me on Phase 6.1". Questions marked **Review** come first, then **Not yet taken**.

| # | Topic | First run |
|---|---|---|
| 1 | Why replayed games reached real users (#47) | **Not yet taken** |
| 2 | Why a missing `synthetic:users` set reaches nobody | **Not yet taken** |
| 3 | Why the checker's oracle needed no change for #47 | **Not yet taken** |
| 4 | What a machine dying does that a crashed pod doesn't | **Not yet taken** |
| 5 | The unanswered-ping rule, and why the gap starts at the last frame | **Not yet taken** |
| 6 | Why count silent excuses separately | **Not yet taken** |
| 7 | How a crash is made on EKS, and how we know it was a crash | **Not yet taken** |
| 8 | Why the cut might need `ss -K` on EKS | **Not yet taken** |
| 9 | What the three-broker crash proves | **Not yet taken** |
| 10 | How node-loss picks its machine | **Not yet taken** |
| 11 | Why the replacement must land in the same zone | **Not yet taken** |

---

### 1. Why replayed games reached real users (#47)

> A replay uses made-up game ids. How could its pushes still reach a real account?

<details>
<summary>Model answer</summary>

The game id is new every run (`nfl-rp<runId>-…`), but the teams and league are real (`nfl-buf`, `NFL`). Stage 1's audience comes from who follows the game, its two teams, or its league, so anyone who follows the Bills got the replay's touchdowns in their history and badge. On the laptop that polluted the developer's own account; on EKS it would send fake alerts to real users. The fix cuts a replayed game's audience down to the checker's users: one `SINTERSTORE` with `synthetic:users` per alert, inside the pipeline that already builds the audience, so there is no lookup per push. `/games` also hides replays, so real users don't see them in their list.
</details>

### 2. Why a missing `synthetic:users` set reaches nobody

> If Redis restarts empty, the set is gone until the rebuild. Why is "replays reach nobody" the right failure?

<details>
<summary>Model answer</summary>

There are two ways to fail: a replay reaches nobody, or it reaches real users. The first costs a checker run: it fails loudly with pushes missing, and you rerun after the rebuild. The second sends made-up alerts to real people, which is the bug being fixed. An intersection with a missing set is empty in Redis, so the safe direction comes for free. The rebuild refills the set from Postgres (`username LIKE 'ck\_%'`), and the shape version went to 3, so a Redis from before this change is rebuilt instead of trusted. Stage 1 also waits while `sub:built` is missing, which covers the window after an empty restart.
</details>

### 3. Why the checker's oracle needed no change for #47

> Stage 1 now drops real followers from replays. Doesn't the checker's expected list have to change too?

<details>
<summary>Model answer</summary>

No. The oracle never read the real users. It computes audiences in Java from the frozen follows of the run's own users (`Snapshots`, `Audience`), and those are all `ck_` users, which are all in `synthetic:users`. So the expected pushes are the same as before. The checker would catch the opposite bug: if a `ck_` user were missing from the set, its pushes would show as missing.
</details>

### 4. What a machine dying does that a crashed pod doesn't

> Phase 4.2 already killed a gateway with SIGKILL. What is new when the whole machine goes?

<details>
<summary>Model answer</summary>

When a process is killed on a living machine, the machine's kernel still closes its sockets and sends a reset, so the other side learns at once. A dead machine sends nothing, so every connection to it just goes quiet. Everything on that machine goes at once: a Kafka broker (there is one per machine), whichever app pods it held, and the node itself. Kubernetes only notices after about 40 s, when the node stops reporting. Kafka clients notice through request timeouts and session timeouts, not a closed connection. And a socket through a dead gateway looks open until something times it out. The Auto Scaling group then starts a replacement machine, and the broker's disk has to follow it.
</details>

### 5. The unanswered-ping rule, and why the gap starts at the last frame

> How does the checker decide a socket is dead, and why backdate the gap?

<details>
<summary>Model answer</summary>

The gateway answers every ping with a pong. The checker pings every 5 s. If nothing at all has arrived since the previous ping, the line is dead, so the checker aborts the socket and reconnects. Pushes delivered to the dead gateway after the last received frame never had a chance to arrive, so the gap starts at that last frame, not at the abort. It is about 10 s long. A push missing inside it is still excused only if history has it, as before. Without the rule, the socket would look up until TCP gave up, which takes minutes, and every push in that time would count as "missing while up": a false FAIL. The UI uses the same rule at its 25 s ping, so a phone on a dead gateway reconnects in under 50 s and refetches what it missed.
</details>

### 6. Why count silent excuses separately

> The report already counts pushes excused by disconnects. Why add `silent: S`?

<details>
<summary>Model answer</summary>

A silent gap is the most forgiving rule the checker has. It excuses up to about 10 s of pushes on a socket that never said it closed. If it fired where no machine died, that would mean something is quietly dropping sockets, or the rule is hiding a real loss. Counting separately makes that visible: `silent 0` is expected everywhere except `node-loss`. A push counts as silent only when every gap that excuses it is silent. If an ordinary gap would have excused it anyway, the silent rule didn't matter.
</details>

### 7. How a crash is made on EKS, and how we know it was a crash

> On k3d, `docker exec` reached the node. What replaces it on EKS?

<details>
<summary>Model answer</summary>

`kubectl debug node/<machine> --profile=sysadmin` starts a short-lived privileged pod on that machine, sharing its processes and network, with the machine's disk at `/host`. In it, `chroot /host crictl stop --timeout 0 <container>` sends SIGKILL to the container from outside. From inside, Linux ignores SIGKILL to PID 1, and `kubectl delete` would be a polite SIGTERM. The proof is unchanged: the restarted container's last exit code is 137 (128 + 9). The debug pod is deleted after each command, and the suite's exit trap deletes any leftover `node-debugger-*` pod, because they run privileged.
</details>

### 8. Why the cut might need `ss -K` on EKS

> The cut is a NetworkPolicy. Why might the policy alone not be enough?

<details>
<summary>Model answer</summary>

A policy decides which new connections are allowed. Kafka clients keep their connections open for minutes, so the question is whether connections already open get cut too. On k3s they don't, which is why 4.2 deletes their conntrack entries. EKS enforces policies differently (the VPC CNI's eBPF agent), so we measure it in the session. If open connections survive, the debug pod enters the pod's network namespace and resets them with `ss -K '( dport = :9092 )'`. The client reconnects, and the policy refuses the new connection. The proof stays the same: by 50 s the cut pod has left its consumer group and the other copy is still in it.
</details>

### 9. What the three-broker crash proves

> On the laptop a broker crash stops Kafka. What should happen on EKS, and how is it checked?

<details>
<summary>Model answer</summary>

With three brokers, replication factor 3, and `min.insync.replicas=2`, losing one broker should only move leadership. The scenario kills the broker that leads the most partitions, so the most leadership has to move. Within 30 s, no partition may still be led by it or be without a leader; KRaft's controller notices the broker's missing heartbeats and elects new leaders from the in-sync replicas. Before the next scenario, steady state needs every broker Ready and no under-replicated partitions, so the crashed broker has caught back up. Admin commands meanwhile run on a Ready broker that is not the one at fault, because an exec into the dead one would fail.
</details>

### 10. How node-loss picks its machine

> Which machine does node-loss terminate, and why those rules?

<details>
<summary>Model answer</summary>

Never Redis's: losing Redis loses Stage 2's delivery claims, so a push can be delivered twice. That is a known limit (#52), not something this run should rediscover. Never Postgres's: it would come back, but it adds minutes and tests nothing new. Never the checker's, or the run dies with it: the scenario places the checker on Redis's machine (`run-job.sh --near redis-0`), so with three machines one always holds none of the three. Preferably not the observability pod's, because its data lives in the pod and the screenshot would be lost; if that is the only choice, the scenario says so.
</details>

### 11. Why the replacement must land in the same zone

> The scenario fails "fault did not land" if the replacement machine comes up in another zone. Why?

<details>
<summary>Model answer</summary>

Each broker's data is an EBS volume, and an EBS volume lives in one availability zone. The broker pod can only come back on a machine in the dead machine's zone, where its disk can attach. The Auto Scaling group keeps zones balanced, so the replacement should land there. If it doesn't, the broker cannot return, and the run proves nothing about recovery. The scenario reports how long each step took: NotReady, replacement Ready, broker back, fully replicated. Expect 5 to 10 minutes from termination to the broker being back. The checker's run finishes before that, on two brokers, which `min.insync.replicas=2` still allows.
</details>
