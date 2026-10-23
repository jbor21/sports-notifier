# AWS: one-time setup

Do this once, before the first `terraform apply`. Most of it is in the AWS console. It takes about
an hour, mostly waiting for emails. Everything after this runs from `make aws-*`.

Why each step exists is in `docs/superpowers/specs/2026-10-22-phase-5-aws-design.md`, section 2.

## 1. Lock down the root user

The root user (the email you signed up with) can do anything, including close the account. Give it
a second factor and then stop using it.

- [ ] Sign in as root. Top right, your account name → **Security credentials** → **Assign MFA device**.
  Use an authenticator app or a passkey.
- [ ] Same menu → **Account** → "IAM user and role access to Billing information" → **Edit** →
  activate. Without this, even an administrator you create can't see Billing or Budgets.

## 2. Your everyday login: IAM Identity Center

Identity Center gives you a login that hands out short-lived credentials, to the console and to the
CLI alike. So there are no access keys on the Mac to leak.

- [ ] Check that the region picker (top right) says **US East (Ohio) us-east-2**. Identity Center
  lives in one region; keep everything in this one.
- [ ] Search for **IAM Identity Center** → **Enable**. It offers to create an AWS Organization
  around your account. Accept: that is the normal setup, and it costs nothing.
- [ ] **Users** → **Add user**. Pick a username and use your email. Accept the invite email, set a
  password, and register MFA.
- [ ] **Permission sets** → **Create permission set** → **Predefined** → `AdministratorAccess`.
  Set the session duration to 8 hours.
- [ ] **AWS accounts** → tick your account → **Assign users or groups** → your user →
  `AdministratorAccess`.
- [ ] **Dashboard** → copy the **AWS access portal URL** (`https://d-….awsapps.com/start`).
  Bookmark it: it is how you open the console from now on.
- [ ] Sign out of root. Sign in through the portal URL as your new user and open the console from
  there.

## 3. Tools on the Mac

- [ ] `brew install awscli`
- [ ] `brew tap hashicorp/tap && brew install hashicorp/tap/terraform`. Homebrew's own `terraform`
  formula is stuck at 1.5.7, and this project needs 1.10 or later (S3 state locking).
- [ ] `aws --version` and `terraform version` both print a version.

## 4. Connect the CLI to your login

- [ ] `aws configure sso`, answering:
  - SSO session name: `sports`
  - SSO start URL: the portal URL from step 2
  - SSO region: `us-east-2`
  - registration scopes: press Enter
  - A browser tab asks you to approve. Then pick your account and `AdministratorAccess`.
  - CLI default region: `us-east-2`; output format: `json`; profile name: `sports`.
- [ ] Check: `AWS_PROFILE=sports aws sts get-caller-identity`. You should see your account id and an
  ARN containing `AWSReservedSSO_AdministratorAccess`.
- [ ] When a session expires (8 hours), run `aws sso login --profile sports`.

## 5. Room for three machines

New accounts can start with a low limit on how many vCPUs may run at once. Three t3.large need 6.

- [ ] Console → **Service Quotas** → **AWS services** → **Amazon Elastic Compute Cloud (Amazon EC2)**
  → "Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances". The **Applied account-level
  quota value** must be at least **6**. If it is lower, **Request increase** to 16. Approval takes
  minutes to a day.
- [ ] Or from the terminal:
  `AWS_PROFILE=sports aws service-quotas get-service-quota --service-code ec2 --quota-code L-1216C47A --query Quota.Value`

## 6. Cloudflare token

Terraform writes two DNS records in your zone: the certificate's validation record and
`sports.<domain>`. It needs a token that can edit DNS in that one zone and do nothing else.

- [ ] Cloudflare dashboard → **My Profile** → **API Tokens** → **Create Token** → template
  **Edit zone DNS** → Zone Resources: *Include → Specific zone →* your domain → **Create Token**.
- [ ] Put it in the macOS keychain. The command prompts for the token, so it never appears in your
  shell history:
  `security add-generic-password -a "$USER" -s sports-notifier-cloudflare -w`
- [ ] Check it reads back: `security find-generic-password -s sports-notifier-cloudflare -w`
- [ ] Check Cloudflare accepts it:
  `curl -s -H "Authorization: Bearer $(security find-generic-password -s sports-notifier-cloudflare -w)" https://api.cloudflare.com/client/v4/user/tokens/verify | jq -r .result.status`
  should print `active`.

## 7. Does ESPN answer from AWS?

The poller only works on AWS if ESPN answers requests coming from an AWS address. CloudShell is a
terminal inside the console that runs on an AWS machine, so it answers that question before anything
is built.

- [ ] Console, region **us-east-2** → the `>_` icon in the top bar (**CloudShell**). Paste:
  ```bash
  UA='sports-notifier/1.0 (+https://github.com/boboborenstein-collab/sports-notifier)'
  for host in site.web.api.espn.com site.api.espn.com; do
    for p in basketball/nba football/nfl baseball/mlb hockey/nhl; do
      printf '%-22s %-18s %s\n' "$host" "$p" \
        "$(curl -s -m 15 -o /dev/null -w '%{http_code}' -A "$UA" "https://$host/apis/site/v2/sports/$p/scoreboard")"
    done
  done
  curl -s https://checkip.amazonaws.com
  ```
- [ ] **Pass:** all four `site.web.api.espn.com` lines say `200`. Those are the app's defaults. The
  `site.api.espn.com` lines are for the record.
- [ ] Paste the output below.

### ESPN from AWS

2026-10-23, CloudShell in us-east-2, from `77.113.248.56` (in AWS's published `77.112.0.0/14`, us-east-2, EC2):

```
site.web.api.espn.com  basketball/nba     200
site.web.api.espn.com  football/nfl       200
site.web.api.espn.com  baseball/mlb       200
site.web.api.espn.com  hockey/nhl         200
site.api.espn.com      basketball/nba     200
site.api.espn.com      football/nfl       200
site.api.espn.com      baseball/mlb       200
site.api.espn.com      hockey/nhl         200
```

**Pass.** Both hosts answer an AWS address with the app's honest User-Agent. Unlike 2026-10-01, when
`site.api` refused a cloud fetch (DESIGN.md decision 10). The in-cluster check in `make aws-up`
repeats this every time.

## 8. After `infra/base` exists (Task 3)

- [ ] **Billing and Cost Management** → **Cost allocation tags** → find `project` → **Activate**.
  The tag appears only after a resource carries it, up to 24 hours later. Once active, Cost Explorer
  can show this project's spend on its own.
