package gateway;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.GameSummaries;
import model.GameEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import util.GameEventParser;
import util.RedisKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Which games this gateway's tabs show, and the {@code live:<gameId>} channels that follow from it
 * (Phase 3.4 spec, 7.2 and 7.3). The first tab that asks for a game makes the gateway subscribe; the
 * last one to leave makes it unsubscribe. Updates are per game, not per user: a game with 100,000
 * viewers is one message per gateway.
 */
public final class GameSubscriptions {

    private final JedisPool pool;
    private final GatewayChannel channel;
    private final GameSummaries summaries;
    private final long confirmTimeoutMillis;
    /** Tabs per game. Changed only under {@code lock}, together with the subscribe and unsubscribe decisions. */
    private final Map<String, Set<SocketSession>> byGame = new HashMap<>();
    private final Object lock = new Object();
    /** Runs between the confirmed subscription and the read of current state; tests use it to land a play there. */
    volatile Runnable afterConfirmBeforeRead = () -> { };

    public GameSubscriptions(JedisPool pool, GatewayChannel channel, GameSummaries summaries, long confirmTimeoutMillis) {
        this.pool = pool;
        this.channel = channel;
        this.summaries = summaries;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
        channel.onGames(this::onLive, this::resendAll);
    }

    /**
     * Replaces the tab's list, then sends the tab the current state of every game it added.
     *
     * Order matters. The gateway first subscribes to a new game's channel and waits for Redis to
     * confirm; only then does it read game:<id>. Read first, and a play accepted between the read and
     * the subscription would be missed until the game's next play. Subscribe first, and that play
     * arrives twice at worst, once live and once in the read; the UI drops a version it already has.
     */
    public void set(SocketSession session, List<String> gameIds) {
        Set<String> wanted = new LinkedHashSet<>(gameIds);
        List<String> added = new ArrayList<>();
        List<CompletableFuture<Void>> confirms = new ArrayList<>();
        synchronized (lock) {
            for (String old : new ArrayList<>(session.games())) {
                if (!wanted.contains(old)) leave(session, old);
            }
            for (String id : wanted) {
                if (session.games().contains(id)) continue;
                byGame.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(session);
                session.games().add(id);
                // Idempotent: a channel already confirmed (another tab) completes at once.
                confirms.add(channel.subscribe(RedisKeys.LIVE_CHANNEL_PREFIX + id));
                added.add(id);
            }
        }
        if (added.isEmpty()) return;
        try {
            CompletableFuture.allOf(confirms.toArray(new CompletableFuture[0])).get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.err.println("[GameSubscriptions] Redis did not confirm " + added + " within " + confirmTimeoutMillis + " ms; reading anyway");
        } catch (Exception e) {
            System.err.println("[GameSubscriptions] waiting for " + added + ": " + e);
        }
        afterConfirmBeforeRead.run();
        // The tab may have closed or changed its list while we waited.
        added.removeIf(id -> !session.games().contains(id));
        sendCurrent(added, List.of(session));
    }

    /** The tab closed. */
    public void removeAll(SocketSession session) {
        synchronized (lock) {
            for (String id : new ArrayList<>(session.games())) leave(session, id);
        }
    }

    /** Called with {@code lock} held. */
    private void leave(SocketSession session, String gameId) {
        session.games().remove(gameId);
        Set<SocketSession> tabs = byGame.get(gameId);
        if (tabs == null) return;
        tabs.remove(session);
        if (tabs.isEmpty()) {
            byGame.remove(gameId);
            channel.unsubscribe(RedisKeys.LIVE_CHANNEL_PREFIX + gameId);
        }
    }

    /** A message on live:<gameId>: {"version": n, "play": {...}} from the accept script. */
    void onLive(String gameId, String message) {
        String update;
        try {
            JsonObject m = JsonParser.parseString(message).getAsJsonObject();
            GameEvent play = GameEventParser.parse(m.get("play").toString());
            update = gameUpdate(summaries.fromLive(play, m.get("version").getAsLong()));
        } catch (RuntimeException e) {
            System.err.println("[GameSubscriptions] unreadable message on live:" + gameId + ": " + e);
            return;
        }
        for (SocketSession s : tabsOf(gameId)) send(s, update);
    }

    /** After a reconnect: plays may have gone by while the connection was down, so every tab gets current state. */
    void resendAll() {
        List<String> ids;
        synchronized (lock) {
            ids = new ArrayList<>(byGame.keySet());
        }
        Map<String, String> updates = currentState(ids);
        for (Map.Entry<String, String> u : updates.entrySet()) {
            for (SocketSession s : tabsOf(u.getKey())) send(s, u.getValue());
        }
    }

    private void sendCurrent(List<String> ids, List<SocketSession> to) {
        if (ids.isEmpty()) return;
        for (String update : currentState(ids).values()) {
            for (SocketSession s : to) send(s, update);
        }
    }

    /** {@code game_update} messages for the games that have a record; scheduled and unknown ids have none. */
    private Map<String, String> currentState(List<String> ids) {
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        try (Jedis jedis = pool.getResource()) {
            Pipeline p = jedis.pipelined();
            List<Response<Map<String, String>>> records = new ArrayList<>();
            for (String id : ids) records.add(p.hgetAll(RedisKeys.GAME_PREFIX + id));
            p.sync();
            for (int i = 0; i < ids.size(); i++) {
                Map<String, String> record = records.get(i).get();
                if (!record.isEmpty()) out.put(ids.get(i), gameUpdate(summaries.summary(ids.get(i), record, Map.of())));
            }
        } catch (RuntimeException e) {
            System.err.println("[GameSubscriptions] reading current state failed: " + e.getMessage());
        }
        return out;
    }

    private List<SocketSession> tabsOf(String gameId) {
        synchronized (lock) {
            Set<SocketSession> tabs = byGame.get(gameId);
            return tabs == null ? List.of() : new ArrayList<>(tabs);
        }
    }

    private static String gameUpdate(JsonElement game) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "game_update");
        m.add("game", game);
        return m.toString();
    }

    private static void send(SocketSession s, String text) {
        try {
            s.send(text);
        } catch (RuntimeException e) {
            System.err.println("[GameSubscriptions] send error: " + e.getMessage());
        }
    }

    /** Channels this gateway listens to for its tabs; for diagnostics. */
    Set<String> channels() {
        synchronized (lock) {
            Set<String> out = new LinkedHashSet<>();
            for (String id : byGame.keySet()) out.add(RedisKeys.LIVE_CHANNEL_PREFIX + id);
            return out;
        }
    }
}
