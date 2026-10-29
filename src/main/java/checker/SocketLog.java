package checker;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** What one socket (one tab) of a synthetic user saw: every push, and every time it was not connected. */
public final class SocketLog {

    public record Arrival(String messageId, String gameId, JsonObject body, long atMillis) {}

    /**
     * Not connected from {@code fromMillis} to {@code toMillis}; {@code Long.MAX_VALUE} while still down.
     * {@code silent}: the socket never said it ended; an unanswered ping found it dead, and the gap
     * starts at the last frame received (Phase 6.1 spec, 2).
     */
    public record Gap(long fromMillis, long toMillis, boolean silent) {}

    private final String userId;
    private final int tab;
    private final List<Arrival> arrivals = new ArrayList<>();
    private final List<Gap> gaps = new ArrayList<>();

    public SocketLog(String userId, int tab) {
        this.userId = userId;
        this.tab = tab;
    }

    public String userId() {
        return userId;
    }

    public int tab() {
        return tab;
    }

    public synchronized void arrived(Arrival a) {
        arrivals.add(a);
    }

    /** Opens a gap unless one is already open. */
    public synchronized void disconnected(long atMillis) {
        disconnected(atMillis, false);
    }

    /** Opens a gap unless one is already open; {@code silent} as in {@link Gap}. */
    public synchronized void disconnected(long atMillis, boolean silent) {
        if (gaps.isEmpty() || gaps.get(gaps.size() - 1).toMillis() != Long.MAX_VALUE) {
            gaps.add(new Gap(atMillis, Long.MAX_VALUE, silent));
        }
    }

    /** Closes the open gap, if any. */
    public synchronized void connected(long atMillis) {
        if (!gaps.isEmpty() && gaps.get(gaps.size() - 1).toMillis() == Long.MAX_VALUE) {
            Gap open = gaps.remove(gaps.size() - 1);
            gaps.add(new Gap(open.fromMillis(), atMillis, open.silent()));
        }
    }

    public synchronized List<Arrival> arrivals() {
        return List.copyOf(arrivals);
    }

    public synchronized List<Gap> gaps() {
        return List.copyOf(gaps);
    }
}
