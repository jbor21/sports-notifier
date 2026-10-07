# Sports Notifier

Real-time sports notifications. You create an account, follow teams, leagues, or games, pick which events you care about, and get a live push in the browser when one happens. Separate Java services coordinate through Redis: an atomic Lua script is the authority on the live score, and a fan-out stage finds everyone who follows the game.

The design and every decision behind it are in [DESIGN.md](DESIGN.md).

## What has been proven so far

- **The score race stays fixed.** In the course project, 50 events for one game sent at once left the score at 43, then 48 after a partial fix, then 50 once the check and the write became one Lua script. `EventProcessingBurstTest` replays that burst on every push. Breaking the script on purpose makes it fail (the score came out 6).
- **Events can't go back in game time.** Each event carries `seq`, one number built from the game's progress (period, clock, outs, score), and the script drops anything not later than what it has. Two pollers running at once, as in a rolling update, send each change once.
- **Following a team covers its whole game.** A Celtics follower gets the Lakers' scores in Lakers–Celtics. The smoke test checks it on a real cluster.
- **Only one signup per username.** Before the fix, 20 of 20 simultaneous signups for one name succeeded; now exactly one does.
- **Internal services aren't reachable from other pods.** The smoke test starts a stranger pod and checks it is refused.

## Prerequisites

- [OrbStack](https://orbstack.dev) (provides Docker)
- `brew install k3d helm kubectl jq`
- Java 17 and Maven, for `make test`

## Run it

```bash
make cluster     # once: a small Kubernetes cluster (k3d) inside Docker
make up          # build the image and deploy everything
open http://localhost:8080
make simulate    # play a round of simulated games
make up HELM_ARGS="--set espnPoller.enabled=true"   # same, with live ESPN data
```

## Check it

```bash
make test        # unit and integration tests (starts a throwaway Redis)
make smoke       # end to end on k3d: notifications for the away team, user isolation, network policies
make logs        # pod status and recent logs
bash scripts/espn-check.sh   # can we still read ESPN? (also runs daily in GitHub Actions)
```

## Stop it

```bash
make down        # deletes the cluster
```

`make down` also deletes every account. Accounts live in Redis until Postgres arrives in Phase 3.
