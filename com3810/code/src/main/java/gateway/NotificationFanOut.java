package gateway;

import io.javalin.websocket.WsContext;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-gateway in-memory registry of WebSocket clients plus a background thread
 * that runs PSUBSCRIBE notify:* on a dedicated Jedis connection. When Redis
 * publishes to `notify:<userId>`, we look up connected clients for that userId
 * and forward the JSON payload over the socket.
 */
public final class NotificationFanOut {

    private final Jedis blockingJedis;
    private final ConcurrentHashMap<String, Set<WsContext>> clientsByUser = new ConcurrentHashMap<>();
    private Thread subscriberThread;
    private volatile boolean running = true;

    public NotificationFanOut(Jedis blockingJedis) {
        this.blockingJedis = blockingJedis;
    }

    public void register(String userId, WsContext ws) {
        clientsByUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(ws);
    }

    public void unregister(String userId, WsContext ws) {
        if (userId == null) return;
        Set<WsContext> set = clientsByUser.get(userId);
        if (set != null) {
            set.remove(ws);
            if (set.isEmpty()) clientsByUser.remove(userId);
        }
    }

    public int connectedCount() {
        int n = 0;
        for (Set<WsContext> s : clientsByUser.values()) n += s.size();
        return n;
    }

    public void start() {
        subscriberThread = new Thread(() -> {
            while (running) {
                try {
                    blockingJedis.psubscribe(new JedisPubSub() {
                        @Override
                        public void onPMessage(String pattern, String channel, String message) {
                            if (!channel.startsWith("notify:")) return;
                            String userId = channel.substring("notify:".length());
                            Set<WsContext> set = clientsByUser.get(userId);
                            if (set == null || set.isEmpty()) return;
                            for (WsContext ws : set) {
                                try {
                                    if (ws.session.isOpen()) ws.send(message);
                                } catch (Exception e) {
                                    System.err.println("[FanOut] send error: " + e.getMessage());
                                }
                            }
                        }
                    }, "notify:*");
                } catch (Exception e) {
                    if (!running) return;
                    System.err.println("[NotificationFanOut] psubscribe error: " + e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }, "gateway-notify-psubscribe");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    public void stop() {
        running = false;
        try { blockingJedis.close(); } catch (Exception ignored) {}
        if (subscriberThread != null) subscriberThread.interrupt();
    }
}
