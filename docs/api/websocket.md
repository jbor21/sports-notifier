# WebSocket: live notifications and live scores

The HTTP API (`openapi.yaml`) is for asking. The WebSocket is for being told. One connection per browser tab carries two kinds of live data:

- **Notifications.** The alerts this user's follows produce (`DESIGN.md` decision 15).
- **Game updates.** Score, clock, and status changes for the games the tab is showing.

Message shapes reuse the schemas in `openapi.yaml` (`Notification`, `GameSummary`).

## Connecting

```
ws://localhost:8080/api/v1/ws      (wss:// in the cloud)
```

The browser sends the session cookie automatically. There is nothing to pass in the URL.

- **Signed in:** the server sends `hello`.
- **Not signed in:** the server closes the connection with code **4401**. The UI sends the user to sign in and does not retry.

Every message in both directions is one JSON object with a `type` field. The UI must ignore any `type` it does not know.

## Server to client

### `hello`

The first message after connecting.

```json
{ "type": "hello", "userId": "u_7f3a", "serverTime": "2026-10-04T20:00:00Z", "unreadCount": 3 }
```

### `notification`

A new alert for this user, as one push: a headline plus anything merged into it. History saves it separately and may lag the push by a second or two, so add the pushed notification to the list on screen rather than refetching `GET /notifications` at once; its `id` is the same in both.

```json
{
  "type": "notification",
  "notification": {
    "id": "nfl-401872948:SCORE:away:3",
    "kind": "game_moment",
    "leagueId": "NFL",
    "createdAt": "2026-10-04T20:01:14Z",
    "headline": { "type": "TOUCHDOWN", "text": "Touchdown Raiders! Extra point pending. Raiders 20, Bears 17 · Q4 4:12" },
    "extras": [{ "type": "LEAD_CHANGE", "text": "Raiders take the lead. Raiders 20, Bears 17 · Q4 4:12" }],
    "game": {
      "id": "nfl-401872948",
      "home": { "team": { "id": "nfl-chi", "abbreviation": "CHI", "displayName": "Chicago Bears" }, "score": 17 },
      "away": { "team": { "id": "nfl-lv", "abbreviation": "LV", "displayName": "Las Vegas Raiders" }, "score": 20 },
      "periodLabel": "Q4",
      "clock": "4:12"
    }
  }
}
```

### `unread_count`

Sent whenever the count changes: after a notification arrives, after `POST /notifications/seen`, and after clearing history. All of the user's tabs get it, so a badge cleared in one tab clears in the others.

```json
{ "type": "unread_count", "count": 4 }
```

### `game_update`

A game this tab subscribed to changed. It carries the full `GameSummary`, not a diff, so the UI just replaces what it has.

```json
{
  "type": "game_update",
  "game": {
    "id": "nfl-401872948",
    "leagueId": "NFL",
    "status": "in_progress",
    "startTime": "2026-10-04T17:00:00Z",
    "home": { "team": { "id": "nfl-chi", "abbreviation": "CHI", "displayName": "Chicago Bears" }, "score": 17 },
    "away": { "team": { "id": "nfl-lv", "abbreviation": "LV", "displayName": "Las Vegas Raiders" }, "score": 20 },
    "period": 4,
    "periodLabel": "Q4",
    "clock": "4:12",
    "version": 58,
    "updatedAt": "2026-10-04T20:01:13Z"
  }
}
```

**Ignore stale updates.** Each game has a `version` that only goes up. If an update's `version` is less than or equal to the one the UI already has, from the HTTP list or an earlier message, drop it. Updates can arrive out of order after a reconnect.

**How often.** As often as the provider changes: every few seconds while a game is live. The clock does not tick between updates. The UI shows the clock from the last update and does not run its own countdown, because the real clock stops and starts.

### `pong`

The reply to `ping`.

```json
{ "type": "pong" }
```

## Client to server

### `subscribe_games`

"These are the games I am showing." It **replaces** the tab's previous list. Send it whenever the visible games change: on the games list, on a game page, or after a reconnect. An empty list stops all game updates.

```json
{ "type": "subscribe_games", "gameIds": ["nfl-401872948", "nfl-401872951"] }
```

At most 100 ids; extra ids and ids that are not game ids are ignored. Notifications do not depend on this. They arrive for everything the user follows, whatever is on screen.

**Current state right away.** For every game the list adds, the server sends a `game_update` with the game's current state as soon as it is listening for that game, so nothing that happened between the HTTP fetch and the subscription is missed. It may repeat a version the UI already has; the stale-version rule drops it. Games that have not started send nothing until they do.

### `ping`

Send every 25 seconds while connected.

```json
{ "type": "ping" }
```

This keeps proxies and load balancers from closing a quiet connection. The server closes a connection that has sent nothing for 60 seconds, with code 1000 (reconnect with backoff).

The server answers every `ping` with a `pong`. If nothing at all has arrived from the server by the time of the next `ping`, treat the connection as dead and reconnect with backoff, then refetch. A server on a machine that died sends no close, so without this a client can sit on a dead connection for minutes.

## Reconnecting

Connections drop: Wi-Fi changes, a laptop sleeps, or a gateway restarts or scales in (`DESIGN.md` decision 12).

**Close codes:**

| Code | Meaning | UI does |
|---|---|---|
| 4401 | Not signed in | Go to sign in. Do not retry. |
| 4000 | Server is shutting this gateway down | Reconnect right away (after the jitter below). |
| 1013 | This tab fell too far behind reading messages (a slow or sleeping connection) | Reconnect with backoff, then refetch. |
| 1000 `idle` | Nothing received from the tab for 60 seconds | Reconnect with backoff; keep pinging every 25 seconds. |
| anything else | Network or server problem | Reconnect with backoff. |

**Backoff.** Wait 1 s, then 2 s, 4 s, 8 s, up to 30 s between tries. Add a random 0–1 s to each wait, so a thousand tabs do not all reconnect in the same instant. Reset to 1 s after a connection stays up for a minute.

**After reconnecting:**

1. Send `subscribe_games` again.
2. Refetch over HTTP whatever is on screen: the games list or game page, and the unread count (or use `hello.unreadCount`).
3. Do not expect missed messages to be replayed. Notifications sent while disconnected are in history (`GET /notifications`).

## What this means for the backend (Phase 3)

Notes for whoever builds the server side; the UI can skip this.

- **Notifications** follow decision 13: Stage 2 publishes to the channel of the gateway holding the user's socket.
- **Game updates** are a second path. The accept script (the Lua script that is the authority on the score) publishes every accepted play on `live:<gameId>` in the same step that saves it, so updates leave in version order and only for saved state. A gateway subscribes to the channels of games its tabs asked for, waits for Redis to confirm, then reads the game's current state for the tab; it unsubscribes when no tab wants a game. Updates are per game, not per user, so a Super Bowl with 100,000 viewers is one message per gateway, not 100,000.
- **Unread count.** `unread:<userId>` in Redis is a cache of the count in Postgres. Stage 2's delivery script raises it in the same step as the delivery (only if it exists) and publishes `unread_count`; seen and clear set it to 0 and tell every gateway holding the user. `hello` reads it, or fills it from Postgres when it is missing. It can be off by one until the next seen, clear, or its daily expiry.
- `version` is the per-game counter the Lua script increments on every accepted play, in the same step as the score, so it increases in the order the score changed. It is not the script's internal `seq`, which can go down when the script heals a bad ESPN clock (Phase 3.1 spec, section 1.4).
- Multiple tabs: each tab is its own connection and gets its own copy of notifications and `unread_count`.
