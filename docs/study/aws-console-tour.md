# AWS console tours

One tour per milestone of Phase 5. Each says what to open, what to look at, and why it matters in
this project. The goal is knowing the services and how to get around them, not memorizing clicks.
Sign in through your access portal URL (`docs/aws-setup.md`, step 2), and keep the region on
**us-east-2**. Most services show only one region at a time; a "missing" resource is usually in a
different region.

## 0. Your account

**IAM Identity Center** is who you are.
- **Users**: your user. **Permission sets**: `AdministratorAccess`, a bundle of permissions that is
  not yet attached to anything. **AWS accounts**: your account, with your user assigned that
  permission set. Those three together are what lets you in.
- When you sign in, AWS gives you a role named `AWSReservedSSO_AdministratorAccess_…` for 8 hours.
  Find it in **IAM → Roles**. You never edit it; Identity Center manages it.
- Why it matters: the CLI on your Mac uses the same login (`aws sso login`), so there are no access
  keys stored anywhere that could leak.

**Service Quotas** is what AWS lets you run.
- EC2 → "Running On-Demand Standard … instances" counts vCPUs, not machines. Three t3.large are
  3 × 2 = 6 vCPUs. Quotas are per region.

**S3 → the `sports-notifier-tfstate-…` bucket** is where Terraform remembers what it built.
- **Properties → Bucket Versioning: Enabled.** Every apply writes a new version of the state file.
  If one goes wrong, an older version is still there.
- **Permissions → Block public access: all On.** The state file lists every resource's details;
  it must never be public.
- After Task 3 there will be a `base/terraform.tfstate` object. Open its **Versions** tab after a
  second apply.

**Billing and Cost Management** is what it costs.
- **Bills**: this month so far, by service. **Budgets**: empty until Task 3. **Cost allocation
  tags**: the `project` tag, active after Task 3 (`docs/aws-setup.md`, step 8).

## 1. The kept stack (`infra/base`)

One `terraform apply` made 14 things. Find each one; this is the part of AWS that stays up forever,
for under a dollar a month.

**S3 → `sports-notifier-dumps-…`** is where your accounts survive a teardown.
- **Properties → Bucket Versioning: Enabled**, and **Default encryption: SSE-S3**.
- **Management → Lifecycle rules → `expire-old-dumps`**: an old dump is deleted 30 days after a newer
  one replaces it. The newest is never deleted.
- **Permissions → Block public access: all On.** A dump holds password hashes.
- Empty until your first `make aws-down`.

**ECR → Private registry → Repositories → `sports-notifier`** is where the app's image lives.
- The **URI** (`250758375664.dkr.ecr.us-east-2.amazonaws.com/sports-notifier`) is the image's
  address. EKS pulls from it; CI pushes to it.
- **Lifecycle policy**: keep the last 20 images. **Scan on push**: each image is checked for known
  vulnerabilities in its packages.
- Empty until CI's first push (Task 4).

**Certificate Manager (ACM) → the `sports.expense-app.uk` certificate** is the padlock in the
browser.
- **Status: Issued.** **Validation: DNS.** Open it and look at **Domains**: the CNAME name and value
  ACM asked for.
- Now open **Cloudflare → expense-app.uk → DNS → Records** and find that same CNAME, with the
  comment Terraform wrote. That record is how ACM knows you own the domain. Leave it there: ACM
  checks it again to renew the certificate each year.
- **In use? No** for now. In Task 5 the load balancer will use it.

**IAM → Identity providers → `token.actions.githubusercontent.com`** is AWS trusting GitHub's
signature.
- **IAM → Roles → `sports-notifier-github` → Trust relationships.** Read it line by line:
  - `Federated`: only tokens signed by GitHub's identity provider.
  - `aud = sts.amazonaws.com`: tokens meant for AWS, not for another service.
  - `sub` is your repository's `main` branch or its `pull_request` runs. A workflow in someone
    else's repository, or on another branch, gets nothing.
- **Permissions** tab → `push-sports-notifier-image`: it can log in to ECR and push to the one
  repository, and nothing else. If GitHub were compromised, the damage is limited to pushing an image.
- Compare it with your own `AWSReservedSSO_AdministratorAccess-SportsApp_…` role: that one can do
  everything, and only you, with your passkey, can use it.

**Billing and Cost Management → Budgets → `sports-notifier-monthly`**: $25 a month, emailing you
at 40% and 100% of actual spend and at 100% of the forecast. Spend data updates a few times a day,
so this is a backstop; `make aws-status` is how you check right now.

**Billing → Cost allocation tags**: within 24 hours `project` appears here. Activate it
(`docs/aws-setup.md`, step 8).

## 2. The cluster (`infra/cluster`, while `make aws-up`'s cluster is running)

This is the part that bills by the hour, about $0.45 an hour. Take the tour while the app is up, then
run `make aws-down`.

**VPC → Your VPCs → `sports-notifier`** (10.40.0.0/16) is the private network everything lives in.
- **Subnets**: three `sports-notifier-public-…`, one per availability zone (`us-east-2a`, `b`, `c`),
  each a /20. Each zone is a separate data center, so losing one costs one machine and one broker.
- **Route tables**: the public table sends `0.0.0.0/0` to an **internet gateway** (`igw-…`). That
  is how machines reach ECR, S3 and ESPN.
- **NAT gateways: none.** A NAT gateway lets machines in *private* subnets reach the internet, and
  costs about $32 a month plus traffic. Here the machines sit in public subnets with their own
  public addresses, and security groups keep the outside out. For a cluster that lives a few hours,
  that is the trade.

**EC2 → Instances**: three `t3.large`, one per zone (the **Availability Zone** column). Nobody made
them by hand: EKS's managed node group runs an **Auto Scaling group** (EC2 → Auto Scaling Groups)
that keeps exactly 3. Terminate one and watch a new one appear within minutes.
- **Security groups** (an instance → **Security** tab): the node group's inbound rules. Find the
  one allowing ports **30080** and **30443** *from the load balancer's security group*, not from the
  internet. Traefik listens on those ports on every node.
- **Volumes**: a 20 GiB root disk per machine, plus `gp3` volumes appearing as Kafka (3 × 10 GiB),
  Postgres (10 GiB) and Redis (1 GiB) start. The EBS CSI driver made those from the pods' volume
  claims. Their **Tags** include `project` (from the storage class) and the claim's name
  (`kubernetes.io/created-for/pvc/name`).
- **Load Balancers → `sports-notifier`**: a **Network** load balancer, internet-facing, in all three
  subnets. **Listeners**: TCP 80 and **TLS 443** with the ACM certificate (open tour 1's certificate
  again: **In use? Yes** now). TLS ends here; Traefik only sees plain HTTP.
- **Target Groups → `sports-notifier-web` / `-websecure`**: **Targets** shows the three instances
  on 30080 / 30443, each **healthy**. That is the load balancer checking each node. **Attributes**:
  proxy protocol v2 on, which is how Traefik learns your real IP (its access log shows it).

**EKS → Clusters → `sports-notifier`** is Kubernetes, run by AWS.
- **Overview**: version **1.35**, status Active. **Networking**: API server endpoint access
  "Public and private", with the public side allowed only from your IP (`/32`): aws-up passes it.
- **Compute**: the node group, its instance type and its 3 nodes.
- **Add-ons**: `vpc-cni` → **Edit** → **Optional configuration settings** shows
  `{"enableNetworkPolicy":"true"}` (cancel without saving). That turns on the agent that enforces the
  chart's NetworkPolicies, which EKS otherwise silently ignores (CLAUDE.md, open problem 2). Also
  `coredns`, `kube-proxy`, `eks-pod-identity-agent`, `aws-ebs-csi-driver`.
- **Access**: an access entry for your `AWSReservedSSO_…` role. That is why `kubectl` works for you.
- **Access → Pod Identity associations**: `ebs-csi-controller-sa` (makes disks) and `postgres`
  (reads the dump). A pod gets an AWS role only through one of these.
- **Resources → Workloads**: the same pods `kubectl get pods` lists. **Pods** → `gateway-…` shows its
  node, so you can see two gateways on different machines.

## 3. Drift: someone changed it by hand

Terraform's state says what it built; the console lets anyone change it anyway. `plan` compares the
two.

1. **EC2 → Load Balancers → `sports-notifier` → Tags → Manage tags** → add `owner` = `console`.
   Save.
2. In the terminal:
   ```bash
   CLOUDFLARE_API_TOKEN=$(security find-generic-password -s sports-notifier-cloudflare -w) \
     AWS_PROFILE=sports terraform -chdir=infra/cluster plan -var api_cidr=$(curl -s https://checkip.amazonaws.com)/32
   ```
   (The token because this stack also owns the Cloudflare record; `plan` reads it to compare.)
3. You should see `aws_lb.this will be updated in-place`, with `- "owner" = "console" -> null` under
   `tags_all` (every tag, including the `project` one the provider adds to everything). Terraform noticed the change it didn't make and offers to undo it. "Plan: 0 to add, 1 to
   change, 0 to destroy."
4. Don't apply. Remove the tag in the console, run `plan` again, and see "No changes."

This is why the console is for looking, and changes go through code: the code and the state stay
the record of what exists.

## 4. After `make aws-down`

**S3 → `sports-notifier-dumps-…` → `dumps/` → `latest.dump`**: the database, written by
`make aws-down` after it checked the dump (`scripts/aws-backup.sh`).
- **Show versions** (the toggle above the list): one version per teardown. The newest is what the
  next `make aws-up` restores; older ones are deleted 30 days after a newer one replaces them.
- The size is small, kilobytes for a few accounts. It is `pg_dump`'s compressed format.

**Everything that bills by the hour is gone.** Check each, in us-east-2:
- **EC2 → Instances**: none running (terminated ones linger in the list for about an hour; that is
  fine). **Volumes**: none. **Load Balancers**: none. **Target Groups**: none.
- **EKS → Clusters**: none. **VPC → Your VPCs**: only the `default` one AWS created with the
  account.
- `make aws-status` checks the same list from the terminal and prints "nothing billing by the hour".

**The next day, Billing → Cost Explorer**: group by **Service**, last 7 days, daily. You should see
EC2 and EKS for the hours the cluster ran, and almost nothing on the days it was down. With the
`project` tag activated (`docs/aws-setup.md`, step 8), **Filter → Tag → project =
sports-notifier** shows just this app.

**Then `make aws-up` again** and open the postgres pod's logs (EKS → Resources → Pods →
`postgres-0` → **Logs**, or `kubectl --context sports-notifier-aws logs postgres-0 -c fetch-dump`).
You should see `downloading s3://…/dumps/latest.dump`, and your account still logs in.
