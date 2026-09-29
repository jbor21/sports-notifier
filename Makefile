# Everyday commands. CI runs these same targets, so "passes in CI" means "works on the laptop".

TEST_REDIS := sports-notifier-test-redis
CLUSTER    := sports-notifier
RELEASE    := sports-notifier
CHART      := deploy/helm/sports-notifier
# A new tag per build so Kubernetes always rolls pods onto new code. Uncommitted changes get a unique suffix.
TAG        := $(shell git rev-parse --short HEAD)$(shell test -n "$$(git status --porcelain)" && echo "-dirty-$$(date +%s)")

.PHONY: test cluster up simulate smoke logs down

cluster:
	@if k3d cluster get $(CLUSTER) >/dev/null 2>&1; then \
		echo "Cluster $(CLUSTER) already exists"; \
	else \
		k3d cluster create $(CLUSTER) -p "8080:80@loadbalancer" --wait; \
	fi

up:
	@k3d cluster get $(CLUSTER) >/dev/null 2>&1 || { echo "Cluster $(CLUSTER) not found. Run: make cluster"; exit 1; }
	docker build -t sports-notifier:$(TAG) .
	k3d image import sports-notifier:$(TAG) -c $(CLUSTER)
	helm upgrade --install $(RELEASE) $(CHART) -f $(CHART)/values-local.yaml --set image.tag=$(TAG) --wait --timeout 5m

simulate:
	@scripts/simulate.sh

smoke:
	@scripts/smoke-test.sh

logs:
	-kubectl get pods -o wide
	-kubectl logs -l app.kubernetes.io/part-of=sports-notifier --all-containers --prefix --tail=50 --max-log-requests=20
	-kubectl get events --sort-by=.lastTimestamp | tail -30

down:
	k3d cluster delete $(CLUSTER)

# The tests expect Redis on localhost:6379 (they use DB 1 and flush it).
# Start a throwaway one, and remove it whether the tests pass or fail.
test:
	@if nc -z localhost 6379 2>/dev/null; then \
		echo "Port 6379 is in use (local Redis?). Stop it and re-run make test."; exit 1; \
	fi
	@docker run -d --rm --name $(TEST_REDIS) -p 6379:6379 redis:7.4 >/dev/null; \
	trap 'docker rm -f $(TEST_REDIS) >/dev/null 2>&1' EXIT; \
	until docker exec $(TEST_REDIS) redis-cli ping >/dev/null 2>&1; do sleep 0.2; done; \
	mvn -B test
