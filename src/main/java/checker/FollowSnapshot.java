package checker;

import service.impl.Follow;

import java.time.Instant;

/** One row of {@code follows} as the checker snapshots it: the follow and when its switches last changed. */
public record FollowSnapshot(Follow follow, Instant updatedAt) {
}
