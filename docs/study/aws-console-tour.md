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
