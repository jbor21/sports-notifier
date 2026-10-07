package util;

/**
 * Named constants for every Redis key prefix and channel used across services.
 * Centralising these prevents typos and makes key-space changes a single-line edit.
 */
public final class RedisKeys {

    private RedisKeys() {}

    /** Hash storing live game state: {@code game:<gameId>} */
    public static final String GAME_PREFIX = "game:";

    /** Sorted set of recently updated game ids, scored by Redis time in seconds: {@code games:recent} */
    public static final String GAMES_RECENT = "games:recent";

    /** SET NX dedup guard per event: {@code processed:<eventId>} */
    public static final String PROCESSED_PREFIX = "processed:";

    /** One game's accepted play waiting for the log to confirm it (hash: version, play): {@code outbox:<gameId>} */
    public static final String OUTBOX_PREFIX = "outbox:";

    /** Games with a play in the outbox, scored by accept time in millis; the retry loop reads it. */
    public static final String OUTBOX_PENDING = "outbox:pending";

    /** The last play version Stage 1 finished for a game: {@code s1:last:<gameId>} */
    public static final String STAGE1_LAST_PREFIX = "s1:last:";

    /** Account record JSON (id, username, bcrypt hash): {@code user:<userId>} */
    public static final String USER_PREFIX = "user:";

    /** Username → userId, claimed with SET NX at signup: {@code username:<name>} */
    public static final String USERNAME_PREFIX = "username:";

    /** Failed-login counters: {@code login:fail:user:<name>} and {@code login:fail:ip:<address>} */
    public static final String LOGIN_FAIL_PREFIX = "login:fail:";

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
