package checker;

/** A synthetic user signed in: their id and the {@code sid} cookie the gateway set. */
public record Session(String userId, String sid) {
}
