#!/usr/bin/env bash
# Runs once, when the image creates an empty database, while Postgres listens only on its local
# socket: the services can't connect (and run Flyway) until the restore is over.
set -euo pipefail
if [ -s /restore/latest.dump ]; then
  echo "restoring /restore/latest.dump"
  pg_restore --exit-on-error --no-owner --role=sportsnotifier -U sportsnotifier -d sportsnotifier \
    /restore/latest.dump
fi
# Only now: a failed restore leaves no marker, so the next start wipes and tries again.
touch /var/lib/postgresql/data/restore-done
