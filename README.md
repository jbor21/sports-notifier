# Sports Notifier

Real-time sports notifications. You create an account, follow teams, leagues, or games, pick which events you care about, and get a live push in the browser when one happens. Separate Java services on Kubernetes: an atomic Lua script in Redis is the authority on the live score, Kafka carries each accepted play to a stage that finds everyone who follows the game, and a second stage delivers to each user's browser.

The design and every decision behind it are in [DESIGN.md](DESIGN.md).

## What has been proven so far

- **The score race stays fixed.** In the course project, 50 events for one game sent at once left the score at 43, then 48 after a partial fix, then 50 once the check and the write became one Lua script. `EventProcessingBurstTest` replays that burst on every push. Breaking the script on purpose makes it fail (the score came out 6).
- **Events can't go back in game time.** Each event carries `seq`, one number built from the game's progress (period, clock, outs, score), and the script drops anything not later than what it has. Two pollers running at once, as in a rolling update, send each change once.
- **Following a team covers its whole game.** A Celtics follower gets the Lakers' scores in Lakers–Celtics. The smoke test checks it on a real cluster.
- **Only one signup per username.** Before the fix, 20 of 20 simultaneous signups for one name succeeded; now exactly one does.
- **Internal services aren't reachable from other pods.** The smoke test starts a stranger pod and checks it is refused, for the services and for Kafka.
- **A crash can't lose an accepted play.** The script that accepts a play also writes it to an outbox in Redis, and only Kafka's confirmation clears it; a retry loop sends leftovers. A game has at most one unsent play, so its plays reach Kafka in order. The script enforces that instead of a lock, because a pod that stalls past a lock's timeout can still send an older play after a newer one. Tests crash a pod after accept, run two retry loops at once, slow the broker, and pause it.
- **Each notification arrives once.** Kafka delivers at least once; Stage 2 delivers through one Lua script that claims the message, writes history, and publishes to the user's gateways in one step, so a redelivery does nothing. Killing a Stage 1 or Stage 2 worker during a simulated game left each user's history exactly equal to what Stage 1 sent (305, 306, and 308 messages in three runs).
- **Notifications go only to the gateway holding the socket.** Each gateway listens on its own channel; the smoke test checks an open WebSocket receives the play. Shutting a gateway down closes its sockets with "reconnect now" after removing it from routing.
- **It runs on AWS, from nothing, in about 25 minutes, and back to nothing in about 15.** Three machines in three data centers, three Kafka brokers (one per zone, every partition copied to all three), HTTPS at `sports.<domain>` through a load balancer. On EKS the correctness checker replayed two recorded games to 500 synthetic users at 60× speed: 5,128 expected pushes, none missing, duplicated, or out of order, on 550 sockets and in history. The smoke test passes there too, including the stranger pod being refused.
- **Accounts survive tearing AWS down.** `make aws-down` dumps Postgres to S3 and checks the dump before destroying anything; the next `make aws-up` restores it on Postgres's first boot. An account logged in afterwards with its password, its follow, and all 14 notifications. A corrupt dump makes Postgres crash-loop and never start empty; a restart of a live database never fetches again.
- **A wrong ESPN clock heals.** 60 hours of recorded ESPN data showed the clock running ahead of reality in 2 of 23 NHL games, which would have blocked a game's alerts for minutes. The script now accepts an older-looking play in the same period if it has more points, and a status change at the same score (an end of period, a delay) once it has kept arriving for 30 seconds.

## Prerequisites

- [OrbStack](https://orbstack.dev) (provides Docker)
- `brew install k3d helm kubectl jq`
- Java 17 and Maven, for `make test` (Docker must be running: the Kafka tests start a broker in a container)

## Run it

```bash
make cluster     # once: a small Kubernetes cluster (k3d) inside Docker, plus the Strimzi operator that runs Kafka
make up          # build the image and deploy everything
open http://localhost:8080
make simulate    # play a round of simulated games
make up HELM_ARGS="--set espnPoller.enabled=true"   # same, with live ESPN data
```

## Check it

```bash
make test        # unit and integration tests (a throwaway Redis, and Kafka through Testcontainers)
make smoke       # end to end on k3d: the away team's fans, a live socket, plays waiting in Kafka, isolation, network policies
make logs        # pod status and recent logs
bash scripts/espn-check.sh   # can we still read ESPN? (also runs daily in GitHub Actions)
```

## Stop it

```bash
make down        # deletes the cluster
```

Accounts survive `make down`: Postgres keeps its data in `~/.sportsnotifier/postgres` on the Mac.

## Run it on AWS

One-time setup (an AWS account with SSO, the CLI, Terraform, a Cloudflare token, and the kept stack) is in [docs/aws-setup.md](docs/aws-setup.md). Then, for each session:

```bash
aws sso login --profile sports
caffeinate -i make aws-up TAG=pr58   # ~25 min; read Terraform's plan, then type yes
#   … https://sports.<domain> is live …
BASE_URL=https://sports.<domain> KUBE_CONTEXT=sports-notifier-aws make smoke
caffeinate -i make aws-down          # ~15 min; saves Postgres to S3 first
make aws-status                      # must say "nothing billing"
```

What it costs: about **$0.45 an hour** while it is up (EKS, three t3.large machines, the load balancer, disks), about $11 a day if forgotten. While it is down, under a dollar a month: the image registry, the dump bucket, and Terraform's state. A $25 monthly budget emails a warning.
