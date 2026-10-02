package service.impl;

import model.GameState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import static org.junit.jupiter.api.Assertions.*;

class GameStateServiceImplTest {

    private JedisPool pool;
    private Jedis jedis;
    private GameStateServiceImpl service;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        service = new GameStateServiceImpl(pool);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    @Test
    void getGameState_returnsNullIfGameNotSeen() {
        assertNull(service.getGameState("unknown-game"));
    }
}
