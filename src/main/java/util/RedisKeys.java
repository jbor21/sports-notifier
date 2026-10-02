package util;

/**
 * Named constants for every Redis key prefix and channel used across services.
 * Centralising these prevents typos and makes key-space changes a single-line edit.
 */
public final class RedisKeys {

    private RedisKeys() {}

    /** Hash storing live game state: {@code game:<gameId>} */
    public static final String GAME_PREFIX = "game:";

    /** SET NX dedup guard per event: {@code processed:<eventId>} */
    public static final String PROCESSED_PREFIX = "processed:";

    /** User preferences JSON blob: {@code prefs:<userId>} */
    public static final String PREFS_PREFIX = "prefs:";

    /** Session token → userId mapping: {@code sid:<token>} */
    public static final String SESSION_PREFIX = "sid:";

    /** Inverted index — users subscribed to a team: {@code sub:team:<team>} */
    public static final String SUB_TEAM_PREFIX = "sub:team:";

    /** Inverted index — users subscribed to a specific game: {@code sub:game:<gameId>} */
    public static final String SUB_GAME_PREFIX = "sub:game:";

    /** Inverted index — users subscribed to a league: {@code sub:league:<league>} */
    public static final String SUB_LEAGUE_PREFIX = "sub:league:";

    /** Inverted index — users who enabled a notification type: {@code sub:notif:<type>} */
    public static final String SUB_NOTIF_PREFIX = "sub:notif:";

    /** Temporary audience set built during one event's fan-out: {@code fanout:<eventId>} */
    public static final String FANOUT_PREFIX = "fanout:";

    /** Per-user notification history list: {@code notifications:<userId>} */
    public static final String NOTIFICATIONS_PREFIX = "notifications:";

    /** Per-user Redis Pub/Sub channel for real-time push: {@code notify:<userId>} */
    public static final String NOTIFY_CHANNEL_PREFIX = "notify:";

    /** Redis Stream key for game events shared by all PubSub Workers. */
    public static final String GAME_EVENTS_STREAM = "game-events";

    /** Redis Streams consumer group name for PubSub Workers. */
    public static final String WORKER_GROUP = "workers";
}
