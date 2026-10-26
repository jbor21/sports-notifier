#!/usr/bin/env bash
# The Postgres restore's scripts (Phase 5 spec, section 5.2), on the laptop, no AWS:
#   1. fetch-dump.sh against a fake `aws`: marker, 404, other S3 errors, a download.
#   2. start.sh + 10-restore.sh in a real postgres:17 container (needs Docker): a good dump restores
#      and leaves the marker; a restart keeps the data; a corrupt dump never starts, not even after
#      a container restart (init containers don't run again then); no dump starts empty.
# Run: bash scripts/test-restore.sh [fetch|postgres]   (both when no argument)
set -uo pipefail
cd "$(dirname "$0")/.."
files=deploy/helm/sports-notifier/files/postgres-restore
tmp=$(mktemp -d)
failed=0
ok()  { echo "ok   $1"; }
bad() { echo "FAIL $1: $2"; failed=1; }

# ---- 1. fetch-dump.sh ----
fetch_tests() {
  mkdir -p "$tmp/bin"
  # The fake aws logs its arguments; HEAD says what head-object does (found | 404 | 403), CP_FAIL=1 fails cp.
  cat > "$tmp/bin/aws" <<'EOF'
#!/bin/sh
echo "aws $*" >> "$CALLS"
case "$*" in
  *head-object*)
    case "$HEAD" in
      found) echo '{"ContentLength": 10}'; exit 0;;
      404) echo "An error occurred (404) when calling the HeadObject operation: Not Found" >&2; exit 254;;
      *) echo "An error occurred (403) when calling the HeadObject operation: Forbidden" >&2; exit 254;;
    esac;;
  "s3 cp "*) [ "${CP_FAIL:-0}" = 1 ] && exit 1; for last; do :; done; echo dump > "$last"; exit 0;;
esac
exit 0
EOF
  chmod +x "$tmp/bin/aws"

  fetch() {  # fetch <HEAD> [marker] : fresh /data and /restore, run the script, rc in $rc
    rm -rf "$tmp/data" "$tmp/restore" "$tmp/calls" "$tmp/out"
    mkdir -p "$tmp/data/pgdata" "$tmp/restore"
    echo half > "$tmp/data/pgdata/PG_VERSION"
    [ "${2:-}" = marker ] && touch "$tmp/data/restore-done"
    PATH="$tmp/bin:$PATH" CALLS="$tmp/calls" HEAD=$1 BUCKET=b KEY=dumps/latest.dump \
      DATA_DIR="$tmp/data" RESTORE_DIR="$tmp/restore" sh "$files/fetch-dump.sh" > "$tmp/out" 2>&1
    rc=$?
  }

  fetch found marker
  if [ $rc = 0 ] && grep -q "already restored or started fresh" "$tmp/out" && [ ! -e "$tmp/calls" ] \
     && [ -e "$tmp/data/pgdata/PG_VERSION" ]; then ok fetch_marker_noop
  else bad fetch_marker_noop "rc=$rc, expected no S3 call and pgdata kept: $(cat "$tmp/out")"; fi

  fetch 404
  if [ $rc = 0 ] && grep -q "no dump; starting empty" "$tmp/out" && [ ! -e "$tmp/data/pgdata" ] \
     && ! grep -q " s3 cp " "$tmp/calls" && [ ! -e "$tmp/restore/latest.dump" ]; then ok fetch_404_empty
  else bad fetch_404_empty "rc=$rc, expected pgdata wiped and no download: $(cat "$tmp/out")"; fi

  fetch 403
  if [ $rc != 0 ] && grep -q "403" "$tmp/out" && ! grep -q " s3 cp " "$tmp/calls" \
     && [ ! -e "$tmp/restore/latest.dump" ]; then ok fetch_other_error_fails
  else bad fetch_other_error_fails "rc=$rc, expected a failure and no download: $(cat "$tmp/out")"; fi

  fetch found
  if [ $rc = 0 ] && grep -q "s3 cp s3://b/dumps/latest.dump $tmp/restore/latest.dump" "$tmp/calls" \
     && [ -s "$tmp/restore/latest.dump" ] && [ ! -e "$tmp/data/pgdata" ]; then ok fetch_downloads
  else bad fetch_downloads "rc=$rc: $(cat "$tmp/out") / $(cat "$tmp/calls" 2>/dev/null)"; fi

  CP_FAIL=1 fetch found
  if [ $rc != 0 ]; then ok fetch_cp_fail
  else bad fetch_cp_fail "a failed download exited 0"; fi
}

# ---- 2. start.sh + 10-restore.sh in postgres:17 ----
postgres_tests() {
  if ! docker info >/dev/null 2>&1; then bad postgres "Docker is not running"; return; fi
  img=postgres:17 n=sn-restore-test
  cleanup_pg() { docker rm -f $n $n-src >/dev/null 2>&1; docker volume rm -f $n-data >/dev/null 2>&1; }
  trap 'cleanup_pg; rm -rf "$tmp"' EXIT
  cleanup_pg

  # A real dump of a database shaped like ours: a users table with one row.
  docker run -d --name $n-src -e POSTGRES_USER=sportsnotifier -e POSTGRES_PASSWORD=x \
    -e POSTGRES_DB=sportsnotifier $img >/dev/null
  until docker exec $n-src pg_isready -h 127.0.0.1 -U sportsnotifier >/dev/null 2>&1; do sleep 1; done
  docker exec $n-src psql -q -U sportsnotifier -d sportsnotifier \
    -c "create table users(id int primary key, username text); insert into users values (1, 'kept');"
  mkdir -p "$tmp/good" "$tmp/corrupt" "$tmp/none" "$tmp/empty"
  : > "$tmp/empty/latest.dump"
  docker exec $n-src pg_dump -Fc -U sportsnotifier sportsnotifier > "$tmp/good/latest.dump"
  docker rm -f $n-src >/dev/null
  head -c 1000 /dev/urandom > "$tmp/corrupt/latest.dump"

  # The volume as EKS gives it: root-owned, with fsGroup 999 (postgres) making it group-writable.
  fresh_volume() {  # the container goes first: a volume in use can't be removed
    docker rm -f $n >/dev/null 2>&1
    docker volume rm -f $n-data >/dev/null 2>&1
    docker volume create $n-data >/dev/null
    docker run --rm -q -v $n-data:/v alpine:3.22 sh -c 'chown root:999 /v && chmod 2775 /v' >/dev/null
  }
  # Start the Postgres container the way the chart does; $1 = the folder the init container filled.
  start_pg() {
    docker rm -f $n >/dev/null 2>&1
    docker run -d --name $n -e PGDATA=/var/lib/postgresql/data/pgdata -e POSTGRES_DB=sportsnotifier \
      -e POSTGRES_USER=sportsnotifier -e POSTGRES_PASSWORD=y \
      -v $n-data:/var/lib/postgresql/data -v "$1":/restore:ro \
      -v "$PWD/$files/10-restore.sh":/docker-entrypoint-initdb.d/10-restore.sh:ro \
      -v "$PWD/$files/start.sh":/scripts/start.sh:ro \
      --entrypoint bash $img /scripts/start.sh >/dev/null
  }
  # Ready (TCP, like the chart's probe) within 60 s, or exited: prints ready | exited:<code>.
  wait_pg() {
    for _ in $(seq 1 60); do
      if [ "$(docker inspect -f '{{.State.Running}}' $n)" != true ]; then
        echo "exited:$(docker inspect -f '{{.State.ExitCode}}' $n)"; return; fi
      docker exec $n pg_isready -h 127.0.0.1 -U sportsnotifier >/dev/null 2>&1 && { echo ready; return; }
      sleep 1
    done
    echo timeout
  }
  sql() { docker exec $n psql -tA -U sportsnotifier -d sportsnotifier -c "$1" 2>&1; }
  marker() { docker exec $n test -e /var/lib/postgresql/data/restore-done && echo yes || echo no; }

  fresh_volume; start_pg "$tmp/good"
  st=$(wait_pg)
  if [ "$st" = ready ] && [ "$(sql 'select username from users')" = kept ] && [ "$(marker)" = yes ]; then
    ok pg_restores_dump
  else bad pg_restores_dump "state $st, users: $(sql 'select username from users'); $(docker logs $n 2>&1 | tail -5)"; fi

  # A restart of a live database: the marker is there, nothing is wiped or restored again.
  sql "insert into users values (2, 'after')" >/dev/null
  docker restart $n >/dev/null
  st=$(wait_pg)
  if [ "$st" = ready ] && [ "$(sql 'select count(*) from users')" = 2 ]; then ok pg_restart_keeps_data
  else bad pg_restart_keeps_data "state $st, count $(sql 'select count(*) from users')"; fi

  # A corrupt dump: the first start fails before Ready and leaves no marker...
  fresh_volume; start_pg "$tmp/corrupt"
  st=$(wait_pg)
  if [[ $st == exited:* && $st != exited:0 ]]; then ok pg_corrupt_fails
  else bad pg_corrupt_fails "state $st; $(docker logs $n 2>&1 | tail -5)"; fi
  # ...and the container's restart (same volume, same /restore, no init container) fails again
  # instead of finding an initialized data folder and serving it.
  docker start $n >/dev/null
  st=$(wait_pg)
  if [[ $st == exited:* && $st != exited:0 ]]; then ok pg_corrupt_restart_fails
  else bad pg_corrupt_restart_fails "state $st: a half-restored database started"; fi

  # An empty dump file: fetch-dump writes the file only when S3 had the object, so 0 bytes means a bad
  # dump, never "no dump". It must fail like a corrupt one, not start empty with the marker set.
  fresh_volume; start_pg "$tmp/empty"
  st=$(wait_pg)
  if [[ $st == exited:* && $st != exited:0 ]]; then ok pg_empty_dump_fails
  else bad pg_empty_dump_fails "state $st, marker $(marker): an empty dump started an empty database"; fi

  # No dump (the first apply ever): starts empty and leaves the marker.
  fresh_volume; start_pg "$tmp/none"
  st=$(wait_pg)
  # psql exits 1 on the (expected) error, so match its output, not a pipeline's status.
  if [ "$st" = ready ] && [ "$(marker)" = yes ] && [[ $(sql 'select 1 from users') == *"does not exist"* ]]; then
    ok pg_no_dump_empty
  else bad pg_no_dump_empty "state $st, marker $(marker)"; fi
}

case "${1:-all}" in
  fetch) fetch_tests;;
  postgres) postgres_tests;;
  *) fetch_tests; postgres_tests;;
esac
rm -rf "$tmp"
exit $failed
