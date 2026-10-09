# Phase 3b: New UI (Sideline) — design

Status: draft for review, 2026-10-08. Parent document: `DESIGN.md` (build order item 3b, "What we are not building"). The contract is `docs/api/` (`README.md`, `openapi.yaml`, `websocket.md`) on main. If this spec and `DESIGN.md` disagree, `DESIGN.md` wins and this spec gets fixed. `DESIGN.md` is updated in the same commit as this spec: the 3b entry (how the UI is built and served) and a Phase 6 line for a public demo build.

Phase 3 (3.1–3.4) is merged; the backend serves all of `docs/api/` since PR #28. 3b is the web UI that replaces the course `index.html`, broken since 3.2.

## Goal

A web app called **Sideline**, in `web/`, that does everything in `docs/api/README.md`'s screen table against the real backend: sign up and in, the day's games with live scores, a game page, following leagues, teams, and games, each follow's alert switches, notification history, the unread badge, and settings. It is installable (a PWA) so Phase 7's Web Push can reach a phone. The gateway serves it from the same image, on the same origin as `/api/v1`.

The look comes from the user's v0 design (`~/Desktop/projects/sports-notification-app-frontend-v0`, outside the repo, read-only). The code is rebuilt, not ported (section 1).

Not in 3b: Web Push, quiet hours, rate caps (Phase 7); the AI layer; a public demo build (Phase 6, below); backend changes beyond serving the files. Open issues stay open: #24 and #25 are tolerated by the UI (section 5.6), #29 and #15 are due before Phase 4, #16 before Phase 5.

### Done when

1. `make test` passes, including the gateway tests in section 9.1.
2. In `web/`: `pnpm format:check`, `pnpm typecheck`, `pnpm test`, `pnpm types:check`, `pnpm build`, and `pnpm test:pwa` pass, locally and in CI's `web` job.
3. `make cluster up smoke e2e` passes on the laptop and in CI's `deploy-check` job.
4. The user's live check (section 9.5) is done and its results are in the PR, or the user waives it there.
5. Docs updated (section 11), and `docs/study/phase-3-quiz.md` has a 3b section. The user is quizzed only on request.
6. The PR from `phase-3b-ui` is merged.

### Branch

`phase-3b-ui`, worktree `.worktrees/phase-3b-ui`, from main at `0c8926b`. No cluster rebuild needed: the backend's data and pipeline don't change, only the gateway image.

---

## 1. v0 is the reference, not the code

**What v0 is.** A client-only React app (hash routing, server rendering turned off) wrapped in a Next.js static export, with a hand-written stylesheet, an API client, a socket hook with reconnects, catalog-driven alert switches, and a demo mode that fakes the API and socket.

**Problem.** The source is minified by hand: `Games.tsx` is 27 lines and 9 KB, a whole screen per line. It can't be reviewed, tested piece by piece, or explained in an interview. It also departs from the contract in places:

- games with a status it doesn't know are hidden (`openapi.yaml`: treat unknown statuses like `in_progress`);
- every pushed notification refetches history (`websocket.md`: add the pushed one, history lags);
- the date input allows any date (only yesterday through +7 are served);
- the badge starts at 3 before `hello`;
- it unregisters every service worker on load, which would break the PWA;
- known alert types are a hard-coded list.

**Fix.** Rebuild in `web/`, test first for the client core (section 5). Carry over from v0: the stylesheet (un-minified and split by screen), the copy, the logo (`sideline.svg`), the fonts (DM Sans, Manrope), and each screen's layout. v0's demo mode is not carried over; its code stays in the v0 folder as a reference.

**Why.** Keeps the look the user chose and gives code that can be read and defended.

## 2. Stack

| Piece | Choice | Why |
|---|---|---|
| Build | Vite | v0 is already client-only, so Next.js adds nothing; v0 started on Vite (its `dist/`, and "Set VITE_API_BASE_URL" in its Settings). Vite's dev proxy handles WebSockets. |
| UI | React 19, TypeScript (strict) | Same as v0. |
| Routing | React Router, path routes | Real URLs (`/games/nfl-401872948`), which Phase 7's push clicks can open. |
| Data | SWR | Same as v0: caching, revalidation, paging. |
| Styles | Plain CSS from v0, one file per screen | v0's look is plain CSS; Tailwind and shadcn were installed but unused (one unused button). Both dropped. |
| Icons, fonts | lucide-react, @fontsource-variable (self-hosted) | Same as v0; self-hosted fonts keep the CSP at `font-src 'self'`. |
| PWA | vite-plugin-pwa, `injectManifest`, our own `src/sw.ts` | A service worker we can read; Phase 7 adds its push handler to the same file. |
| Types | openapi-typescript from `../docs/api/openapi.yaml` | One contract. Main's file generates cleanly (checked 2026-10-08), so v0's patched copy is not used. |
| Tests | Vitest, Testing Library (jsdom), Playwright | Units and components; browser checks for the PWA update and end to end. |
| Mock | @stoplight/prism-cli, a pinned dev dependency | `pnpm dev:mock` without a download each time. |
| Format | Prettier, checked in CI | The answer to v0's minified source. |
| Package manager | pnpm, version pinned in `package.json` (`packageManager`), Node 24 LTS | Same as v0; Corepack picks the pinned pnpm in Docker and CI. |

## 3. Layout of `web/`

```
web/
  package.json  pnpm-lock.yaml  vite.config.ts  tsconfig.json  index.html  .prettierrc
  scripts/dev-mock.mjs            starts Prism on :4010 and Vite in mock mode
  public/                         sideline.svg, icons (192, 512, maskable 512, apple-touch 180)
  src/
    main.tsx  App.tsx  routes.tsx  sw.ts
    api/      schema.d.ts (generated)  client.ts  errors.ts  types.ts
    live/     connection.ts  games.ts  badge.ts  notifications.ts  knownTypes.ts
    screens/  SignIn.tsx  SignUp.tsx  GameDay.tsx  GamePage.tsx  Following.tsx
              FollowSettings.tsx  AddFollow.tsx  Notifications.tsx  Settings.tsx
    components/  Shell.tsx  GameCard.tsx  TeamMark.tsx  NoticeItem.tsx  Toggle.tsx
                 Modal.tsx  Toasts.tsx  ReconnectBanner.tsx  LoadState.tsx  Empty.tsx
    pwa/      register.ts (update flow)
    styles/   tokens.css  base.css  shell.css  games.css  game.css  follows.css
              notifications.css  auth.css  settings.css
    test/     fixtures.ts (from openapi.yaml examples)  fakeSocket.ts
  e2e/        cluster.spec.ts  pwa-update.spec.ts  playwright.config.ts
```

`src/api/` and `src/live/` are plain TypeScript with no React, so they test without rendering. Screens read them through small hooks.

## 4. Build, serving, and the dev loop

### 4.1 Into the image

**Problem.** The image is Java only. **Fix.** A third Dockerfile stage:

```
FROM node:24-alpine AS web
WORKDIR /web
RUN corepack enable
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
ARG BUILD_ID=dev
RUN VITE_BUILD_ID=$BUILD_ID pnpm build
```

The final stage adds `COPY --from=web /web/dist /app/web`. The Makefile passes `--build-arg BUILD_ID=$(TAG)`. Dependencies are installed before the source is copied, so a code-only change reuses the cached install layer. The build uses the committed `src/api/schema.d.ts` (`docs/` stays out of the build context, as `.dockerignore` has it); `.dockerignore` gains `web/node_modules/`, `web/dist/`, and Playwright's output folders. **Why.** Still one image (`DESIGN.md`), and every `make up` and CI deploy gets the current UI with no extra step.

### 4.2 The gateway serves it

**Problem.** The gateway serves the course `index.html` from the classpath at `/ui` and redirects `/` there. **Fix.** In `GatewayMain`, a small `WebFiles` class registered when the folder `WEB_DIR` (default `/app/web`) exists; if it doesn't (unit tests, `mvn` runs), the gateway logs one line and starts without it. Rules:

1. Files under `WEB_DIR` are served at `/` (Javalin static files, `Location.EXTERNAL`).
2. **App routes fall back to `index.html`:** a `GET` that matched no file and no endpoint, whose path is not under `/api/`, is not `/health`, and has no file extension, gets `index.html` with 200. Anything else keeps its 404: `/api/v1/nope` stays an API 404, `/assets/missing.js` stays a 404 (a stale hashed file must not come back as HTML).
3. **Cache headers:** `/assets/*` (Vite's hashed names) get `Cache-Control: public, max-age=31536000, immutable`. Everything else served by `WebFiles` (`index.html`, the fallback, `sw.js`, `manifest.webmanifest`, icons) gets `Cache-Control: no-cache`, so a deploy is seen on the next load and the browser's service worker update check is never served a stale `sw.js`.
4. **Security headers** on every `WebFiles` response:
   - `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https://a.espncdn.com; font-src 'self'; connect-src 'self' ws://<host> wss://<host>; manifest-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`.
   - `connect-src` names the WebSocket hosts explicitly because older Safari doesn't count `ws:`/`wss:` as `'self'`. A bare `ws:` would allow sockets to any host, so `<host>` is the request's `Host` header, used only if it matches `^[A-Za-z0-9.-]+(:[0-9]{1,5})?$`. Otherwise `connect-src 'self'` alone (header injection guard).
   - `img-src` allows ESPN's logo host because the API's `logoUrl`s point there (`catalog/teams.json`).
   - `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`.
5. Deleted: the `/ui` static mount, the `/` redirect, and `src/main/resources/public/index.html`. The course UI stays in the repo's first commit.

**Why.** Same origin: the `sid` cookie (`SameSite=Lax`, `Path=/`) and the socket at `/api/v1/ws` work with no CORS. The Ingress, network policies, and chart are unchanged. A UI change rolls the gateways, but with one image every deploy rolls every service already; tabs reconnect on close code 4000.

### 4.3 Dev loop

- `pnpm dev`: Vite on `:5173`, proxying `/api/v1` (HTTP and WebSocket, `ws: true`) to `http://localhost:8080`, the k3d cluster. The laptop cookie is not `Secure`, and browsers match cookies by host, not port, so signing in works.
- `pnpm dev:mock`: starts Prism on `:4010` with `docs/api/openapi.yaml`, and Vite with the proxy pointed at it: the `/api/v1` prefix is stripped and `Cookie: sid=mock` is added (Prism answers 401 without a `sid`). The app still calls `/api/v1` on its own origin. **Why not call `:4010` directly:** a different origin needs CORS with credentials, and Prism never sets a real session cookie, so every signed-in screen would be 401. Against the mock the socket can't connect, and the UI shows its reconnecting state, which is honest.
- `VITE_API_BASE` (default `/api/v1`) stays configurable. The socket URL is derived from it: same origin with `ws:`/`wss:` matching the page, or from an absolute base.
- `docs/api/README.md`'s "Running the mock" section points at `pnpm dev:mock`.

## 5. The client core

### 5.1 HTTP client (`api/client.ts`, `api/errors.ts`)

- `request<T>(path, {method, body, query})`: `fetch(base + path, {credentials: 'same-origin'})`, JSON body with `Content-Type: application/json`, 204 → `undefined`.
- Every failure becomes `ApiError {status, code, message, fields, retryAfter}`:
  - a body in the `Error` shape → its `code`, `message`, `fields`; `Retry-After` → seconds;
  - any other body (#25's bare 500, Traefik's 502/504 page) → `code: 'server_error'` with "Something went wrong on our side. Try again.";
  - no response at all → `status: 0`, `code: 'network'`, "Can't reach Sideline. Check your connection.".
- A 401 from any call except `POST /auth/login` means the session is gone: the app goes to `/signin?next=<current path>`.

### 5.2 Live connection (`live/connection.ts`)

A class with no React, so fake timers can drive it. States: `connecting → open → waiting → connecting`, plus `signedOut` and `stopped`.

| Event | Does |
|---|---|
| open | Send `subscribe_games` with the current set; start pinging every 25 s; after 60 s open, reset the backoff. If this is a reconnect, call `onReconnected` (the app refetches what's on screen and the badge). |
| close 4401 | `signedOut`: no retry; the app goes to `/signin?next=…`. |
| close 4000 | Retry after 0–1 s of jitter only. |
| close 1013, 1000, anything else, error | Back off 1, 2, 4, 8, 16, 30, 30… s, each plus 0–1 s of jitter. |
| `online` event, or the tab becomes visible, while waiting | Retry now. A laptop that slept 20 minutes shouldn't sit out its backoff. |
| the tab becomes visible while open | Send a `ping` at once. Chrome runs timers in a tab hidden more than 5 minutes about once a minute, which can stretch the 25 s ping past the server's 60 s idle close; a ping on return makes sure the server hears from the tab right away. |
| message | Parse; drop anything malformed or with an unknown `type`; dispatch `hello`, `notification`, `unread_count`, `game_update`, `pong`. |
| `setGames(ids)` | Keep the set (first 100); if open, send `subscribe_games` when it changed. |

The socket opens only when signed in (`GET /me` answered 200).

### 5.3 Live game store (`live/games.ts`)

One map, game id → the highest-version `GameSummary`. HTTP results and `game_update` both go through `accept(game)`: kept only if no copy exists or its `version` is higher. Screens read from the store, so a slow list refetch can't undo a newer socket update, and a schedule game (version 0) is replaced by its first live update. The store also exposes, per game, "changed at" and the score delta of the last accepted change, for the live-change look (section 7).

The subscribed set is the ids the current screen shows: Game day's cards (live and soonest first, at most 100) or the game page's one id. Leaving a screen replaces the set.

### 5.4 Badge (`live/badge.ts`)

The badge is the count from the last count message received, `hello.unreadCount` or `unread_count`, in whatever order they come (#24). Opening Notifications calls `POST /notifications/seen` and sets 0 locally. The server's `unread_count` 0 to every tab follows. The badge can be off by one until the next seen (`websocket.md`); accepted.

### 5.5 Notifications (`live/notifications.ts`, `live/knownTypes.ts`)

- A pushed notification is shown as a toast and inserted at the top of every cached history list it matches (all, its league, the game's teams, its game), deduplicated by `id`. History is not refetched for it, because the history writer may lag the push.
- **Known alert types** are the union of every league's catalog (`GET /leagues/{id}/alerts`, public, fetched once at start and cached) and the answering types `openapi.yaml` lists under `AlertPart` (`SCORE`, `RESUMED`, `GOAL_OVERTURNED`, `RUN_OVERTURNED`, `TOUCHDOWN_OVERTURNED`, `FIELD_GOAL_OVERTURNED`, `SCORE_OVERTURNED`). A notification whose headline type is unknown is skipped (no toast, not listed); unknown extras are dropped. A new catalog type is known without a UI change.
- While Notifications is open and visible, a push also calls `seen`, at most once per 5 s, so the badge doesn't count what the user is looking at.

### 5.6 Backend quirks, tolerated

| Issue | UI behavior |
|---|---|
| #24 `hello` not always first | The connection dispatches messages in any order; the badge takes the last count. |
| #25 bare 500 | `ApiError` with the plain message (5.1). |
| Badge off by one | Shown as is; the next seen or clear corrects it. |

**Why not fix the backend here.** A client has to survive non-JSON errors from proxies anyway, and 3b stays frontend only. The issues stay open.

### 5.7 Unknown values

Unknown socket message types and unknown alert types are skipped. An unknown game status is shown like `in_progress` with its `periodLabel`, as `openapi.yaml`'s `GameStatus` says.

### 5.8 Times

Every time is shown with `Intl` in `/me`'s `timezone`. Clocks are shown exactly as the last update gave them; nothing ticks locally (`websocket.md`).

## 6. Screens

Every screen requires sign-in, as `docs/api/README.md` says ("401 means show sign in"); the socket closes signed-out tabs with 4401 anyway. App start: `GET /me`; 401 → `/signin?next=<path>`; 200 → open the socket and the requested route.

| Route | Screen | Calls and behavior |
|---|---|---|
| `/signin`, `/signup` | v0's two-panel auth | `POST /auth/login` / `POST /auth/signup` (with the browser's timezone). Client checks match `Credentials`: username 3–20 of `a-z 0-9 _`, password 8–72 bytes in UTF-8. Field errors from `error.fields`; 429 disables the button with a countdown from `Retry-After`. Then go to `next` or `/`. |
| `/` | Game day | A 9-day strip, yesterday to 7 days ahead in the user's timezone, default today. League tabs from `GET /leagues`; a Following toggle (`following=true`); a Live only filter (client side). `GET /games?date=&tz=&league=&following=`, refetched every 60 s while visible (games not in the schedule, like the simulator's, appear on a refetch). Grouped by league, soonest first. Cards subscribe (5.3). |
| `/games/:id` | Game page | `GET /games/{id}` (score, line score, venue, broadcast), `GET /notifications?game=` (recent alerts), subscribe to the one id. `game_update` has no line score, so the detail is refetched when the store's `period` or `status` changes. Follow/unfollow this game (`POST /follows` kind game, `DELETE /follows/{id}`). 404 → "Game not found". |
| `/following` | Your follows | `GET /follows`; cards with target, kind, league, and the count of switches on. |
| (dialog) | Add a follow | Kind (team, league, game) → league → team list (`GET /leagues/{id}/teams`, searchable), the league itself, or today's games of that league → Follow (`POST /follows`, 201 or 200) → go to that follow's switches. |
| `/following/:id` | Alert switches | `GET /follows/{id}` and `GET /leagues/{leagueId}/alerts`. One row per catalog entry whose `appliesTo` has this follow's kind, in catalog order: name, description, switch. `coming_soon` → shown disabled with "Coming soon". `precision: from_snapshots` → a note that a moment between polls can be missed. `inning_set` options → innings 1–9, none chosen meaning every inning. Each change sends `PATCH /follows/{id}` with only that type, at once, optimistically; a failure rolls the switch back with an error toast. PATCHes for one follow go one at a time, in order. Unfollow asks first. |
| `/notifications` | History | `GET /notifications` with league and team filters, 20 per page, "Load older" with `cursor`. `POST /notifications/seen` on open. Clear all asks first, then `DELETE /notifications`. "Kept for 30 days". |
| `/settings` | Account | Timezone select (`PATCH /me`), sign out (`POST /auth/logout` → `/signin`), dark mode (kept in `localStorage`, default from `prefers-color-scheme`), the build id, how to install the app. |

**Shell.** v0's sidebar (desktop) and bottom tab bar (phone), top bar with the bell. The badge shows on Notifications and the bell. The demo toggle is gone. Notification toasts: v0's toast, 6.5 s, at most 3 stacked, `aria-live="polite"`, click opens the game. Error toasts for failed actions.

**States, the same on every screen** (v0's components): loading skeletons; empty panels with per-screen copy; an error box with "Try again" and the message from 5.1.

**Accessibility.** v0's focus outlines, labelled switches (`role="switch"`), dialogs as `<dialog>`, `aria-live` toasts. Every animation is off under `prefers-reduced-motion`.

## 7. The live look

Chosen in the visual companion (option B):

- **A score change.** The new digits slide in and flash green (v0's `score-in`), the card's border glows green, and a chip with the change ("+7") sits next to the new score for 4 s. The clock and period label change only when an update arrives.
- **Reconnecting** (option Y, with a longer delay). The sidebar status says "Reconnecting" at once. After **10 s** without a connection, a banner appears above the content on every screen, phone included: "Live updates paused. Reconnecting in N s; scores may be behind." with "Try now". LIVE labels and live scores turn grey until the refetch after reconnecting. **Why 10 s:** a 4000 reconnects within about 1 s, and the first two backoffs finish within about 5 s, so routine blips never show the banner; scores update every few seconds anyway, so 10 s behind is normal.

## 8. PWA

- **Manifest:** name "Sideline", `display: standalone`, `start_url: /`, theme colour `#16794c`, icons from `sideline.svg` (192, 512, maskable 512, apple-touch 180), generated once by a script and committed.
- **`src/sw.ts`:** precaches the built files (Workbox `precacheAndRoute`) and answers navigations with the cached `index.html`, except `/api/` and `/health`. Nothing under `/api/` is cached; the socket is never touched by a service worker. Offline, the shell loads and each screen shows its error state.
- **Updates (what happens when a deploy ships a new version).**
  1. The app asks the browser to check for a new `sw.js` when the tab becomes visible and every 30 minutes (`registration.update()`); a long-open tab otherwise rarely checks.
  2. A new worker installs and waits. The app shows a toast: "New version — Reload".
  3. Reload → the waiting worker is told to activate (`SKIP_WAITING`), and the page reloads when it takes control.
  4. Toast not answered → the next in-app navigation activates the waiting worker and loads the target URL with a full page load, so the tab doesn't run old code for hours.
- **Build id:** `VITE_BUILD_ID` (the image tag) in a `<meta name="build-id">` and in Settings.
- **Phone install** needs HTTPS (or `localhost`), and k3d listens only on the Mac's 127.0.0.1, so installing on a phone is first checked in Phase 5. Desktop install from `http://localhost:8080` is checked in 3b.

## 9. Tests

### 9.1 Gateway (Java, in process, `make test`)

`WebFilesTest` with a temporary folder holding `index.html`, `assets/app-abc123.js`, `sw.js`:

- `/` and `/games/nfl-1` → `index.html`, 200; `/following/f_1` → `index.html`.
- `/assets/app-abc123.js` → the file with `immutable`; `/assets/missing.js` → 404, not HTML.
- `/api/v1/nope` → still the API's 404, not HTML; `/health` → unchanged.
- `index.html` and `sw.js` → `Cache-Control: no-cache`.
- CSP present; with `Host: localhost:8080`, `connect-src` contains `'self' ws://localhost:8080 wss://localhost:8080`; with a `Host` containing `;` or a space, `connect-src` is `'self'` alone; `img-src` has `https://a.espncdn.com`; `nosniff` present.
- No `WEB_DIR` folder → the gateway starts, `/health` answers, `/` is 404.
- `/ui/` → 404 (the course UI is gone).

### 9.2 Web unit and component tests (Vitest, CI `web` job)

Fixtures come from the examples in `openapi.yaml` (parsed at test time), so a contract change breaks the tests rather than drifting.

- **Client:** Error shape mapped with `fields` and `Retry-After`; bare 500 text and a Traefik 502 HTML page → `server_error`; network failure → `network`; 204 → `undefined`; 401 calls the signed-out hook except for login.
- **Connection** (fake socket, fake timers): each close code row of 5.2; the backoff sequence and the 0–1 s jitter bounds; reset after 60 s open; ping every 25 s; a ping sent at once when the tab becomes visible while open; `subscribe_games` re-sent after a reconnect and `onReconnected` called only on reconnects; immediate retry on `online` and on becoming visible; a malformed frame and an unknown `type` change nothing; `hello` after a `notification` and an `unread_count` (#24).
- **Game store:** equal and lower versions dropped; an HTTP copy older than a socket copy is dropped; version 0 replaced; delta and "changed at" recorded.
- **Badge:** last count wins in either order; seen → 0.
- **Notifications:** a push is inserted at the top of matching cached lists without a fetch, once per id; unknown headline type skipped; unknown extras dropped; an answering type (`TOUCHDOWN_OVERTURNED`) is known.
- **Screens:** switches drawn from the catalog fixture, one per `appliesTo` entry; `coming_soon` disabled; a failed PATCH rolls back and shows an error; two quick toggles send two PATCHes in order; the date strip has 9 days around today in the user's timezone; an unknown status renders like live; the 10 s banner appears at 10 s and not before; Notifications calls seen on open.

`pnpm types:check` regenerates `src/api/schema.d.ts` from `docs/api/openapi.yaml` and fails if it differs from the committed file.

### 9.3 PWA update test (`pnpm test:pwa`, Playwright, CI `web` job, no cluster)

Builds the app twice with `VITE_BUILD_ID=one` and `two`. A static server serves a folder holding build one. The test opens the page (build id one, service worker active), replaces the folder with build two, triggers the update check, and asserts:

1. the "New version" toast appears; clicking Reload shows build id two;
2. in a second run, leaving the toast and clicking an in-app link shows build id two at the target route.

### 9.4 End to end (`make e2e`, Playwright, CI `deploy-check` after `make smoke`)

Chromium against `http://localhost:8080`, browser timezone UTC, a fresh username. Each step goes through the UI except where noted. The simulator runs with `SIM_INTERVAL_MS=5000` (the NFL game is about 45 steps, so about 4 minutes, with the Chiefs' touchdown about 20 s in), so a gateway restart (20–40 s) can't outlast the game. Nothing reloads the page after the simulator starts, so the touchdown toast can't be lost.

1. Sign up through the form; the app lands on Game day.
2. Add a follow: Team → NFL → Kansas City Chiefs → Follow; the switches page lists Touchdown (on) and at least one disabled "Coming soon" switch.
3. Go back to Game day and wait until the page reports the socket open (`data-connection="open"` on the app root).
4. Start the simulator from the test: `scripts/simulate.sh SIM_WAVES=1 SIM_INTERVAL_MS=5000` (stopping any simulator job still running first, as the smoke test does).
5. Wait for the "Touchdown" toast and a badge of at least 1.
6. Click the toast: the app moves to the game page without a reload. Wait for the score card's `data-version` to rise (a `game_update`).
7. Restart the gateway (`kubectl rollout restart deployment/gateway`, then wait for the rollout). Wait for the app's reconnect counter (`data-connects` on the app root) to rise and `data-connection="open"` again.
8. Check through the API (`GET /api/v1/games/{id}` with the test's cookie) that the game is still `in_progress`. If not, fail with "the game ended during the gateway restart; the e2e timing assumption is broken" rather than a timeout.
9. Wait for `data-version` to rise again: updates flow after the reconnect and re-subscribe.
10. Back to Game day (in-app link): the game's card is listed and LIVE.
11. Open Notifications: the touchdown is listed; the badge drops to 0. Reload: still signed in, badge still 0.
12. Sign out: the app shows `/signin`.

On failure, CI uploads the Playwright trace (screenshots, network, socket frames) beside the existing `make logs` output.

### 9.5 Live check (issue #3), the user's

The last task; it blocks the merge unless the user waives it in the PR. The checklist goes in the plan, the results in the PR:

- `make up HELM_ARGS="--set espnPoller.enabled=true"`; two accounts (normal window and a private window), each following one team of a game that is live.
- For about 30 minutes, the UI's score matches espn.com within about 10 s; the clock never ticks between updates; the live look (section 7) shows on each score.
- Each account gets its own team's alerts and the other team's scores; turning a switch off mid-game stops that alert.
- Close the laptop lid for 2 minutes and open it: the banner, a reconnect, a refetch, and no duplicated notification.
- Two tabs of one account: opening Notifications in one clears the badge in both.
- Hide the tab (switch to another tab) for 10 minutes, then come back: the socket did not cycle in the meantime (count the reconnects: the app logs each one to the console, and the network tab lists one `ws` connection). If it did cycle, the fix is to run the ping timer in a Web Worker, whose timers Chrome doesn't throttle the same way; that is a follow-up, not part of 3b.
- Changing the timezone in Settings shifts the shown times; the final shows `Final`.
- Install the app from Chrome on the Mac; it opens standalone.

## 10. Order of work

1. **Gateway serves `WEB_DIR`** (section 4.2, tests 9.1, written first). Delete the course UI. Inline.
2. **Scaffold `web/`:** Vite, React, TypeScript, pnpm pinned, Prettier, Vitest, generated types and `types:check`, the Dockerfile stage, `BUILD_ID` in the Makefile, CI's `web` job, `pnpm dev` and `pnpm dev:mock`. Subagent, then walked through.
3. **HTTP client and errors** (5.1). Inline, test first.
4. **Live connection** (5.2). Inline, test first.
5. **Game store, badge, notifications, known types** (5.3–5.5). Inline, test first.
6. **Shell:** routes, the sign-in guard, v0's styles un-minified and split, toasts, the reconnect banner, dark mode. Subagent, then reviewed.
7. **Sign in and sign up.** Subagent, then reviewed.
8. **Game day and the game page,** with the live look. Subagent, then reviewed.
9. **Following, the add dialog, and the switches.** Subagent, then reviewed.
10. **Notifications and Settings.** Subagent, then reviewed.
11. **PWA:** manifest, icons, `sw.ts`, the update flow, `pnpm test:pwa`. Subagent, then reviewed.
12. **`make e2e`** and CI's `deploy-check` step. Subagent, then walked through.
13. **Docs and the quiz section** (section 11).
14. **Live check:** the user's (9.5).

Then the final review, one fix pass, and the PR; merge when CI is green and the review's findings are fixed. Each task keeps CI green: the gateway serves nothing at `/` between tasks 1 and 2 (no folder in the image yet), which no check depends on.

## 11. Docs to update

- `DESIGN.md` (with this spec): the 3b entry says how the UI is built (Vite, rebuilt from the v0 design), served (by the gateway from the same image, same origin), and checked (Playwright end to end in CI, the live check), and that phone install is first checked in Phase 5 (HTTPS). Phase 6 gains "a public demo build (fake data, static hosting) for when AWS is off".
- `CLAUDE.md` and `AGENTS.md`: `web/` in the layout and stack, the commands (`pnpm dev`, `pnpm dev:mock`, `make e2e`), the course `index.html` gone, current state.
- `docs/api/README.md`: "Running the mock" points at `pnpm dev:mock`.
- `docs/study/phase-3-quiz.md`: a 3b section (same origin and cookies, the reconnect rules, versions and the store, the service worker update, why the gateway serves the files).

## Known gaps accepted in 3b

- **No signed-out browsing.** Every screen needs an account; a public scoreboard would need the socket to allow signed-out tabs.
- **New games appear on a refetch** (every 60 s on Game day), not live: there is no socket message for a game that wasn't on the list.
- **No offline data.** Offline, the shell loads but screens show their error states.
- **Logos load from ESPN's image host,** because that is what the API returns.
- **Phone install** is untested until Phase 5.
- **#24, #25, and the badge's off-by-one** are tolerated, not fixed.
- **A hidden tab's ping can be throttled.** Chrome runs a hidden tab's timers about once a minute after 5 minutes, so the 25 s ping may miss the server's 60 s idle close and the socket may close and reconnect in the background. The ping on return to visible covers the user-visible case; if the live check shows the socket cycling, the ping moves into a Web Worker.
