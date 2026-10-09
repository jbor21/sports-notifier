package checker;

import com.google.gson.JsonObject;

/**
 * One push the oracle says a user should get.
 *
 * @param messageId the push's id (its headline alert's id), Stage 2's and history's dedup key with the user
 * @param body      the API {@code Notification}, as history stores it
 * @param version   the play it came from
 */
public record ExpectedPush(String userId, String gameId, String messageId, JsonObject body, long version) {
}
