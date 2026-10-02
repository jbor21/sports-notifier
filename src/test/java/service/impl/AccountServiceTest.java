package service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AccountServiceTest {

    private JedisPool pool;
    private Jedis jedis;
    private AccountService accounts;

    @BeforeEach
    void setUp() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(32);
        pool = new JedisPool(cfg, "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        accounts = new AccountService(pool);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    /** Runs every task at the same instant and returns their status codes. */
    private List<Integer> race(List<Callable<AccountService.Result>> tasks) throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<AccountService.Result>> futures = new ArrayList<>();
            for (Callable<AccountService.Result> t : tasks) {
                futures.add(threads.submit(() -> { gate.await(); return t.call(); }));
            }
            gate.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<AccountService.Result> f : futures) codes.add(f.get(10, TimeUnit.SECONDS).status());
            return codes;
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void twentyConcurrentSignupsOneWinner() throws Exception {
        List<Callable<AccountService.Result>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) tasks.add(() -> accounts.signup("bob", "password-123"));
        List<Integer> codes = race(tasks);
        assertEquals(1, codes.stream().filter(c -> c == 200).count());
        assertEquals(19, codes.stream().filter(c -> c == 409).count());
        assertEquals(1, jedis.keys("username:*").size());
        assertEquals(1, jedis.keys("user:*").size());
    }

    @Test
    void caseVariantsAreTheSameName() throws Exception {
        List<Integer> codes = race(List.of(
            () -> accounts.signup("Bob", "password-123"),
            () -> accounts.signup("bob", "password-456")));
        assertEquals(1, codes.stream().filter(c -> c == 200).count());
        assertEquals(1, codes.stream().filter(c -> c == 409).count());
    }

    @Test
    void passwordUnderEightRejected() {
        AccountService.Result r = accounts.signup("bob", "1234567");
        assertEquals(400, r.status());
        assertTrue(r.error().contains("8"), r.error());
        assertFalse(jedis.exists("username:bob"));
    }

    @Test
    void passwordOverSeventyTwoBytesRejected() {
        assertEquals(400, accounts.signup("bob", "a".repeat(73)).status());
        // 19 four-byte emoji: only 38 Java chars, but 76 bytes, past what bcrypt reads.
        assertEquals(400, accounts.signup("bob", "😀".repeat(19)).status());
    }

    @Test
    void seventyTwoBytesAccepted() {
        String password = "a".repeat(72);
        assertEquals(200, accounts.signup("bob", password).status());
        assertEquals(200, accounts.login("bob", password).status());
    }

    @Test
    void blankUsernameRejected() {
        assertEquals(400, accounts.signup("   ", "password-123").status());
        assertEquals(400, accounts.signup(null, "password-123").status());
        assertEquals(400, accounts.signup("bob", null).status());
    }

    @Test
    void wrongPasswordIs401() {
        accounts.signup("bob", "password-123");
        assertEquals(401, accounts.login("bob", "password-999").status());
        assertEquals(401, accounts.login("nobody", "password-123").status());
        assertEquals(401, accounts.login(null, null).status());
    }

    @Test
    void loginReturnsTheSameUserId() {
        String id = accounts.signup("Bob", "password-123").userId();
        AccountService.Result login = accounts.login(" BOB ", "password-123");
        assertEquals(200, login.status());
        assertEquals(id, login.userId());
        assertEquals("bob", login.username());
    }
}
