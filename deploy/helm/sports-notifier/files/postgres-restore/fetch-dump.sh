#!/bin/sh
# Init container: put the last dump where 10-restore.sh will find it (Phase 5 spec, section 5.2).
# Runs in the aws-cli image, with read-only S3 access through Pod Identity.
set -u
DATA_DIR=${DATA_DIR:-/data}
RESTORE_DIR=${RESTORE_DIR:-/restore}

# The marker means a first boot finished (restored, or started empty because there was no dump).
# This is a restart of a live database: never fetch or restore over it.
if [ -e "$DATA_DIR/restore-done" ]; then
  echo "already restored or started fresh"
  exit 0
fi

# No marker: whatever data folder is here comes from an attempt that never finished.
rm -rf "$DATA_DIR/pgdata"

# Only "no such object" means start empty. Any other error (no credentials, 403, network) fails the
# pod, which retries, rather than starting an empty database that would later be dumped over the
# real one. Telling 404 from 403 needs s3:ListBucket (infra/cluster/restore.tf).
if ! err=$(aws s3api head-object --bucket "$BUCKET" --key "$KEY" 2>&1 >/dev/null); then
  case "$err" in
    *"(404)"*) echo "no dump; starting empty"; exit 0;;
    *) echo "cannot read s3://$BUCKET/$KEY: $err" >&2; exit 1;;
  esac
fi

echo "downloading s3://$BUCKET/$KEY"
aws s3 cp "s3://$BUCKET/$KEY" "$RESTORE_DIR/latest.dump" || exit 1
