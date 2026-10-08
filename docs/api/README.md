# API for the web UI

The contract between the new web UI (phase 3b, in `web/`) and the backend. It was written before the backend implements it, so the UI can be built now against a mock server.

| File | What it is |
|---|---|
| `openapi.yaml` | Every HTTP endpoint: paths, inputs, outputs, errors, examples. The standard format UI tools, code generators, and mock servers read. |
| `websocket.md` | The live connection: notifications, live scores, keepalive, reconnecting. |

Status: **final for Phase 3, 2026-10-06.** Reviewed against the backend design in the Phase 3 brainstorm. The backend implements it slice by slice: 3.2 (`/auth`, `/me`, `/follows`, `/notifications`), 3.3 (the alert catalog's behavior), 3.4 (`/games`, `/leagues`, live `game_update`). Until a slice lands, only the mock serves it. **Served by the backend:** everything, since Phase 3.4 (`/auth`, `/me`, `/follows`, `/notifications` since 3.2; the alert catalog's behavior since 3.3; `/leagues`, `/games`, and the WebSocket since 3.4). The mock is still useful for building screens without a cluster.

## Screens and the calls behind them

| Screen | Calls |
|---|---|
| Sign up / sign in | `POST /auth/signup`, `POST /auth/login`. Signup sends the browser's timezone. |
| App start | `GET /me` (401 means show sign in), then open the WebSocket. |
| Today's games | `GET /games?date=…`, optionally `league=`, `following=true`. Then `subscribe_games` with the ids on screen. |
| Game page | `GET /games/{id}` (score and line score), `GET /notifications?game={id}` (recent alerts), then `subscribe_games` with that id. |
| Follow something | `GET /leagues`, `GET /leagues/{id}/teams` (names, logos, colors), then `POST /follows`. |
| A follow's alert switches | `GET /leagues/{id}/alerts` draws the switches. `GET /follows/{id}` gives their state. `PATCH /follows/{id}` saves changes. |
| Notifications | `GET /notifications` (filter by `league` or `team`, page with `cursor`), `POST /notifications/seen` on open, `DELETE /notifications` to clear all. |
| Badge | `hello.unreadCount` on connect, then `unread_count` messages. |
| Settings | `PATCH /me` for the timezone. |

## Rules for whoever builds the UI, including generator tools

- **Frontend only.** No database, no login system, no server code. Accounts, follows, history, and alerts all live in the backend behind this API. A tool that adds Supabase, Firebase, or its own auth is building a second app.
- **Draw the alert switches from `GET /leagues/{id}/alerts`.** Do not hard-code them. New alert types then appear without a UI change. Show `coming_soon` alerts as disabled.
- **Ignore what you don't know.** Unknown alert types, game statuses, and WebSocket message types are skipped, not errors.
- **One configurable base URL.** `/api/v1` against the real backend, `http://localhost:4010` against the mock (the mock serves the paths without the `/api/v1` prefix).
- **Installable web app (PWA).** Build it so a phone can add it to the home screen. Phase 7's Web Push needs that.
- **Times arrive in UTC.** Show them in the user's timezone.
- **Live clocks do not tick on their own.** Show the clock from the last `game_update`; see `websocket.md`.

## Running the mock

From the repo root (needs Node):

```
npx @stoplight/prism-cli mock docs/api/openapi.yaml -p 4010
```

It answers every endpoint with the examples in the spec and checks inputs against the schemas. Endpoints that need a session return 401 unless the request carries a `sid` cookie; any value works against the mock. The mock covers HTTP only. For live data, run the real backend (`make cluster up simulate`), or fake `game_update` and `notification` messages locally from the examples in `websocket.md`.

## What changed from the course API

| Course | Now | Why |
|---|---|---|
| `/api/...` | `/api/v1/...` | Room for a v2 without breaking this UI. |
| `/api/signup`, `/api/login` | `/auth/signup`, `/auth/login` | Grouped, so the sign-in method can change later without touching the rest. |
| `PUT /api/preferences` with three generic event types | `/follows` with per-follow switches from the catalog | Decision 15. |
| Team ids like `BOS`, game ids like `espn-401872948` | League-qualified ids like `nba-bos`, `nfl-401872948` | `BOS` is the Celtics, the Red Sox, and the Bruins, and ids should not name the data provider. |
| `/api/history?limit=50` | `/notifications` with filters, cursor paging, unread count, clear all, 30-day retention | What the history screen needs. |
| `/api/upcoming` (ESPN proxy) | Folded into `/games?date=` | The UI never talks to the data provider, so the provider can change per sport. |
| WebSocket `/ws`, notifications only | `/api/v1/ws` with notifications, live game updates, unread count, keepalive | Live scores on screen. |
| Password minimum 4 | 8 to 72 bytes, usernames `a–z 0–9 _` | One of the Phase 2 fixes. bcrypt ignores bytes past 72. |

## Changing the contract

Edit `openapi.yaml` or `websocket.md` first, then the code. Check the file with:

```
npx @redocly/cli lint docs/api/openapi.yaml
```

Three warnings are expected: no license (private repo), a localhost server, and `GET /leagues` having no error response.
