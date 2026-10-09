package checker;

import com.google.gson.JsonObject;

import java.time.Instant;

/** One row of Postgres history: {@code createdAt} is the {@code notifications} record's Kafka timestamp. */
public record HistoryRow(String userId, String messageId, String gameId, Instant createdAt, JsonObject body) {
}
