#!/usr/bin/env bash
# Proof that traces and metrics reach the observability pod end to end (Phase 4.3 spec, 8.4). Run after
# something has gone through the pipeline (make check, make simulate). For the last 15 minutes:
#   1. Tempo has a trace with spans from event-processing, stage1-worker, stage2-worker, and gateway,
#      rooted in a poll span from the checker, replay, simulator, or ESPN poller: the trace id crossed
#      the HTTP header, the outbox, both Kafka topics, and the pub/sub body;
#   2. Prometheus has pipeline.latency for all four stages and consumer lag for all three groups;
#   3. Prometheus has an exemplar on the gateway's latency whose trace id Tempo can open.
# Retries for up to 30 s (metrics are pushed every 5 s, spans in batches). Exits 1 naming what is missing.
set -uo pipefail
cd "$(dirname "$0")/.."

KUBE_CONTEXT=${KUBE_CONTEXT:-k3d-sports-notifier}
TEMPO_PORT=${TEMPO_PORT:-13200}
PROM_PORT=${PROM_PORT:-19090}
TRIES=${TRACE_CHECK_TRIES:-6}

kubectl --context "$KUBE_CONTEXT" get svc lgtm >/dev/null 2>&1 \
  || { echo "trace-check: no svc/lgtm (observability.enabled=false?)" >&2; exit 1; }
kubectl --context "$KUBE_CONTEXT" port-forward svc/lgtm "$TEMPO_PORT:3200" "$PROM_PORT:9090" >/dev/null 2>&1 &
pf=$!
trap 'kill $pf 2>/dev/null' EXIT
for _ in $(seq 1 20); do curl -sf -o /dev/null "http://localhost:$PROM_PORT/-/ready" && break; sleep 0.5; done

tempo="http://localhost:$TEMPO_PORT"
prom="http://localhost:$PROM_PORT"

# The values of one label across a PromQL query's result, one per line.
labels() { curl -s --get "$prom/api/v1/query" --data-urlencode "query=$1" | jq -r ".data.result[].metric.$2" 2>/dev/null; }

check_once() {
  local end start missing="" ids id trace services names found="" want s
  end=$(date +%s); start=$((end - 900))

  # 1. One trace through every hop.
  ids=$(curl -s --get "$tempo/api/search" --data-urlencode 'q={ resource.service.name = "gateway" && name = "send" }' \
          --data-urlencode "start=$start" --data-urlencode "end=$end" --data-urlencode "limit=20" | jq -r '.traces[]?.traceID')
  for id in $ids; do
    trace=$(curl -s "$tempo/api/traces/$id")
    services=$(jq -r '[.. | objects | select(.key? == "service.name") | .value.stringValue] | unique | .[]' <<< "$trace")
    names=$(jq -r '[.. | objects | select(has("spanId") and has("name")) | .name] | unique | .[]' <<< "$trace")
    want=ok
    for s in event-processing stage1-worker stage2-worker gateway; do grep -qx "$s" <<< "$services" || want=""; done
    grep -qxE "checker|replay|simulator|espn-poller" <<< "$services" || want=""
    grep -qx poll <<< "$names" || want=""
    if [ -n "$want" ]; then found=$id; break; fi
  done
  [ -n "$found" ] || missing="$missing
  no trace from poll to send through event-processing, stage1-worker, stage2-worker, gateway (searched $(wc -w <<< "$ids" | tr -d ' ') traces)"

  # 2. Latency at every stage, lag for every group.
  local stages groups
  stages=$(labels 'count by (stage) (pipeline_latency_seconds_bucket)' stage)
  for s in accepted stage1 stage2 gateway; do grep -qx "$s" <<< "$stages" || missing="$missing
  no pipeline_latency samples for stage=$s"; done
  groups=$(labels 'count by (consumergroup) (kafka_consumergroup_lag)' consumergroup)
  for s in stage1 stage2 history; do grep -qx "$s" <<< "$groups" || missing="$missing
  no kafka_consumergroup_lag for group $s"; done

  # 3. An exemplar that opens a trace.
  local ex
  ex=$(curl -s --get "$prom/api/v1/query_exemplars" --data-urlencode 'query=pipeline_latency_seconds_bucket{stage="gateway"}' \
         --data-urlencode "start=$start" --data-urlencode "end=$end" | jq -r '[.data[]?.exemplars[]?.labels.trace_id] | last // empty')
  if [ -z "$ex" ]; then
    missing="$missing
  no exemplar with a trace_id on pipeline_latency{stage=\"gateway\"}"
  elif [ "$(curl -s -o /dev/null -w '%{http_code}' "$tempo/api/traces/$ex")" != 200 ]; then
    missing="$missing
  the gateway exemplar's trace $ex is not in Tempo"
  fi

  if [ -z "$missing" ]; then
    echo "trace-check: PASS  trace $found spans poll → accept → produce → fanout → deliver → send"
    echo "trace-check: PASS  pipeline_latency at accepted, stage1, stage2, gateway; lag for stage1, stage2, history"
    echo "trace-check: PASS  gateway exemplar $ex opens in Tempo"
    return 0
  fi
  last_missing=$missing
  return 1
}

last_missing=""
for i in $(seq 1 "$TRIES"); do
  check_once && exit 0
  [ "$i" = "$TRIES" ] || sleep 5
done
echo "trace-check: FAIL$last_missing" >&2
exit 1
