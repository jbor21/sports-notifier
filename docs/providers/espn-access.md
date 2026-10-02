# ESPN access

The app reads ESPN's public scoreboard JSON. It is unofficial: ESPN changes what it allows without notice. This file records what we measured. The daily `ESPN check` workflow (`.github/workflows/espn-check.yml`, script `scripts/espn-check.sh`) re-measures it from a GitHub runner, which sits in a data center like AWS will.

## Defaults the app uses

- Host: `site.web.api.espn.com` (setting `ESPN_HOST`, Helm `espn.host`)
- User-Agent: `sports-notifier/1.0 (+https://github.com/boboborenstein-collab/sports-notifier)` (setting `ESPN_USER_AGENT`, Helm `espn.userAgent`)

Why: on 2026-10-01, from a cloud address, `site.api.espn.com` answered 403 while `site.web.api.espn.com` answered 200 to the same request, and developers reported ESPN refusing browser-imitating User-Agents while honest client agents got through.

## 2026-10-02, from a home connection (laptop)

Every host and User-Agent pair returned 200 for every league.

| host | user agent | league | HTTP | games |
|---|---|---|---|---|
| site.api.espn.com | sports-notifier/1.0 | nba | 200 | 1 |
| site.api.espn.com | sports-notifier/1.0 | nfl | 200 | 16 |
| site.api.espn.com | sports-notifier/1.0 | mlb | 200 | 4 |
| site.api.espn.com | sports-notifier/1.0 | nhl | 200 | 5 |
| site.api.espn.com | Java-http-client/17 | nba | 200 | 1 |
| site.api.espn.com | Java-http-client/17 | nfl | 200 | 16 |
| site.api.espn.com | Java-http-client/17 | mlb | 200 | 4 |
| site.api.espn.com | Java-http-client/17 | nhl | 200 | 5 |
| site.api.espn.com | Mozilla/5.0 | nba | 200 | 1 |
| site.api.espn.com | Mozilla/5.0 | nfl | 200 | 16 |
| site.api.espn.com | Mozilla/5.0 | mlb | 200 | 4 |
| site.api.espn.com | Mozilla/5.0 | nhl | 200 | 5 |
| site.web.api.espn.com | sports-notifier/1.0 | nba | 200 | 1 |
| site.web.api.espn.com | sports-notifier/1.0 | nfl | 200 | 16 |
| site.web.api.espn.com | sports-notifier/1.0 | mlb | 200 | 4 |
| site.web.api.espn.com | sports-notifier/1.0 | nhl | 200 | 5 |
| site.web.api.espn.com | Java-http-client/17 | nba | 200 | 1 |
| site.web.api.espn.com | Java-http-client/17 | nfl | 200 | 16 |
| site.web.api.espn.com | Java-http-client/17 | mlb | 200 | 4 |
| site.web.api.espn.com | Java-http-client/17 | nhl | 200 | 5 |
| site.web.api.espn.com | Mozilla/5.0 | nba | 200 | 1 |
| site.web.api.espn.com | Mozilla/5.0 | nfl | 200 | 16 |
| site.web.api.espn.com | Mozilla/5.0 | mlb | 200 | 4 |
| site.web.api.espn.com | Mozilla/5.0 | nhl | 200 | 5 |

## From a GitHub runner (data center)

Not run yet: it needs the branch pushed. Run it with `gh workflow run espn-check.yml --ref <branch>` and copy the table from the run's summary page here.

## If the cloud gets refused

1. Switch the other host or User-Agent in the Helm values. No code change.
2. Run the poller on a home machine and have it POST events to the cloud gateway over authenticated HTTPS. It is the only piece that talks to ESPN (the gateway's upcoming-games list would need the same).
3. Use a league's own feed for that sport (`statsapi.mlb.com`, `api-web.nhle.com`), as one more `ScoreFeed`.

Not an option: residential proxies.
