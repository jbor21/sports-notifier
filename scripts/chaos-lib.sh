# Fault primitives for the chaos suite (Phase 4.2 spec, section 2). Sourced by scripts/chaos.sh, or by
# hand: `source scripts/chaos-lib.sh; chaos_first_pod stage2-worker`.
#
# The crash and the cut reach into the k3d node (a Docker container on the Mac) for crictl and
# conntrack. On EKS (Phase 6) those two functions are replaced; the scenarios stay the same.

KUBE_CONTEXT=${KUBE_CONTEXT:-k3d-sports-notifier}
CLUSTER=${CLUSTER:-sports-notifier}
RELEASE=${RELEASE:-sports-notifier}
CHAOS_NODE=${CHAOS_NODE:-k3d-$CLUSTER-server-0}

_k() { kubectl --context "$KUBE_CONTEXT" "$@"; }

# The first pod of a Deployment, in name order (repeatable; either copy carries traffic). "broker" is
# Strimzi's one Kafka pod.
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
  docker exec "$CHAOS_NODE" crictl stop --timeout 0 "$id" >/dev/null
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
  out=$(_k exec "$RELEASE-dual-role-0" -c kafka -- bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group "$1" --members 2>/dev/null) || return 1
  printf '%s\n' "$out" | awk '$3 ~ /^\// { sub("/", "", $3); print $3 }'
}

chaos_rollout() {
  _k rollout restart "deployment/$1" >/dev/null
  _k rollout status "deployment/$1" --timeout=180s >/dev/null
}

# Waits up to CHAOS_STEADY_SECONDS (180) until every Deployment of the app is fully ready, the broker is ready, and each
# consumer group has one member per pod of its Deployment. Prints "steady", or why not and fails.
chaos_steady() {
  local limit=${CHAOS_STEADY_SECONDS:-180} why="" name want ready
  local deadline=$((SECONDS + limit))
  while :; do
    why=""
    while read -r name want ready; do
      [ "${ready:-0}" = "$want" ] || why="$why deployment/$name ${ready:-0}/$want;"
    done < <(_k get deploy -l app.kubernetes.io/part-of=sports-notifier \
               -o jsonpath='{range .items[*]}{.metadata.name} {.spec.replicas} {.status.readyReplicas}{"\n"}{end}')
    [ "$(_k get pod "$RELEASE-dual-role-0" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" = True ] \
      || why="$why broker not ready;"
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
