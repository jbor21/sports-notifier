#!/usr/bin/env bash
# Can we still read ESPN's scoreboards? Tries each host x User-Agent pair for the four leagues and
# prints a table (also to the GitHub Actions run summary when there is one).
# Fails only if the pair the app uses by default (site.web.api.espn.com + our honest agent) does
# not return 200 for every league: that is the one that would break the poller.
set -uo pipefail

DEFAULT_HOST="site.web.api.espn.com"
DEFAULT_AGENT="sports-notifier/1.0 (+https://github.com/boboborenstein-collab/sports-notifier)"
HOSTS=("site.api.espn.com" "site.web.api.espn.com")
AGENTS=(
  "$DEFAULT_AGENT"
  "Java-http-client/17"
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128 Safari/537.36"
)
LEAGUES=("basketball/nba" "football/nfl" "baseball/mlb" "hockey/nhl")

body=$(mktemp); trap 'rm -f "$body"' EXIT
table="| host | user agent | league | HTTP | games |\n|---|---|---|---|---|\n"
default_ok=1

for host in "${HOSTS[@]}"; do
  for agent in "${AGENTS[@]}"; do
    for path in "${LEAGUES[@]}"; do
      code=$(curl -s -m 15 -o "$body" -w '%{http_code}' -A "$agent" \
        "https://$host/apis/site/v2/sports/$path/scoreboard")
      games=$(jq -r '.events | length' "$body" 2>/dev/null || echo "-")
      table+="| $host | ${agent%% *} | ${path#*/} | $code | $games |\n"
      if [[ "$host" == "$DEFAULT_HOST" && "$agent" == "$DEFAULT_AGENT" && "$code" != 200 ]]; then
        default_ok=0
      fi
    done
  done
done

printf "%b" "$table"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  { echo "## ESPN reachability ($(date -u +%Y-%m-%dT%H:%MZ))"; printf "%b" "$table"; } >> "$GITHUB_STEP_SUMMARY"
fi

if [[ "$default_ok" != 1 ]]; then
  echo "ESPN CHECK FAIL: the default host/User-Agent pair did not get 200 for every league" >&2
  exit 1
fi
echo "ESPN CHECK PASS"
