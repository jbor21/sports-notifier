# Sports Notifier

Real-time sports notifications. You create an account, follow teams, leagues, or games, pick which events you care about, and get a live push in the browser when one happens. Separate Java services coordinate through Redis: an atomic Lua script is the authority on the live score, and a fan-out stage finds everyone who follows the game.

The design and every decision behind it are in [DESIGN.md](DESIGN.md).

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
```

## Check it

```bash
make test        # unit and integration tests (starts a throwaway Redis)
make smoke       # end to end: sign up, follow a team, simulate, wait for the notification
make logs        # pod status and recent logs
```

## Stop it

```bash
make down        # deletes the cluster
```

`make down` also deletes every account. Accounts live in Redis until Postgres arrives in Phase 3.
