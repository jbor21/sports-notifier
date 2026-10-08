package gateway;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One browser tab's socket: who it is, when it last sent anything, and which games it shows.
 *
 * Sends never block. The gateway's one pub/sub thread delivers every notification and game update
 * for all its tabs; a blocking write to a tab whose peer stopped reading (a laptop asleep, a dead
 * mobile link) would hold that thread until a timeout and freeze every other tab on the gateway. So
 * frames are queued with Jetty's async send, and a tab with more than MAX_IN_FLIGHT unwritten frames
 * is closed with 1013 ("try again later"): it reconnects and refetches, as after any drop. The lock
 * only keeps one tab's frames in the order they were sent.
 */
public final class SocketSession implements ClientSocket {

    /** Frames queued for this tab and not yet written; past this the tab is too far behind and is closed. */
    static final int MAX_IN_FLIGHT = 256;

    private final String userId;
    private final ClientSocket socket;
    private volatile long lastReceivedMillis;
    private int inFlight;
    private boolean tooSlow;
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
        if (tooSlow || !socket.isOpen()) return;
        if (inFlight >= MAX_IN_FLIGHT) {
            tooSlow = true;
            System.err.println("[SocketSession] " + userId + ": " + inFlight + " frames unwritten; closing the tab");
            socket.close(1013, "too far behind");
            return;
        }
        inFlight++;
        socket.sendAsync(text, this::written);
    }

    private synchronized void written() {
        inFlight--;
    }

    @Override
    public synchronized void close(int code, String reason) {
        socket.close(code, reason);
    }
}
