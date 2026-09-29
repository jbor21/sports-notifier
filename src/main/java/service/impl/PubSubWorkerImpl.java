package service.impl;

import model.GameEvent;
import model.Notification;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XReadGroupParams;
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

    private void scanAndAddSubscribers(Jedis jedis, String key, Set<String> subscribers) {
        String cursor = "0";
        redis.clients.jedis.params.ScanParams params = new redis.clients.jedis.params.ScanParams().count(100);
        do {
            redis.clients.jedis.resps.ScanResult<String> result = jedis.sscan(key, cursor, params);
            subscribers.addAll(result.getResult());
            cursor = result.getCursor();
        } while (!cursor.equals("0"));
    }

    @Override
    public void fanOutNotifications(GameEvent event) {
        Set<String> subscribers = new HashSet<>();

        try (Jedis jedis = pool.getResource()) {
            if (event.getTeam() != null) {
                scanAndAddSubscribers(jedis, RedisKeys.SUB_TEAM_PREFIX + event.getTeam(), subscribers);
            }
            if (event.getGameId() != null) {
                scanAndAddSubscribers(jedis, RedisKeys.SUB_GAME_PREFIX + event.getGameId(), subscribers);
            }
            if (event.getLeague() != null) {
                scanAndAddSubscribers(jedis, RedisKeys.SUB_LEAGUE_PREFIX + event.getLeague(), subscribers);
            }

            if (subscribers.isEmpty()) return;

            List<String> matchingUsers = new ArrayList<>();
            if (event.getEventType() != null) {
                for (String userId : subscribers) {
                    if (jedis.sismember(RedisKeys.SUB_NOTIF_PREFIX + event.getEventType(), userId)) {
                        matchingUsers.add(userId);
                    }
                }
            }

            if (matchingUsers.isEmpty()) return;

            Notification notification = new Notification(
                null,
                event.getGameId(),
                event.getDescription(),
                event.getTimestamp(),
                event.getLeague(),
                event.getHomeTeam(),
                event.getAwayTeam()
            );

            notificationService.sendNotification(matchingUsers, notification);
        }
    }
}
