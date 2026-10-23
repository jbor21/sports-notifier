# Shared by infra/base and infra/cluster (each links to this file). Not secret: the Cloudflare token
# comes from the keychain and AWS access from the SSO profile, never from here.
region       = "us-east-2"
domain       = "expense-app.uk" # the Cloudflare zone
subdomain    = "sports"         # the app lives at sports.expense-app.uk
budget_email = "jacobjborenstein@gmail.com"
budget_usd   = 25
