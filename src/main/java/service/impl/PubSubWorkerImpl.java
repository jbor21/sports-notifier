package service.impl;

import model.GameEvent;
import model.Notification;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.ScanResult;
import service.NotificationService;
import service.PubSubWorker;
import service.UserPreferenceService;
import util.GameEventParser;
import util.RedisKeys;

import java.util.*;

public class PubSubWorkerImpl implements PubSubWorker {

    private final UserPreferenceService userPreferenceService;
    private final NotificationService notificationService;
    private final JedisPool pool;
    private final Jedis blockingClient;
    private final String consumerName;
    private static final String STREAM_KEY = RedisKeys.GAME_EVENTS_STREAM;
    private static final String GROUP_NAME = RedisKeys.WORKER_GROUP;
    private static final int FANOUT_PAGE_SIZE = 1000;
    private static final long FANOUT_KEY_TTL_SECONDS = 60L;
    private volatile boolean running = true;

    public PubSubWorkerImpl(UserPreferenceService userPreferenceService,
                            NotificationService notificationService,
                            JedisPool pool,
                            Jedis blockingClient,
                            String consumerName) {
        this.userPreferenceService = userPreferenceService;
        this.notificationService = notificationService;
        this.pool = pool;
        this.blockingClient = blockingClient;
        this.consumerName = consumerName;
    }

    public void setup() {
        try (Jedis jedis = pool.getResource()) {
            jedis.xgroupCreate(STREAM_KEY, GROUP_NAME, StreamEntryID.LAST_ENTRY, true);
        } catch (JedisDataException e) {
            // BUSYGROUP: consumer group already exists — safe to ignore
        }
    }

    public void startConsuming() {
        while (running) {
            try {
                List<Map.Entry<String, List<StreamEntry>>> results = blockingClient.xreadGroup(
                    GROUP_NAME,
                    consumerName,
                    XReadGroupParams.xReadGroupParams().count(10).block(1000),
                    Map.of(STREAM_KEY, StreamEntryID.UNRECEIVED_ENTRY)
                );

                if (results == null || results.isEmpty()) continue;

                for (Map.Entry<String, List<StreamEntry>> streamResult : results) {
                    for (StreamEntry entry : streamResult.getValue()) {
                        String json = entry.getFields().get("data");
                        try {
                            GameEvent event = GameEventParser.parse(json);
                            handleEvent(event);
                            blockingClient.xack(STREAM_KEY, GROUP_NAME, entry.getID());
                        } catch (Exception ex) {
                            System.err.println("[PubSubWorker " + consumerName + "] error handling entry " + entry.getID() + ": " + ex.getMessage());
                        }
                    }
                }
            } catch (Exception loopEx) {
                System.err.println("[PubSubWorker " + consumerName + "] loop error: " + loopEx.getMessage());
                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    public void stop() {
        running = false;
    }

    @Override
    public void handleEvent(GameEvent event) {
        fanOutNotifications(event);
    }

    /**
     * Builds the audience inside Redis instead of in Java: the union of everyone following either
     * team, the game, or the league, intersected with everyone who enabled this event type.
     * That is a handful of Redis calls per event, not one SISMEMBER round trip per subscriber, and
     * the union de-duplicates a user who follows both teams so they get one notification.
     * Both teams, not just the tagged one: following a team means following its whole game.
     */
    @Override
    public void fanOutNotifications(GameEvent event) {
        if (event.getEventType() == null) return;

        List<String> sources = new ArrayList<>();
        for (String team : new LinkedHashSet<>(Arrays.asList(
                event.getHomeTeam(), event.getAwayTeam(), event.getTeam()))) {
            if (team != null) sources.add(RedisKeys.SUB_TEAM_PREFIX + team);
        }
        if (event.getGameId() != null) sources.add(RedisKeys.SUB_GAME_PREFIX + event.getGameId());
        if (event.getLeague() != null) sources.add(RedisKeys.SUB_LEAGUE_PREFIX + event.getLeague());
        if (sources.isEmpty()) return;

        String audience = RedisKeys.FANOUT_PREFIX + event.getEventId();
        Notification notification = new Notification(
            null,
            event.getGameId(),
            event.getDescription(),
            event.getTimestamp(),
            event.getLeague(),
            event.getHomeTeam(),
            event.getAwayTeam()
        );

        try (Jedis jedis = pool.getResource()) {
            try {
                Pipeline p = jedis.pipelined();
                p.sunionstore(audience, sources.toArray(new String[0]));
                p.sinterstore(audience, audience, RedisKeys.SUB_NOTIF_PREFIX + event.getEventType());
                // Safety net: if this worker dies mid-fan-out, the temporary set still goes away.
                p.expire(audience, FANOUT_KEY_TTL_SECONDS);
                p.sync();

                // Page through the result so a huge audience never sits in memory all at once.
                ScanParams params = new ScanParams().count(FANOUT_PAGE_SIZE);
                String cursor = ScanParams.SCAN_POINTER_START;
                do {
                    ScanResult<String> page = jedis.sscan(audience, cursor, params);
                    if (!page.getResult().isEmpty()) {
                        notificationService.sendNotification(page.getResult(), notification);
                    }
                    cursor = page.getCursor();
                } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
            } finally {
                jedis.del(audience);
            }
        }
    }
}
