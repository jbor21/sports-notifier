#!/usr/bin/env bash
# Tests for the AWS scripts' failure paths, with fake aws, terraform, kubectl, helm, security and curl
# on the PATH (no AWS account touched). Run: bash scripts/test-aws-scripts.sh
set -uo pipefail
cd "$(dirname "$0")/.."
root=$(pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
failed=0

ok()  { echo "ok   $1"; }
bad() { echo "FAIL $1: $2"; failed=1; }

# fake <command> <shell snippet>: a stand-in that runs the snippet with the real arguments in "$@" and
# appends its command line to $tmp/calls, so a test can check what was (or was not) run.
fake() {
  mkdir -p "$tmp/bin"
  printf '#!/usr/bin/env bash\necho "%s $*" >> "%s/calls"\n%s\n' "$1" "$tmp" "$2" > "$tmp/bin/$1"
  chmod +x "$tmp/bin/$1"
}

reset() {
  rm -rf "$tmp/bin" "$tmp/calls"
  : > "$tmp/calls"
  fake security 'echo fake-cloudflare-token'
  fake curl 'echo 203.0.113.7'
  fake terraform 'exit 0'
  fake kubectl 'exit 0'
  fake helm 'exit 0'
}

run() { PATH="$tmp/bin:$PATH" "$@" > "$tmp/out" 2>&1; }
called() { grep -q "$1" "$tmp/calls"; }

# Every listing empty: the sweep reports nothing billing.
empty_aws='case "$*" in *describe-instances*|*describe-load-balancers*|*describe-target-groups*|*describe-volumes*|*describe-network-interfaces*|*describe-addresses*|*describe-nat-gateways*|*list-clusters*|*describe-vpcs*) echo "";; esac; exit 0'

reset; fake aws "$empty_aws"
if run bash scripts/aws-leftovers.sh && grep -q "nothing billing" "$tmp/out"; then ok leftovers_clean
else bad leftovers_clean "$(cat "$tmp/out")"; fi

reset; fake aws 'case "$*" in *describe-volumes*) echo "vol-1 10";; esac; exit 0'
if ! run bash scripts/aws-leftovers.sh && grep -q "vol-1" "$tmp/out"; then ok leftovers_volume
else bad leftovers_volume "expected exit 1 naming vol-1: $(cat "$tmp/out")"; fi

# A listing that fails is never "nothing billing".
reset; fake aws 'echo "An error occurred (ExpiredToken)" >&2; exit 255'
if ! run bash scripts/aws-leftovers.sh && ! grep -q "nothing billing" "$tmp/out"; then ok leftovers_aws_error
else bad leftovers_aws_error "$(cat "$tmp/out")"; fi

# No SSO session: stop before Terraform, and say how to log in.
reset; fake aws 'case "$*" in *get-caller-identity*) exit 255;; esac; exit 0'
if ! TAG=pr58 run bash scripts/aws-up.sh && grep -q "aws sso login" "$tmp/out" && ! called terraform; then ok up_no_session
else bad up_no_session "expected exit 1, a login hint, no terraform: $(cat "$tmp/out")"; fi

# The image tag isn't in ECR: stop before Terraform (nothing starts billing for a deploy that can't work).
reset; fake aws 'case "$*" in *get-caller-identity*) echo 250758375664;; *describe-images*) echo "An error occurred (ImageNotFoundException)" >&2; exit 254;; esac; exit 0'
if ! TAG=nosuchtag run bash scripts/aws-up.sh && grep -q "nosuchtag" "$tmp/out" && ! called "terraform.*apply"; then ok up_missing_tag
else bad up_missing_tag "expected exit 1 naming the tag, no apply: $(cat "$tmp/out")"; fi

# ESPN refuses the cluster: the app still goes in, with the poller off and a loud warning (a half-built
# cluster still bills, so aws-up never stops halfway for this).
reset
fake aws 'case "$*" in *get-caller-identity*) echo 250758375664;; *describe-images*) echo sha256:0123abc;; esac; exit 0'
fake terraform 'case "$*" in *"output -raw ecr_url"*) echo 1.dkr.ecr/x;; *"output -raw fqdn"*) echo sports.example.uk;; *"output -raw dump_bucket"*) echo dumps;; esac; exit 0'
fake kubectl 'case "$*" in *"condition=complete"*) exit 1;; *"condition=failed"*) exit 0;; esac; exit 0'
fake curl 'case "$*" in *checkip*) echo 203.0.113.7;; esac; exit 0'
if TAG=pr58 run bash scripts/aws-up.sh && grep -q "^WARNING: ESPN refused requests from AWS" "$tmp/out" \
   && grep "helm upgrade --install sports-notifier" "$tmp/calls" | grep -q "espnPoller.enabled=false"; then ok up_espn_refused
else bad up_espn_refused "expected exit 0, the warning, and the app installed with the poller off: $(cat "$tmp/out")"; fi
# The same run hands the chart the dump bucket, so Postgres restores the last dump on its new volume.
if grep "helm upgrade --install sports-notifier" "$tmp/calls" | grep -q "postgres.restore.bucket=dumps"; then
  ok up_passes_dump_bucket
else bad up_passes_dump_bucket "the app's helm install lacks --set postgres.restore.bucket=dumps"; fi
# A moving tag (pr58) is pinned to the digest ECR holds now, so a re-run after a new CI push rolls out,
# and no node runs an older image cached under the same tag.
if grep "helm upgrade --install sports-notifier" "$tmp/calls" | grep -q "image.tag=pr58@sha256:0123abc"; then
  ok up_pins_digest
else bad up_pins_digest "expected --set image.tag=pr58@sha256:0123abc: $(grep 'helm upgrade --install sports-notifier' "$tmp/calls")"; fi

# aws-down. A healthy Postgres pod and S3 by default; each test breaks one thing. Whatever breaks
# before the destroy, terraform destroy must never run: a failed dump means no teardown.
down_fakes() {  # down_fakes <users> <local size> <s3 size> <pg_dump exit> <restore list> <s3 cp exit>
  reset
  fake aws "case \"\$*\" in *get-caller-identity*) echo 250758375664;; *describe-cluster*) echo ACTIVE;; *'s3 cp'*) cat >/dev/null; exit $6;; *head-object*) echo $3;; esac; exit 0"
  fake terraform 'case "$*" in *"output -raw dump_bucket"*) echo dumps-bucket;; esac; exit 0'
  fake kubectl "case \"\$*\" in *pg_dump*) exit $4;; *'pg_restore --list'*) printf '%b' '$5';; *'count(*)'*) echo $1;; *'stat -c'*) echo $2;; *' cat '*) printf dumpbytes;; *'get pv'*) echo '';; esac; exit 0"
}
full_list='215; 0 1 TABLE DATA public users x\n216; 0 1 TABLE DATA public follows x\n217; 0 1 TABLE DATA public flyway_schema_history x\n'
destroyed() { called "terraform.*destroy"; }

down_fakes 3 9 9 1 "$full_list" 0
if ! run bash scripts/aws-down.sh && ! destroyed; then ok down_dump_fails
else bad down_dump_fails "pg_dump failed but: $(cat "$tmp/out")"; fi

down_fakes 3 9 9 0 "" 0
if ! run bash scripts/aws-down.sh && ! destroyed; then ok down_dump_unparseable
else bad down_dump_unparseable "an empty pg_restore --list passed: $(cat "$tmp/out")"; fi

down_fakes 0 9 9 0 "$full_list" 0
if ! run bash scripts/aws-down.sh && ! destroyed && grep -q ALLOW_EMPTY_DUMP "$tmp/out"; then ok down_zero_users
else bad down_zero_users "a dump with no users passed: $(cat "$tmp/out")"; fi

down_fakes 3 1000 999 0 "$full_list" 0
# ...and the upload went to a staging key: a truncated upload never replaces latest.dump.
if ! run bash scripts/aws-down.sh && ! destroyed && ! called "s3 cp - s3://dumps-bucket/dumps/latest.dump" \
   && ! called copy-object; then ok down_size_mismatch
else bad down_size_mismatch "S3 size 999 vs 1000 passed or reached latest.dump: $(cat "$tmp/out"; cat "$tmp/calls")"; fi

down_fakes 3 9 9 0 "$full_list" 1
if ! run bash scripts/aws-down.sh && ! destroyed; then ok down_upload_fails
else bad down_upload_fails "a failed upload passed: $(cat "$tmp/out")"; fi

# Everything works: dump, uninstall the app and Traefik, delete the claims, destroy, sweep; in that order.
down_fakes 3 9 9 0 "$full_list" 0
fake aws 'case "$*" in *get-caller-identity*) echo 250758375664;; *describe-cluster*) echo ACTIVE;; *"s3 cp"*) cat >/dev/null;; *head-object*) echo 9;; esac; exit 0'
order_ok() {
  local last=0 n
  for step in "pg_dump" "delete kafkatopic" "helm uninstall sports-notifier" "helm uninstall traefik" "delete pvc" "terraform.*destroy" "describe-instances"; do
    n=$(grep -n "$step" "$tmp/calls" | head -1 | cut -d: -f1)
    [[ -n "$n" && "$n" -gt "$last" ]] || return 1
    last=$n
  done
}
if run bash scripts/aws-down.sh && order_ok && called "copy-object.*--key dumps/latest.dump"; then ok down_happy
else bad down_happy "expected dump → delete topics → uninstalls → delete pvc → destroy → sweep: $(cat "$tmp/out"; cat "$tmp/calls")"; fi

# SKIP_DUMP=1 with Postgres's volume still there: refused, whatever the backup's last error was.
down_fakes 3 9 9 0 "$full_list" 0
fake aws 'case "$*" in *get-caller-identity*) echo 250758375664;; *describe-cluster*) echo ACTIVE;; esac; exit 0'
fake kubectl 'case "$*" in *"get pvc data-postgres-0"*) echo persistentvolumeclaim/data-postgres-0;; esac; exit 0'
if ! SKIP_DUMP=1 run bash scripts/aws-down.sh && ! destroyed && grep -q "data-postgres-0" "$tmp/out"; then
  ok down_skip_dump_with_postgres
else bad down_skip_dump_with_postgres "SKIP_DUMP=1 destroyed a cluster holding Postgres data: $(cat "$tmp/out")"; fi

# The escape hatch for a cluster with no Postgres (an aws-up that failed early): no dump, and it says so.
down_fakes 3 9 9 1 "" 1
fake aws 'case "$*" in *get-caller-identity*) echo 250758375664;; *describe-cluster*) echo ACTIVE;; esac; exit 0'
if SKIP_DUMP=1 run bash scripts/aws-down.sh && ! called pg_dump && destroyed && grep -q "latest.dump is unchanged" "$tmp/out"; then ok down_skip_dump
else bad down_skip_dump "$(cat "$tmp/out")"; fi

# An aws-up cancelled during the apply leaves EKS still CREATING (AWS finishes it anyway) and no kubectl
# context. aws-down waits for the cluster (AWS can't delete one mid-create), skips the Kubernetes
# cleanup there is nothing to clean in, and destroys.
down_fakes 3 9 9 1 "" 1
fake aws 'case "$*" in *get-caller-identity*) echo 250758375664;; *describe-cluster*) echo CREATING;; *update-kubeconfig*) exit 1;; esac; exit 0'
fake kubectl 'exit 1'
if SKIP_DUMP=1 run bash scripts/aws-down.sh && called "eks wait cluster-active" && ! called "delete pvc" && destroyed; then ok down_cluster_creating
else bad down_cluster_creating "$(cat "$tmp/out"; cat "$tmp/calls")"; fi

# No cluster at all (gone, or the apply stopped before EKS): straight to destroy for whatever is left.
down_fakes 3 9 9 1 "" 1
fake aws 'case "$*" in *get-caller-identity*) echo 250758375664;; *describe-cluster*) echo "ResourceNotFoundException" >&2; exit 254;; esac; exit 0'
if SKIP_DUMP=1 run bash scripts/aws-down.sh && ! called "delete pvc" && destroyed; then ok down_no_cluster
else bad down_no_cluster "$(cat "$tmp/out")"; fi

# A running cluster this Mac can't reach (its address changed since aws-up): volumes might exist, so
# don't destroy blind; say how to get access back.
down_fakes 3 9 9 1 "" 1
fake aws 'case "$*" in *get-caller-identity*) echo 250758375664;; *describe-cluster*) echo ACTIVE;; esac; exit 0'
fake kubectl 'case "$*" in *"get namespaces"*) exit 1;; esac; exit 0'
if ! SKIP_DUMP=1 run bash scripts/aws-down.sh && ! destroyed && grep -q "make aws-up" "$tmp/out"; then ok down_api_unreachable
else bad down_api_unreachable "$(cat "$tmp/out")"; fi

# A failed backup on a cluster that never got the app says how to get out.
down_fakes 3 9 9 1 "" 1
fake aws 'case "$*" in *get-caller-identity*) echo 250758375664;; *describe-cluster*) echo ACTIVE;; esac; exit 0'
if ! run bash scripts/aws-down.sh && grep -q "SKIP_DUMP=1 make aws-down" "$tmp/out" && ! destroyed; then ok down_backup_fail_hint
else bad down_backup_fail_hint "$(cat "$tmp/out")"; fi

exit $failed
