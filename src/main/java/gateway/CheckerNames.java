package gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@code make check-clean} deletes every user named like {@code ck_<run>_<n>} (#51), so only the checker
 * may create such names. It proves it by sending {@code X-Checker-Key}, a key the chart generates and
 * mounts into the gateway and the checker only. The header arrives from the internet like any other;
 * the key is what makes it safe. With no key configured, no one may use the prefix.
 */
public final class CheckerNames {

    private static final String PREFIX = "ck_";

    private CheckerNames() {}

    public static boolean allowed(String rawUsername, String presentedKey, String expectedKey) {
        String username = rawUsername == null ? "" : rawUsername.trim().toLowerCase();
        if (!username.startsWith(PREFIX)) return true;
        if (expectedKey == null || expectedKey.isEmpty() || presentedKey == null) return false;
        // Constant time, so response timing doesn't reveal how much of a guess was right.
        return MessageDigest.isEqual(presentedKey.getBytes(StandardCharsets.UTF_8), expectedKey.getBytes(StandardCharsets.UTF_8));
    }
}
