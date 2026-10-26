# Phase 5 quiz

Interview-style questions on Phase 5, running the app on AWS. Each question has a model answer under a fold. Try answering out loud before you open it.

To take it: ask Claude, "quiz me on Phase 5". Questions marked **Review** come first, then **Not yet taken**.

| # | Topic | First run |
|---|---|---|
| 1 | Why two Terraform stacks | **Not yet taken** |
| 2 | What Terraform owns, and what Kubernetes creates behind its back | **Not yet taken** |
| 3 | Why no load-balancer controller | **Not yet taken** |
| 4 | What proxy protocol fixes | **Not yet taken** |
| 5 | Why the storage class says `WaitForFirstConsumer` | **Not yet taken** |
| 6 | What Kafka's `rack` buys | **Not yet taken** |
| 7 | What OIDC and SSO replace | **Not yet taken** |
| 8 | The restore marker's reasoning | **Not yet taken** |
| 9 | What is still not encrypted, and why that is acceptable for now | **Not yet taken** |
| 10 | Why the first teardown hung, and the fix | **Not yet taken** |
| 11 | Why HSTS was missing behind the load balancer | **Not yet taken** |

---

### 1. Why two Terraform stacks

> Everything could live in one Terraform configuration. Why split it into `infra/base` and `infra/cluster`?

<details>
<summary>Model answer</summary>

Because they live on different clocks. `cluster` is created and destroyed every session: the network, EKS, the machines, the load balancer, the DNS record. That is where the hourly cost is. `base` must survive every teardown: the image registry (CI pushes images while AWS is off), the dump bucket (the only copy of the accounts between sessions), the TLS certificate (issuance needs a DNS check, and re-issuing every session is slow and rate-limited), the GitHub role, and the budget alarm. In one stack, `terraform destroy` would delete the dump bucket and the certificate along with the machines, so the split is what makes "destroy" safe to type. `cluster` reads `base`'s outputs (`terraform_remote_state`), so the dependency only points one way.

**Model answer:** "Base is what must survive a teardown, like the dumps, the registry and the certificate, for under a dollar a month. Cluster is what bills by the hour and gets destroyed every session. Splitting them means destroy can never take the data with it."

</details>

---

### 2. What Terraform owns, and what Kubernetes creates behind its back

> After `terraform destroy` says "Destroy complete", why does `aws-down` still run a leftover sweep?

<details>
<summary>Model answer</summary>

Because Terraform only deletes what is in its state, and some billing resources are created by Kubernetes, not Terraform. A volume claim makes the EBS CSI driver create an EBS disk (`reclaimPolicy: Delete` removes it only if the claim is deleted while the cluster is alive). The VPC CNI creates network interfaces for pods. Terraform never heard of either. So `aws-down` deletes the claims and waits for the volumes to go *before* destroy, and afterwards `aws-leftovers.sh` lists instances, load balancers, target groups, volumes, unattached ENIs, Elastic IPs, NAT gateways, clusters and VPCs by tag. It exits 0 only on "nothing billing". A failed listing is never "nothing billing".

</details>

---

### 3. Why no load-balancer controller

> The usual way to expose an app on EKS is the AWS Load Balancer Controller. Why did you create the NLB in Terraform instead?

<details>
<summary>Model answer</summary>

So that `destroy` owns it. A controller creates the load balancer from inside the cluster, so it is not in Terraform's state. If the cluster goes before the controller cleans up, the load balancer and its security groups are left behind. They keep billing and block the VPC's deletion, which is the classic EKS teardown failure. With Terraform owning the NLB, its target groups and listeners, one `destroy` removes all of it. It is also fewer moving parts: no controller pod, no IAM role for it, no webhook. The cost: Traefik runs as a NodePort service on fixed ports (30080/30443), and Terraform attaches the node group's Auto Scaling group to the target groups itself.

</details>

---

### 4. What proxy protocol fixes

> The NLB ends TLS and opens its own connection to Traefik. What does Traefik lose, and how do you get it back?

<details>
<summary>Model answer</summary>

The client's address. With TLS ending at the NLB (and client IP preservation off), Traefik sees the load balancer's private address as the source of every request, so the gateway's login throttling would treat every user as one address. Proxy protocol v2 makes the NLB put a small header with the real client address at the start of each connection. Traefik reads it only from `trustedIPs: 10.40.0.0/16`, the VPC, so nobody on the internet can forge it. Proven live: Traefik's access log showed the Mac's public address, not a `10.40.x.x` one. An X-Forwarded-For header can't do this job, because an NLB is layer 4: it never parses HTTP, so it can't add headers.

</details>

---

### 5. Why the storage class says `WaitForFirstConsumer`

> What would go wrong with the default `Immediate` volume binding?

<details>
<summary>Model answer</summary>

An EBS disk lives in one availability zone and can only attach to a machine in that zone. With `Immediate`, the disk is created as soon as the claim exists, in whatever zone the driver picks. Then the scheduler may want the pod on a machine in another zone, and the pod is stuck Pending forever. `WaitForFirstConsumer` delays creating the disk until the pod is scheduled, then makes it in that pod's zone. With Kafka spread one broker per zone, that matters three times over.

</details>

---

### 6. What Kafka's `rack` buys

> Each broker gets `broker.rack` set to its zone. What does Kafka do with it?

<details>
<summary>Model answer</summary>

It spreads each partition's copies across racks. With `rack.topologyKey: topology.kubernetes.io/zone`, Strimzi reads the zone label of the machine each broker runs on and sets it as that broker's rack. With replication factor 3 and three racks, every partition has one copy in each zone. Without it, Kafka could put two copies of a partition on brokers in the same zone, and one zone outage could take two of three copies. With `min.insync.replicas=2`, losing one zone still leaves two copies, so writes and reads continue. Checked live: the three brokers sat on three nodes in 2a, 2b and 2c, each reporting its zone.

</details>

---

### 7. What OIDC and SSO replace

> How do GitHub Actions and your laptop get AWS credentials, and what did that replace?

<details>
<summary>Model answer</summary>

It replaced long-lived access keys. Before, you made an IAM user and pasted its key into GitHub secrets and `~/.aws/credentials`, where it worked forever if leaked.

- **CI:** GitHub signs a short-lived identity token for each workflow run. AWS trusts GitHub's signer (an OIDC provider in `base`) and swaps the token for one hour of a role's credentials, but only if the token's subject matches this repository's main branch or its pull requests. That role can push to one ECR repository. This repo uses GitHub's immutable subject, with owner and repo ids, which also defeats someone taking over the repo name after a rename.
- **Laptop:** IAM Identity Center (SSO). `aws sso login` gives temporary credentials that expire, which is why `aws-status` failed the morning after with "Token has expired".

</details>

---

### 8. The restore marker's reasoning

> Postgres restores from S3 on first boot. Why is there a `restore-done` marker, why is it outside `PGDATA`, and why does a missing marker wipe the data directory?

<details>
<summary>Model answer</summary>

The rules make three bad outcomes impossible:

1. **Restoring over live data after a restart.** Postgres's image runs its init scripts only when `PGDATA` is empty, but the `fetch-dump` init container runs on every pod start. So the first thing it checks is the marker: if `restore-done` exists, it exits ("already restored or started fresh"). Proven live: a deleted pod came back with the same 9 users and 70 notifications.
2. **A half-restored database that looks fine.** The marker is written only after `pg_restore --exit-on-error` succeeds. It sits at the volume's root, outside `PGDATA`, so it can't be part of a half-written data directory. No marker means the last attempt never finished, so `fetch-dump` (and the container's start script, for restarts that skip init containers) wipes `PGDATA` and tries again. Proven live: a junk dump crash-looped and was never Ready.
3. **Starting empty when the dump exists.** Only S3's 404 ("no such object") starts an empty database. Any other error (permissions, network) exits 1, so the pod retries. The restore role has `s3:ListBucket` for exactly this reason: without it, S3 answers a missing object with 403, which would be indistinguishable from a real permission error.

</details>

---

### 9. What is still not encrypted, and why that is acceptable for now

> Is the traffic between your services encrypted?

<details>
<summary>Model answer</summary>

No, and that's deliberate. Browsers talk TLS (1.3/1.2) to the NLB with an ACM certificate, and HTTP redirects to HTTPS with HSTS. Behind the NLB everything is plain inside the VPC: NLB to Traefik, Traefik to the gateway, and the services to Kafka, Redis and Postgres. What protects it instead:

- the VPC is private
- the nodes' security groups admit only the NLB on the node ports
- network policies, now enforced on EKS through the VPC CNI's engine, admit only the pods that need each service (the smoke test's stranger pod is refused)

The remaining risk is something already inside the cluster sniffing traffic, and by then it has bigger options. TLS on the three stores means certificates for each, Strimzi's TLS listener, and trust stores in every client. That's real work deferred, and it's written down in DESIGN.md's "not building (yet)".

</details>

---

### 10. Why the first teardown hung, and the fix

> The first `make aws-down` sat at "uninstall the app" for a long time. What was it waiting for?

<details>
<summary>Model answer</summary>

Three KafkaTopic resources, each holding a finalizer, `strimzi.io/topic-operator`. A finalizer is a "don't delete me until I've cleaned up" flag that a controller must remove. The topic operator runs inside the Kafka cluster's entity operator, and the uninstall deleted that first. So the topics were marked for deletion with nobody left to clear the flag, and `helm uninstall --wait` waited forever. The by-hand fix was to clear the finalizers, which is safe because the Kafka they described was gone. The lasting fix: `aws-down` deletes the topics first, while the operator is alive, and clears any leftover finalizer. The test asserts that order. The general lesson: when you tear down an operator-managed stack, delete the custom resources before their operator.

</details>

---

### 11. Why HSTS was missing behind the load balancer

> Traefik had the HSTS middleware on the HTTPS entry point, yet the header never appeared. Why?

<details>
<summary>Model answer</summary>

Traefik's headers middleware adds `Strict-Transport-Security` only to requests it received over TLS, since sending HSTS over plain HTTP is meaningless. Here the NLB ends TLS, so every request reaches Traefik as plain HTTP and the middleware skips the header. The fix is `forceSTSHeader: true`. This is safe because the only path to that entry point is the NLB's TLS listener. It's the same shape of problem as question 4: once something in front ends TLS, the app behind it can't see facts about the original connection unless you deliberately pass them on or configure around it.

</details>
