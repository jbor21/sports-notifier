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
