package gateway;

/**
 * The client's address as our own proxy (Traefik) saw it.
 *
 * Never the first X-Forwarded-For entry: the client can send that header itself, so the first
 * entry is whatever it wants. Each proxy appends the address it received the request from, so
 * the last entry is the one Traefik added, and Traefik also puts it in X-Real-Ip. Trusting the
 * first entry would let an attacker invent a new address on every login attempt and never hit
 * the per-address limit.
 */
public final class ClientAddress {

    private ClientAddress() {}

    public static String of(String xRealIp, String xForwardedFor, String remoteAddr) {
        if (xRealIp != null && !xRealIp.isBlank()) return xRealIp.trim();
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String[] hops = xForwardedFor.split(",");
            String last = hops[hops.length - 1].trim();
            if (!last.isEmpty()) return last;
        }
        return remoteAddr;
    }
}
