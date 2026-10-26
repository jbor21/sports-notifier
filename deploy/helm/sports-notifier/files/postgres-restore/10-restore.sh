#!/usr/bin/env bash
# Runs once, when the image creates an empty database, while Postgres listens only on its local
# socket: the services can't connect (and run Flyway) until the restore is over.
set -euo pipefail
# fetch-dump writes the file only when S3 had the object, so an empty one is a bad dump, not "no dump":
# fail (no marker) rather than start empty.
if [ -e /restore/latest.dump ] && [ ! -s /restore/latest.dump ]; then
  echo "the dump in S3 is empty; not starting an empty database" >&2
  exit 1
fi
if [ -s /restore/latest.dump ]; then
  echo "restoring /restore/latest.dump"
  pg_restore --exit-on-error --no-owner --role=sportsnotifier -U sportsnotifier -d sportsnotifier \
    /restore/latest.dump
fi
# Only now: a failed restore leaves no marker, so the next start wipes and tries again.
touch /var/lib/postgresql/data/restore-done
