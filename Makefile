# Everyday commands. CI runs these same targets, so "passes in CI" means "works on the laptop".

TEST_REDIS := sports-notifier-test-redis
CLUSTER    := sports-notifier
RELEASE    := sports-notifier
CHART      := deploy/helm/sports-notifier
# Always talk to the k3d cluster, whatever kubectl's current context is (e.g. after switching to EKS).
KUBE_CONTEXT ?= k3d-$(CLUSTER)
export KUBE_CONTEXT
# A new tag per build so Kubernetes always rolls pods onto new code. Uncommitted changes get a unique suffix.
TAG        := $(shell git rev-parse --short HEAD)$(shell test -n "$$(git status --porcelain)" && echo "-dirty-$$(date +%s)")

.PHONY: test cluster up simulate smoke logs down

# Create the cluster, or start it if it exists but is stopped (e.g. after OrbStack restarts). Then wait
# until Kubernetes says it is ready: `k3d cluster create --wait` can return while the API server still
# answers "unable to handle the request", and the image import in `make up` then fails (seen in CI).
cluster:
	@if k3d cluster get $(CLUSTER) >/dev/null 2>&1; then \
		echo "Cluster $(CLUSTER) already exists"; \
		k3d cluster start $(CLUSTER) >/dev/null; \
	else \
		k3d cluster create $(CLUSTER) -p "127.0.0.1:8080:80@loadbalancer" --wait; \
	fi
	@for i in $$(seq 1 60); do \
		kubectl --context $(KUBE_CONTEXT) get --raw /readyz >/dev/null 2>&1 && break; \
		sleep 2; \
	done
	@kubectl --context $(KUBE_CONTEXT) get --raw /readyz >/dev/null || { echo "Kubernetes API not ready after 2 minutes"; exit 1; }
	@kubectl --context $(KUBE_CONTEXT) wait --for=condition=Ready node --all --timeout=120s >/dev/null
	@echo "Cluster $(CLUSTER) is ready"

up:
	@k3d cluster get $(CLUSTER) >/dev/null 2>&1 || { echo "Cluster $(CLUSTER) not found. Run: make cluster"; exit 1; }
	docker build -t sports-notifier:$(TAG) .
	k3d image import sports-notifier:$(TAG) -c $(CLUSTER)
	helm upgrade --install --kube-context $(KUBE_CONTEXT) $(RELEASE) $(CHART) -f $(CHART)/values-local.yaml --set image.tag=$(TAG) $(HELM_ARGS) --wait --timeout 5m

simulate:
	@scripts/simulate.sh

smoke:
	@scripts/smoke-test.sh

logs:
	-kubectl --context $(KUBE_CONTEXT) get pods -o wide
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
