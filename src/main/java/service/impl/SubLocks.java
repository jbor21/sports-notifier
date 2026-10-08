package service.impl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * The Postgres advisory locks that keep the sub:* sets in step with {@code follows} (spec 5.3, 5.4).
 * Session-level locks: they outlive the save's transaction, so they still cover the Redis update
 * that follows the commit. If the pod dies, its connection closes and Postgres releases them.
 */
public final class SubLocks {

    /** Saves hold it shared, a rebuild holds it exclusive. Bigint form. */
    static final long SUB_LOCK = 3_200_001L;
    /** Namespace for per-user locks, in the two-int form, which never collides with the bigint form. */
    static final int USER_LOCK_NS = 32;

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws Exception;
    }

    private SubLocks() {}

    /**
     * Runs a follow save for one user. Takes the rebuild lock shared, then the user's lock, always in
     * that order, so two saves cannot deadlock. Two users whose ids hash alike just wait for each other.
     */
    public static <T> T withSaveLocks(Connection c, String userId, SqlWork<T> work) throws Exception {
        try {
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_lock_shared(?)")) {
                ps.setLong(1, SUB_LOCK);
                ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_lock(?, hashtext(?))")) {
                ps.setInt(1, USER_LOCK_NS);
                ps.setString(2, userId);
                ps.execute();
            }
            return work.run(c);
        } finally {
            unlockAll(c);
        }
    }

    /** Runs a rebuild: waits for saves in flight to finish and holds off new ones until it is done. */
    public static boolean withRebuildLock(Connection c, SqlWork<Boolean> work) throws Exception {
        try {
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_lock(?)")) {
                ps.setLong(1, SUB_LOCK);
                ps.execute();
            }
            return work.run(c);
        } finally {
            unlockAll(c);
        }
    }

    /** The connection goes back to the pool afterwards; it must not carry a lock with it. */
    private static void unlockAll(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT pg_advisory_unlock_all()");
        }
    }
}
