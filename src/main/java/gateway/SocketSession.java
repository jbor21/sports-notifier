package gateway;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One browser tab's socket: who it is, when it last sent anything, and which games it shows.
 *
 * Pub/sub threads (notifications, game updates), the socket's own thread (pong, current state), and
 * the idle sweeper can all write to one tab, and Jetty refuses a second blocking send on a session
 * while one is in progress. So every send and close takes this session's lock.
 */
public final class SocketSession implements ClientSocket {

    private final String userId;
    private final ClientSocket socket;
    private volatile long lastReceivedMillis;
    private final Set<String> games = ConcurrentHashMap.newKeySet();

    public SocketSession(String userId, ClientSocket socket, long nowMillis) {
        this.userId = userId;
        this.socket = socket;
        this.lastReceivedMillis = nowMillis;
    }

    public String userId() { return userId; }
    public long lastReceivedMillis() { return lastReceivedMillis; }
    public void received(long nowMillis) { lastReceivedMillis = nowMillis; }
    /** The game ids this tab asked for, as {@link GameSubscriptions} keeps them. */
    public Set<String> games() { return games; }

    @Override public boolean isOpen() { return socket.isOpen(); }

    @Override
    public synchronized void send(String text) {
        if (socket.isOpen()) socket.send(text);
    }

    @Override
    public synchronized void close(int code, String reason) {
        socket.close(code, reason);
    }
}
