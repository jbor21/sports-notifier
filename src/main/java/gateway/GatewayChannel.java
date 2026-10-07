package gateway;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import util.RedisKeys;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This gateway's sockets, by user, and its own channel {@code gw:<gatewayId>}. Stage 2 publishes
 * there only for users this gateway holds (see {@link ConnectionRegistry}), so a gateway reads only
 * its own users' notifications and adding gateways spreads the work. Replaces the course version's
 * PSUBSCRIBE notify:*, where every gateway read every notification.
 */
public final class GatewayChannel {

    private final Jedis blocking;
    private final String gatewayId;
    private final ConcurrentHashMap<String, Set<ClientSocket>> socketsByUser = new ConcurrentHashMap<>();
    private final JedisPubSub subscriber = new JedisPubSub() {
        @Override
        public void onMessage(String channel, String message) {
            deliverLocally(message);
        }
    };
    private Thread subscriberThread;
    private volatile boolean running = true;

    public GatewayChannel(Jedis blocking, String gatewayId) {
        this.blocking = blocking;
        this.gatewayId = gatewayId;
    }

    public String gatewayId() {
        return gatewayId;
    }

    public void register(String userId, ClientSocket socket) {
        socketsByUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(socket);
    }

    /** @return true if that was the user's last socket on this gateway */
    public boolean unregister(String userId, ClientSocket socket) {
        if (userId == null) return false;
        boolean[] last = {false};
        socketsByUser.computeIfPresent(userId, (k, set) -> {
            set.remove(socket);
            if (!set.isEmpty()) return set;
            last[0] = true;
            return null;
        });
        return last[0];
    }

    /** Users with at least one socket here, for renewals and for graceful shutdown. */
    public Set<String> users() {
        return Set.copyOf(socketsByUser.keySet());
    }

    /** Messages are {"userId": ..., "payload": {notification}}; the socket gets the payload. */
    private void deliverLocally(String message) {
        String userId;
        String payload;
        try {
            JsonObject m = JsonParser.parseString(message).getAsJsonObject();
            userId = m.get("userId").getAsString();
            JsonElement p = m.get("payload");
            payload = p.toString();
        } catch (RuntimeException e) {
            System.err.println("[GatewayChannel] unreadable message on " + RedisKeys.GATEWAY_CHANNEL_PREFIX + gatewayId);
            return;
        }
        // The user may have closed their last tab since Stage 2 looked them up: nothing to do.
        Set<ClientSocket> sockets = socketsByUser.get(userId);
        if (sockets == null) return;
        for (ClientSocket s : sockets) {
            try {
                if (s.isOpen()) s.send(payload);
            } catch (RuntimeException e) {
                System.err.println("[GatewayChannel] send error: " + e.getMessage());
            }
        }
    }

    public void start() {
        subscriberThread = new Thread(() -> {
            while (running) {
                try {
                    blocking.subscribe(subscriber, RedisKeys.GATEWAY_CHANNEL_PREFIX + gatewayId);
                } catch (RuntimeException e) {
                    if (!running) return;
                    System.err.println("[GatewayChannel] subscribe error: " + e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }, "gateway-channel-" + gatewayId);
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    /** Graceful shutdown: tell every client to reconnect (4000 = "reconnect now", websocket.md). */
    public void closeAll(int code, String reason) {
        for (Set<ClientSocket> sockets : socketsByUser.values()) {
            for (ClientSocket s : sockets) {
                try { s.close(code, reason); } catch (RuntimeException ignored) {}
            }
        }
    }

    public void stop() {
        running = false;
        try { subscriber.unsubscribe(); } catch (RuntimeException ignored) {}
        try { blocking.close(); } catch (RuntimeException ignored) {}
        if (subscriberThread != null) subscriberThread.interrupt();
    }
}
