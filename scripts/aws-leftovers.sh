#!/usr/bin/env bash
# Lists anything in the region that bills by the hour and belongs to this project (make aws-status;
# the last step of make aws-down). Exit 0 only when every listing ran and found nothing: a listing that
# fails is never reported as "nothing billing". Volumes made from claims, an orphaned load balancer
# and leftover network interfaces are the usual culprits: they are not in Terraform's state.
set -uo pipefail
source "$(dirname "$0")/aws-lib.sh"

found=0
errors=0

# check <label> <aws arguments...>: prints one "<label> <fields>" line per resource found.
check() {
  local label=$1 out
  shift
  if ! out=$(aws "$@" --output text 2>&1); then
    echo "ERROR listing ${label}s: $out" >&2
    errors=1
    return
  fi
  out=$(printf '%s\n' "$out" | sed -e '/^[[:space:]]*$/d' -e '/^None$/d')
  if [[ -n "$out" ]]; then
    while IFS= read -r line; do echo "$label $line"; done <<< "$out"
    found=1
  fi
}

live='Name=instance-state-name,Values=pending,running,shutting-down,stopping,stopped'
check instance ec2 describe-instances --filters Name=tag:eks:cluster-name,Values=sports-notifier "$live" \
  --query 'Reservations[].Instances[].[InstanceId,InstanceType,State.Name]'
check instance ec2 describe-instances --filters Name=tag:project,Values=sports-notifier "$live" \
  --query 'Reservations[].Instances[].[InstanceId,InstanceType,State.Name]'
check load-balancer elbv2 describe-load-balancers \
  --query "LoadBalancers[?starts_with(LoadBalancerName,'sports-notifier')].[LoadBalancerName,State.Code]"
check target-group elbv2 describe-target-groups \
  --query "TargetGroups[?starts_with(TargetGroupName,'sports-notifier')].[TargetGroupName]"
check volume ec2 describe-volumes --filters Name=tag:project,Values=sports-notifier \
  --query 'Volumes[].[VolumeId,Size,State]'
check volume ec2 describe-volumes --filters Name=tag:ebs.csi.aws.com/cluster,Values=true \
  --query 'Volumes[].[VolumeId,Size,State]'
check network-interface ec2 describe-network-interfaces \
  --filters Name=status,Values=available Name=tag:cluster.k8s.amazonaws.com/name,Values=sports-notifier \
  --query 'NetworkInterfaces[].[NetworkInterfaceId]'
check elastic-ip ec2 describe-addresses --filters Name=tag:project,Values=sports-notifier \
  --query 'Addresses[].[PublicIp,AllocationId]'
# Any NAT gateway at all: this project never makes one, and each bills by the hour.
check nat-gateway ec2 describe-nat-gateways --filter Name=state,Values=pending,available,deleting \
  --query 'NatGateways[].[NatGatewayId,State]'
check eks-cluster eks list-clusters --query "clusters[?@=='sports-notifier']"
# A VPC costs nothing, but one left behind means a destroy stopped partway.
check vpc ec2 describe-vpcs --filters Name=tag:project,Values=sports-notifier --query 'Vpcs[].[VpcId,CidrBlock]'

if (( errors )); then
  echo "Could not check everything (above). Is the session valid? aws sso login --profile $AWS_PROFILE" >&2
  exit 1
fi
if (( found )); then
  echo "Still in AWS (above). If no cluster should be up, run: make aws-down" >&2
  exit 1
fi
echo "nothing billing by the hour in $AWS_REGION"
