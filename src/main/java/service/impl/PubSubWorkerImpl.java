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
    private final Audience audience;
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
        this.audience = new Audience(pool);
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

    /** The audience rules live in {@link Audience}, shared with Stage 1. */
    @Override
    public void fanOutNotifications(GameEvent event) {
        if (event.getEventType() == null) return;
        Notification notification = new Notification(
            null,
            event.getGameId(),
            event.getDescription(),
            event.getTimestamp(),
            event.getLeague(),
            event.getHomeTeam(),
            event.getAwayTeam()
        );
        audience.forEachPage(event, page -> notificationService.sendNotification(page, notification));
    }
}
