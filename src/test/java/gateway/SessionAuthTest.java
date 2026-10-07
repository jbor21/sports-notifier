package gateway;

import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionAuthTest {

    @Test
    void cookieIsLaxAndHttpOnly() {
        Cookie c = SessionAuth.sessionCookie("abc", 3600, true);
        assertEquals("sid", c.getName());
        assertEquals("abc", c.getValue());
        assertEquals(SameSite.LAX, c.getSameSite());
        assertTrue(c.isHttpOnly());
        assertEquals("/", c.getPath());
        assertEquals(3600, c.getMaxAge());
    }

    @Test
    void secureFollowsSetting() {
        assertTrue(SessionAuth.sessionCookie("abc", 3600, true).getSecure());
        assertFalse(SessionAuth.sessionCookie("abc", 3600, false).getSecure());
        assertEquals(SameSite.LAX, SessionAuth.sessionCookie("", 0, false).getSameSite());
    }
}
