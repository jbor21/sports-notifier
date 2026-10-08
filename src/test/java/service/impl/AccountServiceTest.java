package service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testsupport.PostgresTestSupport;
import util.ApiResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Accounts in Postgres (Phase 3.2 spec, section 4). */
class AccountServiceTest {

    private AccountService accounts;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        accounts = new AccountService(PostgresTestSupport.dataSource());
    }

    @Test
    void signupReturns201WithUser() {
        ApiResult<AccountService.UserView> r = accounts.signup("jacob", "correct-horse-9", "America/New_York");
        assertEquals(201, r.status());
        assertEquals("jacob", r.body().username());
        assertEquals("America/New_York", r.body().timezone());
        assertNotNull(r.body().id());
        assertNotNull(r.body().createdAt());
    }

    @Test
    void twentySimultaneousSignupsOneWins() throws Exception {
        List<Callable<ApiResult<AccountService.UserView>>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) tasks.add(() -> accounts.signup("bob", "password-123", null));
        List<ApiResult<AccountService.UserView>> results = race(tasks);
        assertEquals(1, results.stream().filter(r -> r.status() == 201).count());
        assertEquals(19, results.stream().filter(r -> r.status() == 409 && "username_taken".equals(r.error().code())).count());
    }

    @Test
    void passwordTooShort() {
        ApiResult<?> r = accounts.signup("jacob", "short", null);
        assertEquals(422, r.status());
        assertEquals("validation_failed", r.error().code());
        assertEquals("too_short", r.error().fields().get("password"));
    }

    @Test
    void passwordLengthIsCountedIn72Bytes() {
        String twentyFourThreeByteChars = "€".repeat(24);           // 72 bytes in UTF-8
        assertEquals(201, accounts.signup("multibyte", twentyFourThreeByteChars, null).status());
        ApiResult<?> r = accounts.signup("toolong", twentyFourThreeByteChars + "a", null);   // 73 bytes
        assertEquals(422, r.status());
        assertEquals("too_long", r.error().fields().get("password"));
    }

    @Test
    void usernameRules() {
        for (String bad : List.of("ab", "a".repeat(21), "a-b", "has space", "")) {
            ApiResult<?> r = accounts.signup(bad, "password-123", null);
            assertEquals(422, r.status(), bad);
            assertTrue(r.error().fields().containsKey("username"), bad);
        }
        assertEquals(201, accounts.signup("a_1", "password-123", null).status());
    }

    @Test
    void mixedCaseUsernameIsLowerCased() {
        assertEquals("jacob", accounts.signup("Jacob", "password-123", null).body().username());
        ApiResult<AccountService.UserView> login = accounts.login("JACOB", "password-123");
        assertEquals(200, login.status());
        assertEquals("jacob", login.body().username());
        assertEquals(409, accounts.signup("jacob", "password-123", null).status());
    }

    @Test
    void timezoneRules() {
        assertEquals("UTC", accounts.signup("nozone", "password-123", null).body().timezone());
        for (String bad : List.of("EST", "+05:00", "", "Mars/Olympus")) {
            ApiResult<?> r = accounts.signup("tz" + Math.abs(bad.hashCode() % 1000), "password-123", bad);
            assertEquals(422, r.status(), bad);
            assertEquals("invalid", r.error().fields().get("timezone"), bad);
        }
        assertEquals(201, accounts.signup("chicago", "password-123", "America/Chicago").status());
    }

    @Test
    void wrongUserAndWrongPasswordLookAlike() {
        accounts.signup("jacob", "password-123", null);
        ApiResult<?> wrongUser = accounts.login("nobody", "password-123");
        ApiResult<?> wrongPassword = accounts.login("jacob", "password-999");
        assertEquals(401, wrongUser.status());
        assertEquals(wrongUser.status(), wrongPassword.status());
        assertEquals(wrongUser.error().toJson(), wrongPassword.error().toJson());
        assertEquals("invalid_credentials", wrongUser.error().code());
    }

    @Test
    void getAndUpdateTimezone() {
        String id = accounts.signup("jacob", "password-123", null).body().id();
        assertEquals("jacob", accounts.get(id).body().username());
        assertEquals(404, accounts.get("00000000-0000-0000-0000-000000000000").status());
        assertEquals(404, accounts.get("not-a-uuid").status());
        assertEquals("Europe/London", accounts.updateTimezone(id, "Europe/London").body().timezone());
        assertEquals(422, accounts.updateTimezone(id, "EST").status());
        assertEquals("Europe/London", accounts.get(id).body().timezone());
    }

    /** Runs every task at the same instant. */
    private static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) futures.add(threads.submit(() -> { gate.await(); return t.call(); }));
            gate.countDown();
            List<T> out = Collections.synchronizedList(new ArrayList<>());
            for (Future<T> f : futures) out.add(f.get(30, TimeUnit.SECONDS));
            return out;
        } finally {
            threads.shutdownNow();
        }
    }
}
