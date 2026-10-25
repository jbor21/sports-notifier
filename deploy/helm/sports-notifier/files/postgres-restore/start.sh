#!/usr/bin/env bash
# The Postgres container's command when the restore is on. No marker means the first boot never
# finished: an earlier try of this container may have run initdb and then failed partway through
# 10-restore.sh. The image would find that data folder and start it as if it were fine, and a
# container restart doesn't run the init container again, so the wipe happens here too. The dump
# is still in /restore (an emptyDir lives as long as the pod), so the restore simply runs again.
if [ ! -e /var/lib/postgresql/data/restore-done ]; then
  rm -rf "$PGDATA"
fi
exec docker-entrypoint.sh postgres
