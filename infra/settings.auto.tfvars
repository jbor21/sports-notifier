# Shared by infra/base and infra/cluster (each links to this file). Not secret: the Cloudflare token
# comes from the keychain and AWS access from the SSO profile, never from here. The budget alert's
# email is personal, so it lives in infra/base/private.auto.tfvars (git-ignored), not here.
region     = "us-east-2"
domain     = "expense-app.uk" # the Cloudflare zone
subdomain  = "sports"         # the app lives at sports.expense-app.uk
budget_usd = 25
