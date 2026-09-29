# Everyday commands. CI runs these same targets, so "passes in CI" means "works on the laptop".

TEST_REDIS := sports-notifier-test-redis

.PHONY: test

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
