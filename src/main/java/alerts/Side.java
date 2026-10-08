package alerts;

/** Home or away. */
public enum Side {
    HOME, AWAY;

    /** {@code home} or {@code away}, as it appears in alert ids. */
    public String key() {
        return this == HOME ? "home" : "away";
    }

    public Side other() {
        return this == HOME ? AWAY : HOME;
    }
}
