package util;

/** Production topic and consumer group names. Kafka classes take these as constructor arguments. */
public final class Topics {

    private Topics() {}

    /** Accepted plays, keyed by game id (12 partitions). */
    public static final String GAME_EVENTS = "game-events";
    /** One message per user per play, keyed by user id (48 partitions). */
    public static final String NOTIFICATIONS = "notifications";

    public static final String STAGE1_GROUP = "stage1";
    public static final String STAGE2_GROUP = "stage2";
}
