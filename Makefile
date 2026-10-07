# Everyday commands. CI runs these same targets, so "passes in CI" means "works on the laptop".

TEST_REDIS := sports-notifier-test-redis
CLUSTER    := sports-notifier
RELEASE    := sports-notifier
CHART      := deploy/helm/sports-notifier
# Strimzi 1.2.0 runs Kafka 4.2.0 to 4.3.1; values.yaml asks for 4.3.1, the same as kafka-clients in pom.xml.
STRIMZI_CHART   := oci://quay.io/strimzi-helm/strimzi-kafka-operator
STRIMZI_VERSION := 1.2.0
# Postgres's data lives in this folder on the Mac, mounted into the k3d node, so `make down` keeps it.
PG_DIR ?= $(HOME)/.sportsnotifier/postgres
# Always talk to the k3d cluster, whatever kubectl's current context is (e.g. after switching to EKS).
KUBE_CONTEXT ?= k3d-$(CLUSTER)
export KUBE_CONTEXT
# A new tag per build so Kubernetes always rolls pods onto new code. Uncommitted changes get a unique suffix.
TAG        := $(shell git rev-parse --short HEAD)$(shell test -n "$$(git status --porcelain)" && echo "-dirty-$$(date +%s)")

.PHONY: test cluster up simulate smoke logs down

# Create the cluster, or start it if it exists but is stopped (e.g. after OrbStack restarts). Then wait
# until Kubernetes says it is ready: `k3d cluster create --wait` can return while the API server still
# answers "unable to handle the request", and the image import in `make up` then fails (seen in CI).
# Then install the Strimzi operator: a program in the cluster that turns resources like "a Kafka with one
# broker" into pods and volumes. It lives outside our chart because it adds the Kafka resource types our
# chart uses, and those must exist before our chart is installed. Re-running the install is safe.
cluster:
	@if k3d cluster get $(CLUSTER) >/dev/null 2>&1; then \
		echo "Cluster $(CLUSTER) already exists"; \
		docker inspect k3d-$(CLUSTER)-server-0 --format '{{range .Mounts}}{{.Destination}}{{"\n"}}{{end}}' \
			| grep -qx /var/lib/sportsnotifier/postgres \
			|| { echo "The cluster has no Postgres folder mounted. Recreate the cluster: make down && make cluster"; exit 1; }; \
		k3d cluster start $(CLUSTER) >/dev/null; \
	else \
		mkdir -p $(PG_DIR); \
		k3d cluster create $(CLUSTER) -p "127.0.0.1:8080:80@loadbalancer" \
			--volume "$(PG_DIR):/var/lib/sportsnotifier/postgres@server:0" --wait; \
	fi
	@for i in $$(seq 1 60); do \
		kubectl --context $(KUBE_CONTEXT) get --raw /readyz >/dev/null 2>&1 && break; \
		sleep 2; \
	done
	@kubectl --context $(KUBE_CONTEXT) get --raw /readyz >/dev/null || { echo "Kubernetes API not ready after 2 minutes"; exit 1; }
	@kubectl --context $(KUBE_CONTEXT) wait --for=condition=Ready node --all --timeout=120s >/dev/null
	helm upgrade --install strimzi $(STRIMZI_CHART) --version $(STRIMZI_VERSION) --kube-context $(KUBE_CONTEXT) \
		-n strimzi --create-namespace --set watchNamespaces='{default}' --wait --timeout 5m
	@echo "Cluster $(CLUSTER) is ready"

# Helm's --wait covers our pods; Strimzi reports the broker ready in the Kafka resource's status.
up:
	@k3d cluster get $(CLUSTER) >/dev/null 2>&1 || { echo "Cluster $(CLUSTER) not found. Run: make cluster"; exit 1; }
	docker build -t sports-notifier:$(TAG) .
	k3d image import sports-notifier:$(TAG) -c $(CLUSTER)
	helm upgrade --install --kube-context $(KUBE_CONTEXT) $(RELEASE) $(CHART) -f $(CHART)/values-local.yaml --set image.tag=$(TAG) $(HELM_ARGS) --wait --timeout 5m
	kubectl --context $(KUBE_CONTEXT) wait kafka/$(RELEASE) --for=condition=Ready --timeout=5m

simulate:
	@scripts/simulate.sh

smoke:
	@scripts/smoke-test.sh

logs:
	-kubectl --context $(KUBE_CONTEXT) get pods -o wide
	-kubectl --context $(KUBE_CONTEXT) get kafka,kafkanodepool,kafkatopic
	-kubectl --context $(KUBE_CONTEXT) logs -l app.kubernetes.io/part-of=sports-notifier --all-containers --prefix --tail=50 --max-log-requests=20
	-kubectl --context $(KUBE_CONTEXT) get events --sort-by=.lastTimestamp | tail -30

down:
	k3d cluster delete $(CLUSTER)

# The tests expect Redis on localhost:6379 (they use DB 1 and flush it).
# Start a throwaway one, and remove it whether the tests pass or fail.
test:
	@if nc -z localhost 6379 2>/dev/null; then \
		echo "Port 6379 is in use (local Redis?). Stop it and re-run make test."; exit 1; \
	fi
	@docker run -d --rm --name $(TEST_REDIS) -p 6379:6379 redis:7.4 >/dev/null \
		|| { echo "Could not start the test Redis container (is Docker running?)"; exit 1; }; \
	trap 'docker rm -f $(TEST_REDIS) >/dev/null 2>&1' EXIT; \
	for i in $$(seq 1 50); do docker exec $(TEST_REDIS) redis-cli ping >/dev/null 2>&1 && break; sleep 0.2; done; \
	docker exec $(TEST_REDIS) redis-cli ping >/dev/null 2>&1 \
		|| { echo "Test Redis did not answer within 10s"; exit 1; }; \
	mvn -B test
