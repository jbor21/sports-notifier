package gateway;

import games.GameSummaries;
import redis.clients.jedis.JedisPool;

import java.util.List;

/** Which games each tab shows. Live updates arrive in Phase 3.4 task 9; for now the lists are kept only. */
public final class GameSubscriptions {

    public GameSubscriptions(JedisPool pool, GatewayChannel channel, GameSummaries summaries, long confirmTimeoutMillis) {
    }

    /** Replaces the tab's list. */
    public void set(SocketSession session, List<String> gameIds) {
        session.games().clear();
        session.games().addAll(gameIds);
    }

    /** The tab closed. */
    public void removeAll(SocketSession session) {
        session.games().clear();
    }
}
