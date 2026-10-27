# Fault primitives for the chaos suite (Phase 4.2 spec, section 2). Sourced by scripts/chaos.sh, or by
# hand: `source scripts/chaos-lib.sh; chaos_first_pod stage2-worker`.
#
# The crash and the cut reach into the machine for crictl. On k3d the machine is a Docker container on
# the Mac (docker exec); on EKS it is an EC2 instance, reached through a privileged debug pod on it
# (Phase 6.1 spec, 3). The scenarios stay the same.

KUBE_CONTEXT=${KUBE_CONTEXT:-k3d-sports-notifier}
CLUSTER=${CLUSTER:-sports-notifier}
RELEASE=${RELEASE:-sports-notifier}
CHAOS_NODE=${CHAOS_NODE:-k3d-$CLUSTER-server-0}
if [[ "$KUBE_CONTEXT" == k3d-* ]]; then CHAOS_PLATFORM=k3d; else CHAOS_PLATFORM=eks; fi
CHAOS_DEBUG_IMAGE=${CHAOS_DEBUG_IMAGE:-busybox:1.37.0}
CHAOS_NODE_RUN_SECONDS=${CHAOS_NODE_RUN_SECONDS:-60}
# EKS only: after the cut's policy, also kill the pod's open Kafka connections (spec 3.2; measured in
# the first AWS session whether the policy alone cuts them).
CHAOS_CUT_KILL=${CHAOS_CUT_KILL:-1}
CHAOS_CRICTL="crictl --runtime-endpoint unix:///run/containerd/containerd.sock"

_k() { kubectl --context "$KUBE_CONTEXT" "$@"; }

chaos_node_of() { _k get pod "$1" -o jsonpath='{.spec.nodeName}'; }

# chaos_node_run <node> <shell command>: runs the command as root in the machine's own filesystem
# (chroot /host) through `kubectl debug node`, whose sysadmin profile is privileged and shares the
# machine's processes and network. Prints its output, deletes the pod, returns the command's exit code.
chaos_node_run() {
  local node=$1 cmd=$2 out pod phase="" code
  out=$(_k debug "node/$node" --profile=sysadmin --image="$CHAOS_DEBUG_IMAGE" -- chroot /host sh -c "$cmd" 2>&1) \
    || { echo "chaos_node_run: could not start a debug pod on $node: $out" >&2; return 1; }
  pod=$(grep -oE 'node-debugger-[a-z0-9.-]+[a-z0-9]' <<< "$out" | head -1)
  [ -n "$pod" ] || { echo "chaos_node_run: no debug pod named in: $out" >&2; return 1; }
  for _ in $(seq 1 "$CHAOS_NODE_RUN_SECONDS"); do
    phase=$(_k get pod "$pod" -o jsonpath='{.status.phase}' 2>/dev/null || true)
    [ "$phase" = Succeeded ] || [ "$phase" = Failed ] && break
    sleep 1
  done
  if [ "$phase" != Succeeded ] && [ "$phase" != Failed ]; then
    echo "chaos_node_run: debug pod $pod did not finish within $CHAOS_NODE_RUN_SECONDS s (phase ${phase:-unknown})" >&2
    _k delete pod "$pod" --ignore-not-found --wait=false >/dev/null 2>&1 || true
    return 1
  fi
  _k logs "$pod" 2>/dev/null || true
  code=$(_k get pod "$pod" -o jsonpath='{.status.containerStatuses[0].state.terminated.exitCode}' 2>/dev/null || true)
  _k delete pod "$pod" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  return "${code:-1}"
}

# Deletes every debug pod a run left behind (an interrupt mid-command): they run privileged.
chaos_debug_cleanup() {
  local p
  for p in $(_k get pods -o name 2>/dev/null | grep '^pod/node-debugger-' || true); do
    _k delete "$p" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  done
}

# --- Brokers (one on k3d, three on EKS). Pod <release>-dual-role-<n> is Kafka node id <n>. ---

# Set by the broker crash to the broker it killed, so admin commands go elsewhere; empty otherwise.
CHAOS_BROKER_AT_FAULT=${CHAOS_BROKER_AT_FAULT:-}

chaos_brokers() {
  _k get pods -l strimzi.io/pool-name=dual-role -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | sort
}

chaos_ready() { [ "$(_k get pod "$1" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)" = True ]; }

# The first Ready broker that is not the one at fault. Fails if there is none.
chaos_admin_broker() {
  local b
  for b in $(chaos_brokers); do
    [ "$b" != "$CHAOS_BROKER_AT_FAULT" ] && chaos_ready "$b" && { echo "$b"; return 0; }
  done
  echo "chaos_admin_broker: no Ready broker to run Kafka commands on" >&2
  return 1
}

# chaos_kafka bin/kafka-….sh …: a Kafka admin command on the admin broker.
chaos_kafka() {
  local b
  b=$(chaos_admin_broker) || return 1
  _k exec "$b" -c kafka -- "$@"
}

# kafka-topics' describe lines on stdin → "<topic> <partition> <leader id>" for the two pipeline topics.
chaos_parse_leaders() {
  awk '{ t = p = l = ""
         for (i = 1; i < NF; i++) {
           if ($i == "Topic:") t = $(i + 1)
           if ($i == "Partition:") p = $(i + 1)
           if ($i == "Leader:") l = $(i + 1)
         }
         if (p != "" && (t == "game-events" || t == "notifications")) print t, p, (l == "none" ? -1 : l) }'
}

chaos_leaders() {
  local out
  out=$(chaos_kafka bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe) || return 1
  chaos_parse_leaders <<< "$out"
}

# chaos_leaders lines on stdin → the node id leading the most partitions (ties: the lowest id).
chaos_most_leaders() {
  awk '$3 >= 0 { c[$3]++ } END { best = -1; for (id in c) if (best < 0 || c[id] > c[best] || (c[id] == c[best] && id + 0 < best + 0)) best = id; if (best >= 0) print best }'
}

# chaos_await_leaders_moved <id> <seconds>: waits until no partition is led by broker <id> and none is
# leaderless, and prints how many seconds that took; fails at the limit. The limit is in wall time, not
# tries: each read is an exec plus a JVM start, several seconds.
chaos_await_leaders_moved() {
  local id=$1 limit=$2 t0=$SECONDS leaders
  while [ $((SECONDS - t0)) -lt "$limit" ]; do
    if leaders=$(chaos_leaders 2>/dev/null) && [ -n "$leaders" ] \
       && ! awk -v id="$id" '$3 == id || $3 == -1 { f = 1 } END { exit !f }' <<< "$leaders"; then
      echo $((SECONDS - t0)); return 0
    fi
    [ $((SECONDS - t0 + 2)) -lt "$limit" ] || break
    sleep 2
  done
  return 1
}

chaos_under_replicated() {
  local out
  out=$(chaos_kafka bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --under-replicated-partitions) || return 1
  grep -c 'Partition:' <<< "$out" || true
}

# The first pod of a Deployment, in name order (repeatable; either copy carries traffic). "broker" is
# Strimzi's first Kafka pod.
chaos_first_pod() {
  if [ "$1" = broker ]; then echo "$RELEASE-dual-role-0"; return; fi
  _k get pods -l "app.kubernetes.io/name=$1" --field-selector=status.phase=Running \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | sort | head -1
}

chaos_pod_ip() { _k get pod "$1" -o jsonpath='{.status.podIP}'; }

chaos_restarts() { _k get pod "$1" -o jsonpath='{.status.containerStatuses[0].restartCount}'; }

chaos_last_exit() { _k get pod "$1" -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}'; }

# A crash, not a polite stop. `kubectl delete pod` sends SIGTERM, so the shutdown hook runs; and
# `kubectl exec <pod> -- kill -9 1` does nothing, because Java is PID 1 in its container and Linux
# ignores SIGKILL sent to a namespace's PID 1 from inside it. From the node the container is an
# ordinary process: crictl with no grace period sends SIGKILL at once. Kubernetes then restarts the
# container in the same pod, so the pod keeps its name.
chaos_crash() {
  local id
  id=$(_k get pod "$1" -o jsonpath='{.status.containerStatuses[0].containerID}')
  id=${id#containerd://}
  [ -n "$id" ] || { echo "chaos_crash: $1 has no running container" >&2; return 1; }
  if [ "$CHAOS_PLATFORM" = eks ]; then
    chaos_node_run "$(chaos_node_of "$1")" "$CHAOS_CRICTL stop --timeout 0 $id" >/dev/null
  else
    docker exec "$CHAOS_NODE" crictl stop --timeout 0 "$id" >/dev/null
  fi
}

# Waits up to 60 s for the pod's restart count to pass <before> with a last exit of 137 (SIGKILL).
# A SIGTERM shutdown exits 143: that would be a polite stop, not the crash the scenario wants.
chaos_wait_restarted() {
  local pod=$1 before=$2 r code
  for _ in $(seq 1 60); do
    r=$(chaos_restarts "$pod")
    if [ "${r:-0}" -gt "$before" ]; then
      code=$(chaos_last_exit "$pod")
      [ "$code" = 137 ] && return 0
      echo "chaos_wait_restarted: $pod restarted with exit $code, not 137" >&2
      return 1
    fi
    sleep 1
  done
  echo "chaos_wait_restarted: $pod did not restart within 60 s (restarts still $r)" >&2
  return 1
}

# Cuts a pod off from Kafka while it keeps Redis (spec 2.3). The policy only stops new connections:
# k3s's firewall (kube-router) accepts packets of connections already open before it checks any
# policy, and Kafka clients hold theirs for minutes. Deleting the pod's tracked Kafka connections on
# the node makes their next packets count as new, so the policy refuses them too.
chaos_cut() {
  local pod=$1 ip
  _k label pod "$pod" chaos=cut --overwrite >/dev/null
  _k apply -f - >/dev/null <<EOF
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: chaos-cut
  labels: { app.kubernetes.io/part-of: sports-notifier-chaos }
spec:
  podSelector: { matchLabels: { chaos: cut } }
  policyTypes: [Egress]
  egress:
    - to: [ { podSelector: { matchLabels: { app.kubernetes.io/name: redis } } } ]
      ports: [ { port: 6379, protocol: TCP } ]
    - to: [ { namespaceSelector: {} } ]
      ports: [ { port: 53, protocol: UDP }, { port: 53, protocol: TCP } ]
EOF
  if [ "$CHAOS_PLATFORM" = eks ]; then
    # EKS enforces policies in eBPF, not iptables; if open connections survive the policy, reset them
    # from inside the pod's network namespace. Their next packets are new connections, which it refuses.
    [ "$CHAOS_CUT_KILL" = 1 ] || return 0
    local id
    id=$(_k get pod "$pod" -o jsonpath='{.status.containerStatuses[0].containerID}')
    id=${id#containerd://}
    chaos_node_run "$(chaos_node_of "$pod")" \
      "pid=\$($CHAOS_CRICTL inspect -o go-template --template '{{.info.pid}}' $id) && nsenter -t \$pid -n ss -K '( dport = :9092 )'" >/dev/null
    return
  fi
  ip=$(chaos_pod_ip "$pod")
  docker exec "$CHAOS_NODE" conntrack -D -s "$ip" -p tcp --dport 9092 >/dev/null 2>&1 || true
}

# Ends a cut. With no pod, removes the label from every pod: safe to call when nothing is cut.
# Fails if the policy could not be deleted, so a caller can say the cluster is still cut.
chaos_heal() {
  local rc=0
  _k delete networkpolicy chaos-cut --ignore-not-found >/dev/null || rc=1
  if [ $# -gt 0 ]; then
    _k label pod "$1" chaos- >/dev/null 2>&1 || true
  else
    _k label pods -l chaos chaos- >/dev/null 2>&1 || true
  fi
  return $rc
}

# The IPs of a consumer group's members, one per line (nothing for an empty group). Fails if the
# group could not be read: an unreadable group must never pass for "the cut pod left it".
chaos_members() {
  local out
  out=$(chaos_kafka bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group "$1" --members 2>/dev/null) || return 1
  printf '%s\n' "$out" | awk '$3 ~ /^\// { sub("/", "", $3); print $3 }'
}

chaos_rollout() {
  _k rollout restart "deployment/$1" >/dev/null
  _k rollout status "deployment/$1" --timeout=180s >/dev/null
}

# Waits up to CHAOS_STEADY_SECONDS (180) until every Deployment of the app is fully ready, every broker is ready
# (with several, nothing under-replicated either), and each consumer group has one member per pod of its Deployment. Prints "steady", or why not and fails.
chaos_steady() {
  local limit=${CHAOS_STEADY_SECONDS:-180} why="" name want ready brokers b urp
  local deadline=$((SECONDS + limit))
  while :; do
    why=""
    while read -r name want ready; do
      [ "${ready:-0}" = "$want" ] || why="$why deployment/$name ${ready:-0}/$want;"
    done < <(_k get deploy -l app.kubernetes.io/part-of=sports-notifier \
               -o jsonpath='{range .items[*]}{.metadata.name} {.spec.replicas} {.status.readyReplicas}{"\n"}{end}')
    brokers=$(chaos_brokers)
    [ -n "$brokers" ] || why="$why no broker found;"
    for b in $brokers; do chaos_ready "$b" || why="$why broker $b not ready;"; done
    if [ -z "$why" ] && [ "$(wc -l <<< "$brokers" | tr -d ' ')" -gt 1 ]; then
      if ! urp=$(chaos_under_replicated); then why="$why could not read under-replicated partitions;"
      elif [ "$urp" != 0 ]; then why="$why $urp under-replicated partitions;"; fi
    fi
    if [ -z "$why" ]; then
      for pair in stage1:stage1-worker stage2:stage2-worker history:history; do
        want=$(_k get deploy "${pair#*:}" -o jsonpath='{.spec.replicas}')
        ready=$(chaos_members "${pair%%:*}" | wc -l | tr -d ' ')
        [ "$ready" = "$want" ] || why="$why group ${pair%%:*} $ready/$want members;"
      done
    fi
    if [ -z "$why" ]; then echo steady; return 0; fi
    if [ $SECONDS -ge $deadline ]; then echo "not steady after $limit s:$why" >&2; return 1; fi
    sleep 3
  done
}

# Running pods of a Deployment that are not being deleted. `status.phase=Running` alone still lists a
# terminating pod during its preStop and shutdown, which a rollout check must not count as new (#54).
chaos_live_pods() {
  _k get pods -l "app.kubernetes.io/name=$1" --field-selector=status.phase=Running \
    -o jsonpath='{range .items[*]}{.metadata.name} {.metadata.deletionTimestamp}{"\n"}{end}' \
    | awk 'NF == 1 { print $1 }'
}

# A note when a pod restarted after `before` (its restart count when the cut began). Compared with the
# count before, not with 0: an earlier crash scenario leaves the count above 0 (#54).
chaos_restart_note() {
  local now
  now=$(chaos_restarts "$1") || return 0
  [ "${now:-0}" -gt "$2" ] && echo "$1 was restarted during the cut (liveness): allowed"
  return 0
}

# What the checker's report says about excused socket misses: "excused N  silent S" ("-" when the log
# has no report). Silent ones were excused only because an unanswered ping found the socket dead.
chaos_counts() {
  local line excused silent
  line=$(grep -m1 -oE 'allowed in disconnects: [0-9]+, silent: [0-9]+' "$1" || true)
  excused=$(sed -nE 's/.*disconnects: ([0-9]+),.*/\1/p' <<< "$line")
  silent=$(sed -nE 's/.*silent: ([0-9]+).*/\1/p' <<< "$line")
  echo "excused ${excused:--}  silent ${silent:--}"
}

# Stops a scenario's checker: the run-job.sh process and the Job it started (named in the log), so
# nothing keeps running until the Job's time limit (#54). Its synthetic users are left to check-clean.
chaos_stop_job() {
  local pid=$1 log=$2 job
  kill "$pid" 2>/dev/null || true
  job=$(grep -oE '^job/checker-[0-9a-z-]+' "$log" | head -1 || true)
  [ -z "$job" ] || _k delete "$job" --ignore-not-found >/dev/null 2>&1 || true
  wait "$pid" 2>/dev/null || true
  [ -z "$job" ] || echo "chaos: stopped $job mid-run; run make check-clean to remove its users" >&2
}

# --- A machine dying (node-loss, EKS only; Phase 6.1 spec, 4.3) ---

chaos_zone_of() { _k get node "$1" -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}'; }

# The EC2 instance behind a node: spec.providerID is aws:///<zone>/<instance id>.
chaos_instance_of() {
  local id
  id=$(_k get node "$1" -o jsonpath='{.spec.providerID}') || return 1
  echo "${id##*/}"
}

# chaos_pick_victim <checker's node>: the machine to kill. Never Redis's (its loss repeats pushes, #52),
# Postgres's, or the checker's; preferably not the observability pod's (its data lives in the pod).
# Prints the node, then "note: …" on a second line when only the observability pod's machine is left.
chaos_pick_victim() {
  local nodes avoid lgtm n redis pg free=() spare=()
  nodes=$(_k get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | sort) || return 1
  # Fail closed: a lookup that comes back empty must never let Redis's or Postgres's machine through.
  redis=$(chaos_node_of redis-0 2>/dev/null || true)
  [ -n "$redis" ] || { echo "chaos_pick_victim: cannot find the machine of redis-0" >&2; return 1; }
  pg=$(chaos_node_of postgres-0 2>/dev/null || true)
  [ -n "$pg" ] || { echo "chaos_pick_victim: cannot find the machine of postgres-0" >&2; return 1; }
  [ -n "$1" ] || { echo "chaos_pick_victim: no checker machine given" >&2; return 1; }
  avoid=" $redis $pg $1 "
  lgtm=$(_k get pods -l app.kubernetes.io/name=lgtm -o jsonpath='{.items[0].spec.nodeName}' 2>/dev/null || true)
  for n in $nodes; do
    [[ "$avoid" == *" $n "* ]] && continue
    if [ "$n" = "$lgtm" ]; then spare+=("$n"); else free+=("$n"); fi
  done
  if [ ${#free[@]} -gt 0 ]; then echo "${free[0]}"; return 0; fi
  if [ ${#spare[@]} -gt 0 ]; then echo "${spare[0]}"; echo "note: the dashboard data will be lost"; return 0; fi
  echo "chaos_pick_victim: no machine free of redis, postgres and the checker" >&2
  return 1
}

# The pods on a machine, one per line (for the log of what died with it).
chaos_pods_on() {
  _k get pods -o jsonpath='{range .items[*]}{.metadata.name} {.spec.nodeName}{"\n"}{end}' | awk -v n="$1" '$2 == n { print $1 }'
}

# run-job.sh flags and checker env for a scenario. node-loss keeps the checker on Redis's machine, so
# one machine holds neither and can die, and gives it 600 s instead of 300 to reach its end: a
# one-copy service (event-processing, history, …) on the dead machine comes back only after
# Kubernetes gives up on the machine. DEADLINE (replay + 900 s) still covers replay + 600 s.
chaos_job_flags() { [ "$1" != node-loss ] || echo "--near redis-0"; }
chaos_job_env() { [ "$1" != node-loss ] || echo "RUN_TIMEOUT_SECONDS=${CHAOS_NODE_LOSS_RUN_TIMEOUT:-600}"; }

# chaos_await_replacement <zone> <seconds> <nodes before…>: waits for a Ready node that was not there
# before and prints "<name> <zone>". One in another zone fails: the dead machine's broker disk (zonal
# EBS) cannot follow it there.
chaos_await_replacement() {
  local zone=$1 limit=$2 before name z ready
  shift 2
  before=" $* "
  local deadline=$((SECONDS + limit))
  while :; do
    while IFS=, read -r name z ready; do
      [ -n "$name" ] && [[ "$before" != *" $name "* ]] && [ "$ready" = True ] || continue
      if [ "$z" = "$zone" ]; then echo "$name $z"; return 0; fi
      echo "fault did not land: replacement in $z, not $zone" >&2
      return 1
    done < <(_k get nodes -o jsonpath='{range .items[*]}{.metadata.name},{.metadata.labels.topology\.kubernetes\.io/zone},{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null)
    if [ $SECONDS -ge $deadline ]; then echo "no replacement within $limit s" >&2; return 1; fi
    sleep 5
  done
}

# --- Fault marks on the Pipeline dashboard (Phase 4.3 spec, section 6) ---
# Grafana annotations through a port-forward this script opens. A mark that cannot be posted is a log
# line, never a failed scenario; with no observability pod there are no marks and no links.
CHAOS_GRAFANA=${CHAOS_GRAFANA:-0}
CHAOS_GRAFANA_PORT=${CHAOS_GRAFANA_PORT:-3300}
CHAOS_GRAFANA_PID=""

chaos_now_ms() { echo $(( $(date +%s) * 1000 )); }

chaos_grafana_start() {
  CHAOS_GRAFANA=0
  _k get svc lgtm >/dev/null 2>&1 || return 0
  _k port-forward svc/lgtm "$CHAOS_GRAFANA_PORT:3000" >/dev/null 2>&1 &
  CHAOS_GRAFANA_PID=$!
  for _ in $(seq 1 20); do
    if curl -sf --max-time 2 -o /dev/null "http://localhost:$CHAOS_GRAFANA_PORT/api/health"; then CHAOS_GRAFANA=1; return 0; fi
    sleep 0.5
  done
  echo "chaos: Grafana did not answer on :$CHAOS_GRAFANA_PORT; running without fault marks" >&2
  chaos_grafana_stop
}

chaos_grafana_stop() {
  [ -z "$CHAOS_GRAFANA_PID" ] || kill "$CHAOS_GRAFANA_PID" 2>/dev/null || true
  CHAOS_GRAFANA_PID=""
}

# chaos_mark <scenario> <text> [<epoch ms>]
chaos_mark() {
  [ "$CHAOS_GRAFANA" = 1 ] || return 0
  local at=${3:-$(chaos_now_ms)}
  # A time limit: the port-forward may point at a pod on a machine that just died, and a mark must
  # never hold up a scenario.
  curl -sf --max-time 5 -o /dev/null -X POST "http://localhost:$CHAOS_GRAFANA_PORT/api/annotations" \
    -H 'Content-Type: application/json' \
    -d "{\"time\": $at, \"tags\": [\"chaos\", \"$1\"], \"text\": \"$1: $2\"}" \
    || echo "chaos: could not mark '$1: $2' on the dashboard" >&2
  return 0
}
