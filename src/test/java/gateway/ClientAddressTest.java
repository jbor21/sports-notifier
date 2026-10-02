package gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientAddressTest {

    @Test
    void prefersRealIp() {
        assertEquals("9.9.9.9", ClientAddress.of("9.9.9.9", "1.1.1.1, 9.9.9.9", "10.0.0.5"));
    }

    @Test
    void elseLastForwardedEntry() {
        assertEquals("9.9.9.9", ClientAddress.of(null, "6.6.6.6, 9.9.9.9", "10.0.0.5"));
    }

    @Test
    void elseSocketAddress() {
        assertEquals("10.0.0.5", ClientAddress.of(null, null, "10.0.0.5"));
        assertEquals("10.0.0.5", ClientAddress.of(" ", "  ", "10.0.0.5"));
    }

    @Test
    void neverTheFirstEntry() {
        // The client writes the first entry itself; only the last was added by our proxy.
        assertEquals("9.9.9.9", ClientAddress.of(null, "1.2.3.4,9.9.9.9", "10.0.0.5"));
        assertNotEquals("1.2.3.4", ClientAddress.of(null, "1.2.3.4, 5.6.7.8, 9.9.9.9", "10.0.0.5"));
    }
}
