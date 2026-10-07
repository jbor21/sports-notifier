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

    /** A game's accept counter, kept apart from the game record so it outlives it: {@code version:<gameId>} */
    public static final String VERSION_PREFIX = "version:";

    /** Games with a play in the outbox, scored by accept time in millis; the retry loop reads it. */
    public static final String OUTBOX_PENDING = "outbox:pending";

    /** The last play version Stage 1 finished for a game: {@code s1:last:<gameId>} */
    public static final String STAGE1_LAST_PREFIX = "s1:last:";

    /** Stage 2's dedup claim, one per message per user: {@code notif:<messageId>:<userId>} */
    public static final String NOTIF_DEDUP_PREFIX = "notif:";

    /** Gateways holding a user's sockets (sorted set: gateway id, scored by expiry in seconds): {@code conn:<userId>} */
    public static final String CONN_PREFIX = "conn:";

    /** A gateway's own pub/sub channel; Stage 2 publishes there for the users it holds: {@code gw:<gatewayId>} */
    public static final String GATEWAY_CHANNEL_PREFIX = "gw:";

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

    /** Users following a team, one set per alert type: {@code sub:team:<teamId>:<ALERT>} (3.1: {@code sub:team:<team>}) */
    public static final String SUB_TEAM_PREFIX = "sub:team:";

    /** Users following a game, one set per alert type: {@code sub:game:<gameId>:<ALERT>} */
    public static final String SUB_GAME_PREFIX = "sub:game:";

    /** Users following a league, one set per alert type: {@code sub:league:<league>:<ALERT>} */
    public static final String SUB_LEAGUE_PREFIX = "sub:league:";

    /** Inverted index — users who enabled a notification type: {@code sub:notif:<type>} */
    public static final String SUB_NOTIF_PREFIX = "sub:notif:";

    /**
     * Set when the sub:* sets were last rebuilt from Postgres. Missing means they may be empty or
     * stale: user-pref rebuilds them, and Stage 1 waits until it is back (Phase 3.2 spec, 5.4 and 6).
     */
    public static final String SUB_BUILT = "sub:built";

    /** Where a rebuild writes each set before renaming it into place: {@code sub:tmp:<buildId>:<key>} */
    public static final String SUB_TMP_PREFIX = "sub:tmp:";

    /** Temporary audience set built during one event's fan-out: {@code fanout:<eventId>} */
    public static final String FANOUT_PREFIX = "fanout:";

    /** Per-user notification history list: {@code notifications:<userId>} */
    public static final String NOTIFICATIONS_PREFIX = "notifications:";

}
