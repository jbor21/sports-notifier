package service.impl;

import alerts.Alert;
import alerts.Detectors;
import alerts.GameLabels;
import alerts.GameMemory;
import alerts.Play;
import alerts.PlayBuilder;
import alerts.Side;
import catalog.Teams;
import com.google.gson.JsonObject;
import model.NotificationMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** One push per user per moment, headline and extras, the final last (Phase 3.3 spec, 5.2–5.5). */
class MomentFanoutTest {

    private static final Play PLAY = PlayBuilder.nfl("nfl-1").period(4).clock(2520).score(17, 20).version(57).build();
    private static final Alert TD = Alert.of("nfl-1:SCORE:away:2", "TOUCHDOWN", Alert.P_SCORE, Side.AWAY,
        "Touchdown Raiders! Extra point pending. Raiders 20, Bears 17 · Q4 4:12");
    private static final Alert LEAD = Alert.of("nfl-1:LEAD_CHANGE:1", "LEAD_CHANGE", Alert.P_LEAD, Side.AWAY,
        "Raiders take the lead. Raiders 20, Bears 17 · Q4 4:12");
    private static final Alert FINAL = Alert.of("nfl-1:FINAL", "FINAL", Alert.P_FLOW, null, "Final: Raiders 20, Bears 17. Raiders win.");

    private JedisPool pool;
    private Jedis jedis;
    private MomentFanout fanout;
    private final List<Map.Entry<String, NotificationMessage>> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        fanout = new MomentFanout(pool, new AlertAudience(), new GameLabels(Teams.load()));
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    private void followBears(String user, String... switches) {
        jedis.sadd("sub:team:nfl-chi:ANY", user);
        for (String s : switches) jedis.sadd("sub:team:nfl-chi:" + s, user);
    }

    private void send(List<Alert> moment, Alert fin) {
        fanout.send(PLAY, new Detectors.Result(moment, fin, new GameMemory()), (u, m) -> sent.add(Map.entry(u, m)));
    }

    private static List<String> extras(NotificationMessage m) {
        List<String> out = new ArrayList<>();
        m.getNotification().getAsJsonArray("extras").forEach(e -> out.add(e.getAsJsonObject().get("type").getAsString()));
        return out;
    }

    @Test
    void touchdownAndLeadChangeIsOnePush() {
        followBears("u1", "TOUCHDOWN", "LEAD_CHANGE");
        send(List.of(TD, LEAD), null);
        assertEquals(1, sent.size());
        NotificationMessage m = sent.get(0).getValue();
        assertEquals("u1", m.getUserId());
        assertEquals("nfl-1:SCORE:away:2", m.getMessageId());
        assertEquals("TOUCHDOWN", m.getEventType());
        assertEquals(TD.text(), m.getMessage());
        JsonObject headline = m.getNotification().getAsJsonObject("headline");
        assertEquals("TOUCHDOWN", headline.get("type").getAsString());
        assertEquals(TD.text(), headline.get("text").getAsString());
        assertEquals(List.of("LEAD_CHANGE"), extras(m));
        assertEquals(LEAD.text(), m.getNotification().getAsJsonArray("extras").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void userWithOnlyLeadChangeGetsItAsHeadline() {
        followBears("u2", "LEAD_CHANGE");
        send(List.of(TD, LEAD), null);
        NotificationMessage m = sent.get(0).getValue();
        assertEquals("nfl-1:LEAD_CHANGE:1", m.getMessageId());
        assertEquals("LEAD_CHANGE", m.getEventType());
        assertEquals(List.of(), extras(m));
    }

    @Test
    void finalComesAfterMomentForEveryUser() {
        for (int i = 0; i < 5; i++) followBears("u" + i, "TOUCHDOWN", "FINAL");
        send(List.of(TD), FINAL);
        assertEquals(10, sent.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("TOUCHDOWN", sent.get(i).getValue().getEventType(), "every moment message first");
            assertEquals("FINAL", sent.get(5 + i).getValue().getEventType());
        }
        for (int i = 0; i < 5; i++) {
            String u = "u" + i;
            int moment = -1, fin = -1;
            for (int k = 0; k < sent.size(); k++) {
                if (!sent.get(k).getKey().equals(u)) continue;
                if (sent.get(k).getValue().getEventType().equals("FINAL")) fin = k; else moment = k;
            }
            assertTrue(moment >= 0 && fin > moment, u + ": the final after the moment");
        }
        NotificationMessage f = sent.get(5).getValue();
        assertEquals("nfl-1:FINAL", f.getMessageId());
        assertEquals(List.of(), extras(f));
    }

    @Test
    void finalAloneWhenNoMomentAudience() {
        followBears("u1", "FINAL");
        send(List.of(TD), FINAL);
        assertEquals(1, sent.size());
        assertEquals("nfl-1:FINAL", sent.get(0).getValue().getMessageId());
    }

    @Test
    void bodyHasPeriodLabelAndClock() {
        followBears("u1", "TOUCHDOWN");
        send(List.of(TD), null);
        JsonObject n = sent.get(0).getValue().getNotification();
        assertEquals("nfl-1:SCORE:away:2", n.get("id").getAsString());
        assertEquals("game_moment", n.get("kind").getAsString());
        assertEquals("NFL", n.get("leagueId").getAsString());
        JsonObject game = n.getAsJsonObject("game");
        assertEquals("nfl-1", game.get("id").getAsString());
        assertEquals("Q4", game.get("periodLabel").getAsString());
        assertEquals("4:12", game.get("clock").getAsString());
        assertEquals("nfl-chi", game.getAsJsonObject("home").getAsJsonObject("team").get("id").getAsString());
        assertEquals("Chicago Bears", game.getAsJsonObject("home").getAsJsonObject("team").get("displayName").getAsString());
        assertEquals(17, game.getAsJsonObject("home").get("score").getAsInt());
        assertEquals(20, game.getAsJsonObject("away").get("score").getAsInt());
        assertNotNull(n.get("detectedAt"));
        NotificationMessage m = sent.get(0).getValue();
        assertEquals("nfl-1", m.getGameId());
        assertEquals("NFL", m.getLeague());
        assertEquals(17, m.getHomeScore());
    }

    @Test
    void nobodyFollowsNoMessages() {
        send(List.of(TD, LEAD), FINAL);
        assertEquals(List.of(), sent);
    }

    @Test
    void manyUsersOneMessageEachAcrossPages() {
        String[] users = new String[2500];
        for (int i = 0; i < users.length; i++) users[i] = "u" + i;
        jedis.sadd("sub:team:nfl-chi:ANY", users);
        jedis.sadd("sub:team:nfl-chi:TOUCHDOWN", users);
        jedis.sadd("sub:team:nfl-chi:LEAD_CHANGE", java.util.Arrays.copyOf(users, 1200));
        send(List.of(TD, LEAD), null);
        assertEquals(2500, sent.size());
        Set<String> seen = new HashSet<>();
        int withExtra = 0;
        for (var e : sent) {
            assertTrue(seen.add(e.getKey()), "one message per user");
            if (!extras(e.getValue()).isEmpty()) withExtra++;
        }
        assertEquals(1200, withExtra);
    }

    /** 2,500 Bears followers of a touchdown and lead change: the union path, three pages of about 1,000. */
    private void bigAudience() {
        String[] users = new String[2500];
        for (int i = 0; i < users.length; i++) users[i] = "u" + i;
        jedis.sadd("sub:team:nfl-chi:ANY", users);
        jedis.sadd("sub:team:nfl-chi:TOUCHDOWN", users);
        jedis.sadd("sub:team:nfl-chi:LEAD_CHANGE", users);
    }

    /** Issue #17: the whole fan-out outlasts the sets' TTL, but no single page does. */
    @Test
    void slowPagesStillReachEveryone() {
        bigAudience();
        MomentFanout slow = new MomentFanout(pool, new AlertAudience(), new GameLabels(Teams.load()), 1);
        int[] n = {0};
        slow.send(PLAY, new Detectors.Result(List.of(TD, LEAD), null, new GameMemory()), (u, m) -> {
            if (n[0]++ % 400 == 0) sleep(200);
            sent.add(Map.entry(u, m));
        });
        assertEquals(2500, sent.size());
        assertEquals(2500, sent.stream().map(Map.Entry::getKey).distinct().count());
    }

    /** Issue #17: a set gone between pages is an error Stage 1 retries, never a silent stop. */
    @Test
    void pageSlowerThanTtlFailsInsteadOfSkipping() {
        bigAudience();
        MomentFanout slow = new MomentFanout(pool, new AlertAudience(), new GameLabels(Teams.load()), 1);
        int[] n = {0};
        assertThrows(JedisException.class, () ->
            slow.send(PLAY, new Detectors.Result(List.of(TD, LEAD), null, new GameMemory()), (u, m) -> {
                if (n[0]++ == 0) sleep(1500);
            }));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void noTemporaryKeysLeft() {
        followBears("u1", "TOUCHDOWN", "FINAL");
        send(List.of(TD, LEAD), FINAL);
        assertEquals(Set.of(), jedis.keys("fanout:*"));
    }
}
