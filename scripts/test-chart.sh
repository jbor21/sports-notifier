#!/usr/bin/env bash
# The chart rendered with the laptop's and the AWS values: what each environment must and must not get.
# No cluster needed (helm template). Run: bash scripts/test-chart.sh
set -uo pipefail
cd "$(dirname "$0")/.."
chart=deploy/helm/sports-notifier
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
failed=0

ok()  { echo "ok   $1"; }
bad() { echo "FAIL $1: $2"; failed=1; }

render() {  # render <out file> <helm args...>
  local out=$1
  shift
  helm template t "$chart" "$@" > "$out" 2> "$out.err" || { cat "$out.err"; return 1; }
}

# One resource from a multi-document render: the document whose kind and metadata.name match.
doc() {  # doc <file> <kind> <name>
  awk -v kind="$2" -v name="$3" '
    /^---/ { if (k && n) print buf; buf=""; k=0; n=0; next }
    { buf = buf $0 "\n" }
    $0 ~ "^kind: " kind "$" { k=1 }
    $0 ~ "^  name: " name "$" { n=1 }
    END { if (k && n) print buf }' "$1"
}

render "$tmp/local.yaml" -f "$chart/values-local.yaml" || { echo "FAIL local render"; exit 1; }
render "$tmp/aws.yaml" -f "$chart/values-aws.yaml" --set image.repository=x || { echo "FAIL aws render"; exit 1; }

# The laptop: one broker, no zones, Traefik in kube-system.
if ! grep -q "rack:" "$tmp/local.yaml" && ! grep -q "topologySpreadConstraints" "$tmp/local.yaml" \
   && doc "$tmp/local.yaml" NetworkPolicy gateway-from-ingress | grep -q "kubernetes.io/metadata.name: kube-system"; then
  ok local_no_rack
else bad local_no_rack "rack/spread rendered on the laptop, or Traefik's namespace isn't kube-system"; fi

# AWS: three brokers, one per zone, each partition's copies in different zones, Traefik in traefik.
pool=$(doc "$tmp/aws.yaml" KafkaNodePool dual-role)
kafka=$(doc "$tmp/aws.yaml" Kafka t)
if grep -q "replicas: 3" <<< "$pool" \
   && grep -q "topologyKey: topology.kubernetes.io/zone" <<< "$pool" \
   && grep -q "whenUnsatisfiable: DoNotSchedule" <<< "$pool" \
   && grep -A1 "rack:" <<< "$kafka" | grep -q "topologyKey: topology.kubernetes.io/zone" \
   && grep -q "min.insync.replicas: 2" <<< "$kafka" \
   && grep -q "default.replication.factor: 3" <<< "$kafka" \
   && doc "$tmp/aws.yaml" NetworkPolicy gateway-from-ingress | grep -q "kubernetes.io/metadata.name: traefik"; then
  ok aws_rack
else bad aws_rack "expected 3 zone-spread brokers, rack by zone, RF 3, min ISR 2, Traefik in traefik"; fi

# #51 on both: the key Secret exists and the gateway reads it.
if doc "$tmp/aws.yaml" Secret checker-key | grep -q "key:" \
   && doc "$tmp/aws.yaml" Deployment gateway | grep -q "name: CHECKER_KEY"; then
  ok aws_checker_key
else bad aws_checker_key "checker-key Secret or the gateway's CHECKER_KEY missing"; fi

# AWS runs the real poller, with secure cookies (no COOKIE_SECURE=false override).
if doc "$tmp/aws.yaml" Deployment espn-poller | grep -q "kind: Deployment" \
   && ! doc "$tmp/aws.yaml" Deployment gateway | grep -q 'COOKIE_SECURE'; then
  ok aws_poller_and_cookies
else bad aws_poller_and_cookies "expected the ESPN poller on and no COOKIE_SECURE override on AWS"; fi

exit $failed
