# Phase 5: AWS — design

Status: draft for review, 2026-10-22. Parent document: `DESIGN.md` (decisions 6, 7, 8, 9, 10). If this spec and `DESIGN.md` disagree, `DESIGN.md` wins and this spec gets fixed. `DESIGN.md`'s build order (steps 5 and 6), decision 9, "Where it runs", and the open problem about network policies on EKS are updated with this spec.

## Goal

Run the same chart on AWS, up only when wanted. `make aws-up` creates the cloud environment from code, restores the last Postgres dump, and serves the app at `https://sports.<domain>` with the real ESPN poller. `make aws-down` dumps Postgres to S3, checks the dump, and destroys everything that bills by the hour. The cluster has three machines in three availability zones, and Kafka has three brokers, one per zone.

The resume line this phase makes true: "runs on EKS across three availability zones, built by Terraform, comes up from one command and survives a full teardown."

The user wants to learn the AWS side by using it. They log in, set up their own access, and run every `terraform plan` and `apply`. After each milestone they take a short console tour (section 9) of what was just built. Claude writes the Terraform in small steps and explains each one; nothing here is delegated to subagents.

Not in Phase 5:
- KEDA. It moves to Phase 6, where the load test is what makes it scale; in Phase 5 it would be configuration nobody sees work.
- The chaos suite on EKS (Phase 6).
- TLS between pods (section 8).
- #47, replayed pushes reaching real accounts: decided before Phase 6.
- #52 (after Phase 6).

### Decided before this spec (brainstorm, 2026-10-15 to 2026-10-21)

- **Front door: an AWS load balancer that Terraform creates.** A Network Load Balancer (NLB) ends TLS with a free ACM certificate and forwards to Traefik on a fixed port on each node. Proxy protocol carries the client's address. There is no AWS Load Balancer Controller. A Cloudflare Tunnel was considered and rejected: it changes decision 9, puts a third party in the request path, and needs a `ClientAddress` change.
- **DNS stays at Cloudflare**, where the user's domain is, as a DNS-only (grey cloud) record that Terraform manages.
- **No TLS between pods for now**, for Kafka, Redis, or Postgres alike. The network policies, which EKS now enforces, are the protection. This replaces the Phase 3.1 note that Phase 5 adds Kafka TLS.
- **The user has an AWS account** but no CLI or login set up on the Mac. Section 2 covers that.
- **KEDA moves to Phase 6.**
- **Two Terraform stacks**: `base`, kept, and `cluster`, created and destroyed on demand.
- **Nodes**: one managed node group of three t3.large machines, on demand rather than spot. EKS Auto Mode was rejected because its nodes allow no node access, and Phase 6's crash scenario needs `crictl` on the node.
- **Region us-east-2.** One us-east-1 zone (use1-az3) cannot host EKS, which complicates "one subnet per zone".
- **#51 is in**, because Phase 5 ends with `make check` on the cluster that real accounts use.

### Done when

1. `make test` passes, including the #51 tests (section 7).
2. CI is green, including the new image push to ECR (section 6).
3. `make aws-up` from nothing ends with the app live at `https://sports.<domain>`, and `BASE_URL=https://sports.<domain> make smoke` passes.
4. `make check SET=ci SPEED=60` passes against the EKS cluster.
5. The persistence round trip passes: an account and a follow made on AWS survive `make aws-down` and a fresh `make aws-up`. The account logs in and still gets alerts.
6. `make aws-down` ends with the leftover sweep reporting nothing billable (section 5.3).
7. By hand:
   - The UI installs on the user's phone from the HTTPS address. This is first possible now; Phase 3b deferred it.
   - A live game's alerts arrive through the ESPN poller running on AWS.
   - The user has taken the console tours (section 9).
8. Docs updated (section 11). `docs/study/phase-5-quiz.md` and `docs/study/aws-console-tour.md` are written. The user is quizzed only on request.
9. The PR from `phase-5-aws` is merged.

### Branch

`phase-5-aws`, worktree `.worktrees/phase-5-aws`, from main at `c4e69e0`.

---

## 1. What exists in AWS, and for how long

Two Terraform stacks under `infra/`. A stack is one folder of `.tf` files with its own saved state. Splitting the stacks is how "destroy the cluster" leaves the dumps, the images, and the certificate alone.

| | `infra/base` | `infra/cluster` |
|---|---|---|
| Lifetime | Created once, never destroyed | `make aws-up` / `make aws-down` |
| Holds | Dump bucket, ECR repository, ACM certificate and its Cloudflare validation record, GitHub's push role, budget alarm | VPC and subnets, EKS, node group, add-ons, NLB, Pod Identity role for the restore, Cloudflare record for `sports.<domain>` |
| Cost while the cluster is down | Under $1 a month (ECR storage, S3, cents of state) | Nothing, because it does not exist |
| Cost while up | — | About $0.45 an hour: EKS control plane $0.10, 3 × t3.large $0.25, NLB about $0.03, public IPv4 addresses about $0.03, small gp3 disks |

**Terraform state** lives in an S3 bucket, with versioning on and S3's own lock file (`use_lockfile`, so no DynamoDB table). Terraform cannot keep its state in a bucket it has not created yet, so `make aws-bootstrap` creates that one bucket with the AWS CLI, once. Both stacks then use it. The cluster stack reads the base stack's outputs (certificate ARN, ECR URL, dump bucket) through `terraform_remote_state`.

**Settings** that are not secret go in `infra/settings.auto.tfvars`, which is committed: region, domain, subdomain, budget email, and budget amount. Every resource gets the tag `project = sports-notifier` through the provider's `default_tags`. The leftover sweep and the cost reports both use that tag.

**Versions**: Terraform and providers are pinned in the plan. The providers are `hashicorp/aws` and `cloudflare/cloudflare` v5, which names the record `cloudflare_dns_record`. The VPC and EKS come from the community modules `terraform-aws-modules/vpc` and `terraform-aws-modules/eks`, pinned. Those two hide hundreds of lines of IAM and edge cases that teams do not hand-write. Everything else is plain resources, so each one can be read on its own.

### 1.1 `infra/base`

- **Dump bucket.** It is private, with all public access blocked and default encryption on. Versioning is on, so `dumps/latest.dump` keeps its history, and old versions expire after 30 days.
- **ECR repository** `sports-notifier`. Tags are mutable, because a PR's moving tag needs that (section 6). A lifecycle rule keeps the last 20 images. Images are scanned on push.
- **ACM certificate** for `sports.<domain>`, validated by DNS. Terraform writes the validation CNAME into Cloudflare and waits until the certificate is issued. The certificate is in the kept stack, so a teardown never re-issues it.
- **GitHub OIDC provider and role.**
  - Problem: CI needs to push images, and long-lived AWS keys in GitHub secrets are the classic leak.
  - Fix: GitHub's identity token is exchanged for a short-lived role session.
  - The role's trust policy admits only this repository's `main` branch and its pull requests.
  - Its only permission is to push to the one ECR repository.
- **Budget alarm.** An AWS Budget of $25 a month (a setting) emails the user at 40% and 100% of actual spend and at 100% of forecast. Budgets are free.

### 1.2 `infra/cluster`

- **VPC** across three zones, with **public subnets only** and no NAT gateway (decision 9). Nodes get public addresses so they can reach ESPN and ECR. Their security group lets nothing in from the internet; the only inbound path is through the NLB.
- **EKS 1.35.** It must be 1.34 or later, because of the gateway's native `preStop` sleep. 1.35 is in standard support until March 2027 and matches k3d's 1.35.
  - Authentication mode: access entries. The person who runs `apply` is cluster admin. That is also what lets the EKS console show the cluster's pods.
  - The public API endpoint is open only to the address `make aws-up` sees, from `checkip.amazonaws.com`, passed in as a variable on every apply.
- **Node group**: three t3.large machines (2 vCPU, 8 GiB) with min, max, and desired all 3, one per zone, on Amazon Linux 2023. The node role can pull from ECR.
- **Add-ons** (EKS-managed, pinned):
  - VPC CNI, with `enableNetworkPolicy: "true"`. This closes the open problem "network policies need an engine on EKS": without it, every policy in the chart is silently ignored.
  - CoreDNS.
  - kube-proxy.
  - EBS CSI driver, so disk claims become EBS volumes. Its permissions come through Pod Identity.
  - Pod Identity agent.
- **NLB**, created here and not by a controller in the cluster, so `destroy` removes it.
  - Listener 443: TLS, ending with the ACM certificate and policy `ELBSecurityPolicy-TLS13-1-2-2021-06`. It forwards to Traefik's `websecure` NodePort.
  - Listener 80: TCP, forwarding to Traefik's `web` NodePort, which redirects to HTTPS.
  - Target groups: instance targets, attached to the node group's Auto Scaling group, so a replaced node joins on its own. Proxy protocol v2 is on, and client IP preservation is off: the client's address arrives in the proxy protocol header, so the nodes only ever see the NLB.
  - The node security group admits the two NodePorts from the NLB's security group only. Health checks are TCP.
- **Pod Identity association** for the `postgres` service account: read access to `dumps/` in the dump bucket (section 5.2).
- **Cloudflare record**: `sports.<domain>` CNAME to the NLB's DNS name, DNS only, TTL 60 s. A new apply makes a new NLB name; the record follows it.

## 2. One-time setup (the user, in the console and the terminal)

Written up in `docs/aws-setup.md` during the plan:

1. **Account safety.** Turn on root MFA and stop using root.
2. **Login.** In IAM Identity Center, in us-east-2, create a user for the user with `AdministratorAccess` on the account. Then `aws configure sso` on the Mac with profile `sports`. Every command uses `AWS_PROFILE=sports`, so there are no long-lived keys on the Mac.
3. **Quotas.** In Service Quotas, check "Running On-Demand Standard instances". It needs at least 6 vCPUs, and new accounts can start lower. Request an increase if needed.
4. **Billing.** Activate the `project` cost allocation tag, so Cost Explorer can show this project's spend.
5. **Cloudflare.** Create an API token limited to *Zone → DNS → Edit* on the one zone. Keep it in the macOS keychain; the Make targets read it into `CLOUDFLARE_API_TOKEN` and it never touches the repo.
6. **Tools.** `brew install awscli terraform` (kubectl and helm are already installed).

## 3. `make aws-up`

`scripts/aws-up.sh`. Each step prints what it is doing. Terraform's own prompt is kept: `apply` shows the plan and waits for "yes".

1. Checks: the SSO session is valid, the Cloudflare token is present, and the image tag exists in ECR. The default tag is `origin/main`'s short SHA; `TAG=` overrides it, for example `TAG=pr58`.
2. `terraform apply` in `infra/cluster`. The first run takes about 15–20 minutes, most of it EKS.
3. `aws eks update-kubeconfig --alias sports-notifier-aws`. Every later command passes this context explicitly. `KUBE_CONTEXT` already exists for this (`Makefile`, `run-job.sh`), so the k3d context is never touched.
4. Cluster pieces, installed with Helm like `make cluster` installs Strimzi:
   - A gp3 `StorageClass`, the default, with `WaitForFirstConsumer`, so a disk is made in the zone where its pod lands. It tags every volume `project = sports-notifier`, so the leftover sweep finds them.
   - Strimzi 1.2.0.
   - Traefik, from `deploy/aws/traefik-values.yaml`:
     - a NodePort Service with the two fixed ports
     - proxy protocol trusted from the VPC's address range
     - the `web` entry point redirecting to `websecure`
     - an HSTS header on responses
5. The ESPN check (decision 10: "Phase 5 repeats the request from AWS first"). `scripts/espn-check.sh` runs as a Job in the cluster, so the request leaves from an AWS address.
   - If ESPN refuses, the app is installed with the poller off and the script ends with a loud warning. It does not stop halfway, because a half-built cluster still bills.
   - Plan B, a poller at home posting to the cloud (memory: ESPN access findings), would be its own decision.
6. `helm upgrade --install` of our chart with `values-aws.yaml` and the image tag, then wait for Kafka ready, as `make up` does.
7. Wait until `https://sports.<domain>/health` answers through the public address, then print the URL.

## 4. The chart on AWS

### 4.1 `values-aws.yaml`

- `image.repository`: the ECR URL. The tag comes from `aws-up`.
- `kafka`: 3 nodes (each one KRaft controller and broker, as on the laptop), replication 3, `min.insync.replicas` 2, 10 Gi each.
- `postgres`: 10 Gi, password generated (the existing `lookup` path), restore on (section 5.2). `redis`: 1 Gi.
- `espnPoller.enabled: true`.
- Cookies: the default `COOKIE_SECURE=true`. `values-local.yaml` keeps its `false`.
- `ingress.controllerNamespace: traefik` (section 4.2).
- Java services: the same two copies of the gateway, Stage 1, and Stage 2 as everywhere. Memory requests stay as they are. Section 4.4 checks they fit.
- Observability on, as on the laptop. `make grafana` works with the AWS context.

### 4.2 Template changes (each one a no-op on the laptop)

- **Kafka across zones.** When `kafka.zoneSpread` is on, the node pool gets a topology spread constraint on `topology.kubernetes.io/zone` (one broker per zone, required). The Kafka resource gets `rack.topologyKey: topology.kubernetes.io/zone`, so Kafka places a partition's three copies in three different zones. Without the rack setting, Kafka can put two copies in one zone, and losing that zone loses two copies at once.
- **Traefik's namespace** in the gateway's network policy becomes a value. It defaults to `kube-system`, where k3d runs Traefik. Same label selector.
- **Postgres restore** (section 5.2): a service account, an init container, and a startup script, all behind `postgres.restore.enabled`.
- **No storage class change.** The claims name no class, so they get the cluster's default: gp3 on AWS (section 3, step 4) and local-path on k3d.

### 4.3 Network policies on EKS

They are the same policies, now enforced by the VPC CNI's policy agent. The smoke test's "a stranger pod is refused" check runs against EKS as part of "done when" item 3, which is the proof the engine is on.

One AWS-specific path: the NLB reaches Traefik as a node port, from outside the pod network. Traefik's pods are not covered by our policies, which select only our release's namespace. The gateway's policy still admits only Traefik.

### 4.4 Does it fit on three t3.large?

Allocatable is about 7 GiB per node, 21 GiB in total. Requests:

| Component | Request |
|---|---|
| 11 Java pods at 256 Mi | about 2.8 GiB |
| 3 brokers (heap 512 Mi plus overhead) | about 3 GiB |
| Observability pod | 1 GiB |
| Strimzi operators and Kafka exporter | about 1 GiB |
| Postgres and Redis | about 0.5 GiB |
| Traefik, CoreDNS, and the per-node agents | about 1.5 GiB |

That is about 10 GiB of requests. Real use is higher, because JVMs grow toward their 512 Mi limits, but it leaves room for Phase 6's extra workers. t3 machines run on CPU credits ("unlimited" mode bills extra burst); at friends-scale traffic the baseline is enough, and Phase 6 can change the instance type, which is one setting.

## 5. Data across a teardown

### 5.1 `make aws-down`

`scripts/aws-down.sh`, in this order. A failure in step 1 or 2 stops the script before anything is destroyed.

1. **Dump.**
   - In the Postgres pod, `pg_dump -Fc` writes `/tmp/sportsnotifier.dump`.
   - `pg_restore --list` must parse it, and it must list `TABLE DATA` for `users`, `follows`, and `flyway_schema_history`.
   - It is streamed to `s3://<dump bucket>/dumps/latest.dump`.
   - The object's size in S3 must equal the file's size.
2. **Uninstall.** `helm uninstall` of our release and Traefik. Then delete every PersistentVolumeClaim in the namespace (Kafka's claims are kept on purpose by `deleteClaim: false`, and Postgres's and Redis's outlive a release). Wait until their EBS volumes are gone.
   - Problem: EBS volumes made by the CSI driver are not in Terraform's state, so `destroy` would leave them, and they bill by the gigabyte-month forever.
3. **`terraform destroy`** in `infra/cluster`. This also removes the NLB and the Cloudflare record.
4. **Leftover sweep** (section 5.3).

`make aws-backup` runs step 1 alone, for a dump without a teardown.

### 5.2 Restore on first boot

- **Problem:** the services run Flyway migrations as they start. A restore into a database the services have already set up collides with what they made.
- **Fix:** restore before Postgres accepts outside connections. The official Postgres image runs the scripts in `/docker-entrypoint-initdb.d/` exactly once, when it creates an empty data folder, while it listens only on its local socket.
  - An **init container** (pinned `amazon/aws-cli`) copies `dumps/latest.dump` into a shared `emptyDir`. It has read-only access through Pod Identity (section 1.2). If there is no dump (the first apply ever), it writes nothing.
  - A **startup script** from a ConfigMap runs `pg_restore --no-owner --role=sportsnotifier` if the file is there.
  - The init container runs on every pod start, but the script runs only on an empty disk. So a Postgres restart while the cluster is up never restores over live data.
- A dump made by an older version of the app is migrated forward by Flyway when the services start. The dump has no role passwords in it, so the newly generated Postgres password just works.
- **Redis** rebuilds `sub:*` from Postgres (decision 8). Sessions are gone, so everyone logs in again. **Kafka** starts empty. Both are decision 9 as written.

### 5.3 Leftover sweep

`scripts/aws-leftovers.sh` (also `make aws-status`) lists, in the region, anything billable tagged `project = sports-notifier`, or tagged by Kubernetes for this cluster:
- EC2 instances
- load balancers and target groups
- EBS volumes
- unattached network interfaces
- Elastic IPs
- NAT gateways (there should never be one)
- EKS clusters

`aws-down` fails if anything is listed. Before the cluster exists, or after it is gone, `aws-status` shows "nothing billing", or what is.

## 6. Images: CI to ECR

- **Problem:** EKS cannot pull the image k3d is fed (`k3d image import`). It needs a registry, and the user's Mac builds arm64 images while the t3 nodes are amd64.
- **Fix:** CI's cluster job already builds the image on an amd64 runner and proves it with `smoke`, `check`, `trace-check`, and `e2e`. A final step, run only when everything before it passed, signs in to AWS through the OIDC role and pushes that same image:
  - On a push to `main`: tagged `<short SHA>`, the same tag `make up` uses.
  - On a pull request: tagged `pr<N>-<short head SHA>` and the moving tag `pr<N>`. Like every PR run, the image is built from GitHub's merge of the PR into main. This is how a PR branch, including this phase's own, is deployed before it merges.
- The step is skipped when the repository variable `AWS_ROLE_ARN` is unset, so CI stays green before `infra/base` exists.
- Nothing is pushed from the laptop. Building amd64 on an Apple Silicon Mac goes through emulation and takes several times longer.

## 7. Issue #51: reserve the `ck_` prefix

- **Problem:** `make check-clean` deletes every user named like `ck_<run>_<n>`, but signup accepts those names from anyone. On AWS that is a path for a real account to be deleted.
- **Fix:** signup rejects a username starting with `ck_` unless the request carries the header `X-Checker-Key` matching a Secret.
  - The chart generates the Secret and keeps it across upgrades (the `lookup` pattern Postgres uses).
  - It is mounted only into the gateway and the checker. The header comes from the public internet like any other, so it is the key that makes it safe, not the header's name.
  - The comparison is constant-time.
  - Cleanup keeps selecting by name: only the checker can now make those names.
- **Tests:**
  - Signup as `ck_abcdef_1` without the key gets 400 (the same answer as any invalid name, which reveals nothing).
  - With a wrong key it also gets 400.
  - With the right key it succeeds.
  - The checker's signup path sends the key.
  - `make check` on k3d still passes.

## 8. Security posture on AWS (what is and is not protected)

- **In from the internet:** only ports 80 and 443 on the NLB. Port 80 only redirects. The nodes accept the two NodePorts from the NLB's security group only.
- **Kubernetes API:** public endpoint, open only to the user's current address, and IAM authentication on top.
- **Between pods:** network policies enforced. **No TLS inside the VPC** for Kafka, Redis, or Postgres.
  - The traffic stays on the VPC's private addresses, and the policies decide who can connect.
  - Encrypting one store and not the others would not make a coherent story; doing all three is its own hardening step, listed in "Known gaps".
- **Secrets:**
  - The Postgres password and the checker key are generated in the cluster.
  - The Cloudflare token is in the user's keychain.
  - AWS access is SSO sessions on the Mac and OIDC in CI. There are no long-lived keys anywhere.
- **The dump** holds password hashes: a private bucket, encrypted, with public access blocked.
- **Login throttling** sees the real client address. Proxy protocol from the NLB means Traefik's `X-Real-Ip` is the client, as on k3d. Without it, every user would share the NLB's address and one 20-attempt budget.

## 9. Console tours

After each milestone the user opens the console and finds what Terraform just made. The goal is knowing the services and how to get around them, not memorizing clicks. Claude writes the tour as part of the milestone, into `docs/study/aws-console-tour.md`, one section per milestone, with what to look at and why it matters here.

1. **After `infra/base`:**
   - S3: the bucket's versioning, encryption, and public-access block
   - ECR: the repository, its lifecycle rule, and the first image CI pushes
   - ACM: the certificate going from "Pending validation" to "Issued"
   - IAM: the OIDC provider, and the role's trust policy, read line by line
   - Budgets
2. **After the first `infra/cluster` apply:**
   - VPC: subnets per zone, route tables, the internet gateway, and no NAT
   - EC2: the three instances and their zones, security groups and their one inbound rule, volumes appearing as Kafka and Postgres start, the load balancer, and its target groups' health
   - EKS: the cluster's version and endpoint access, compute, add-ons (find `enableNetworkPolicy`), access entries, and the Resources tab showing our pods
3. **A drift exercise:** change a tag on the load balancer in the console, run `terraform plan`, and watch Terraform notice and offer to put it back.
4. **After the first `aws-down`:** the dump object and its versions in S3. Then confirm that EC2, EKS, and Load Balancers are empty. The next day, the project's spend in Cost Explorer.

## 10. Order of work

Milestones, each ending with something the user can see:

1. **Setup** (section 2) and `make aws-bootstrap`. *Console: IAM Identity Center, Service Quotas.*
2. **`infra/base`** and the CI push step. Merge-independent: the PR's own run pushes `pr<N>` once `AWS_ROLE_ARN` is set. *Tour 1.*
3. **#51** (section 7). A Java change, test first.
4. **`infra/cluster`**, `aws-up` steps 1–3, and the first apply. The cluster exists, still empty. *Tour 2, then the drift exercise.*
5. **Chart changes** (section 4) and `aws-up` steps 4–7, with the restore off. **The app is live at `https://sports.<domain>`.** Smoke test, phone install.
6. **Teardown and restore** (section 5): `aws-down`, the leftover sweep, the restore, then the persistence round trip. *Tour 4.*
7. **Proof and docs:** `make check` on EKS, the live game by hand, docs, quiz.

Between sessions, the cluster is down (`make aws-down`), so idle time costs nothing. Until milestone 6 exists, the teardown is `terraform destroy` plus the sweep, run by hand together.

## 11. Docs to update

- `DESIGN.md`:
  - Decision 9: the NLB made by Terraform, Traefik behind it, Cloudflare DNS, the two stacks.
  - "Where it runs": workers fixed at two copies until Phase 6.
  - Build order: step 5 without KEDA, step 6 with it.
  - The open problem about network policies on EKS, closed.
  - TLS between pods in "What we are not building (yet)".
  - The revision line.
- The Phase 3.1 spec's Kafka TLS note points here.
- `CLAUDE.md` and `AGENTS.md`:
  - "New in Phase 5"
  - the `make aws-*` commands
  - the architecture table's "where it runs"
  - next step: Phase 6
  - open problem 2 removed
- `docs/aws-setup.md` (section 2) and `docs/study/aws-console-tour.md` (section 9).
- `README.md`: how to bring it up on AWS, and what it costs.

## Known gaps accepted in Phase 5

- **No TLS between pods.** Kafka, Redis, and Postgres traffic is plain inside the VPC, behind network policies.
- **Redis and Postgres are one pod each, with a disk in one zone** (decisions 6 and 7). If that zone goes, they stop until it returns.
- **Fixed worker counts** until Phase 6 adds KEDA.
- **The Kubernetes API is open to one address**, read at each apply. A changed home address needs `make aws-up` again (a no-op apply that only updates the address) before `kubectl` works.
- **A dump is taken only by `aws-down` or `aws-backup`.** A cluster lost some other way loses what changed since the last dump. On-demand hosting makes that rare; there is no scheduled backup.
- **The observability pod's data is lost on teardown**, as on the laptop.
