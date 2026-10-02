package service;

/** Outcome of processing one game event. */
public enum ProcessResult {
    /** New and newer than the saved state: state written (and published unless GAME_SYNC). */
    ACCEPTED,
    /** This event id was already accepted; nothing changed. */
    DUPLICATE,
    /** Not later in the game than the saved state; nothing changed and the id stays unclaimed. */
    STALE,
    /** Missing required fields or unparseable; nothing changed. */
    INVALID
}
