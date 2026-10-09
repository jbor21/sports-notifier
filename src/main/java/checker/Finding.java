package checker;

/**
 * One thing the checker found wrong. Kinds: {@code history_missing}, {@code history_unexpected},
 * {@code history_wrong_body}, {@code socket_missing}, {@code socket_duplicate},
 * {@code socket_out_of_order}, {@code socket_unexpected}, {@code follow_changed}, {@code setup_failed},
 * {@code timeout}.
 */
public record Finding(String kind, String userId, String gameId, String messageId, String detail) {
}
