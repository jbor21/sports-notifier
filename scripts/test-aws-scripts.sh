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
fake aws 'case "$*" in *get-caller-identity*) echo 250758375664;; *describe-images*) echo ok;; esac; exit 0'
fake terraform 'case "$*" in *"output -raw ecr_url"*) echo 1.dkr.ecr/x;; *"output -raw fqdn"*) echo sports.example.uk;; *"output -raw dump_bucket"*) echo dumps;; esac; exit 0'
fake kubectl 'case "$*" in *"condition=complete"*) exit 1;; *"condition=failed"*) exit 0;; esac; exit 0'
fake curl 'case "$*" in *checkip*) echo 203.0.113.7;; esac; exit 0'
if TAG=pr58 run bash scripts/aws-up.sh && grep -q "^WARNING: ESPN refused requests from AWS" "$tmp/out" \
   && grep "helm upgrade --install sports-notifier" "$tmp/calls" | grep -q "espnPoller.enabled=false"; then ok up_espn_refused
else bad up_espn_refused "expected exit 0, the warning, and the app installed with the poller off: $(cat "$tmp/out")"; fi

exit $failed
