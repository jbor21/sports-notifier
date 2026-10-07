package service.impl;

import catalog.AlertSetting;
import catalog.FollowKind;

import java.time.Instant;
import java.util.Map;

/** One row of {@code follows}: a user following a league, team, or game, with its alert switches. */
public record Follow(String id, String userId, FollowKind kind, String targetId, String league,
                     Map<String, AlertSetting> alerts, Instant expiresAt, Instant createdAt) {
}
