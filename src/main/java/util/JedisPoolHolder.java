package util;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

public final class JedisPoolHolder {

    private JedisPoolHolder() {}

    public static JedisPool create() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(32);
        cfg.setMaxIdle(16);
        cfg.setMinIdle(2);
        cfg.setTestOnBorrow(true);
        return new JedisPool(cfg, host, port, 2000);
    }

    public static Jedis dedicated() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        return new Jedis(host, port);
    }

    /**
     * Blocks until Redis answers PING, or exits the process after 60 s so Kubernetes restarts the
     * pod as before. On a fresh deploy services often start before Redis; waiting here replaces a
     * crash loop full of UnknownHostException with one "waiting for Redis" line every 2 s.
     */
    public static void awaitRedisOrExit(String serviceName) {
        String host = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        if (!waitForRedis(host, port, Duration.ofSeconds(60), Duration.ofSeconds(2), serviceName)) {
            System.err.println("[" + serviceName + "] Redis at " + host + ":" + port + " not reachable after 60s, exiting");
            System.exit(1);
        }
    }

    public static boolean waitForRedis(String host, int port, Duration max, Duration every) {
        return waitForRedis(host, port, max, every, null);
    }

    private static boolean waitForRedis(String host, int port, Duration max, Duration every, String serviceName) {
        long deadline = System.nanoTime() + max.toNanos();
        while (true) {
            try (Jedis jedis = new Jedis(host, port, 1000)) {
                if ("PONG".equals(jedis.ping())) return true;
            } catch (Exception e) {
                if (serviceName != null) System.out.println("[" + serviceName + "] waiting for Redis at " + host + ":" + port);
            }
            if (System.nanoTime() + every.toNanos() > deadline) return false;
            try {
                Thread.sleep(every.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
