#!/usr/bin/env bash
# make aws-backup: dump Postgres to s3://<dump bucket>/dumps/latest.dump and prove the dump is good
# (spec, section 5.1, step 1). make aws-down runs this first and destroys nothing unless it passes.
# The bucket keeps earlier dumps as old versions for 30 days.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/aws-lib.sh

aws_require_session
fail() { echo "BACKUP FAILED: $*" >&2; exit 1; }

terraform -chdir=infra/base init -input=false -backend-config="bucket=$(state_bucket)" >/dev/null
bucket=$(terraform -chdir=infra/base output -raw dump_bucket)
key=dumps/latest.dump
file=/tmp/sportsnotifier.dump
pg() { kc exec postgres-0 -c postgres -- "$@"; }

echo "== pg_dump in the Postgres pod"
pg pg_dump -Fc -U sportsnotifier -f "$file" sportsnotifier || fail "pg_dump failed"

# The dump must parse, and hold the tables an account needs (its schema history too, so Flyway knows
# where a restored database stands).
list=$(pg pg_restore --list "$file") || fail "pg_restore cannot read the dump"
for table in users follows flyway_schema_history; do
  grep -q "TABLE DATA public $table " <<< "$list" || fail "the dump has no data section for $table"
done

# An empty users table means something is wrong (your own account should be there); saving it as
# latest.dump would make the next aws-up restore nothing.
users=$(pg psql -U sportsnotifier -d sportsnotifier -tAc 'SELECT count(*) FROM users' | tr -d '[:space:]')
if [[ "${ALLOW_EMPTY_DUMP:-}" != 1 ]] && ! [[ "$users" =~ ^[0-9]+$ && "$users" -gt 0 ]]; then
  fail "the users table has ${users:-?} rows. If an empty database is really what you want to keep, run again with ALLOW_EMPTY_DUMP=1"
fi

size=$(pg stat -c %s "$file" | tr -d '[:space:]')
echo "== upload ($size bytes) to s3://$bucket/$key"
pg cat "$file" | aws s3 cp - "s3://$bucket/$key" || fail "upload to S3 failed"
stored=$(aws s3api head-object --bucket "$bucket" --key "$key" --query ContentLength --output text | tr -d '[:space:]')
[[ "$stored" == "$size" ]] || fail "S3 holds $stored bytes, the dump is $size"

echo "Backup OK: $users users, $size bytes in s3://$bucket/$key"
