package service.impl;

import io.javalin.Javalin;
import service.ProcessResult;
import redis.clients.jedis.JedisPool;
import util.GsonJsonMapper;
import util.JedisPoolHolder;
import util.KafkaSettings;
import util.Topics;
import org.apache.kafka.clients.producer.KafkaProducer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.util.Collections;

public final class EventProcessingServiceMain {

    private static final long RELAY_EVERY_MILLIS = 1_000L;
    private static final long RELAY_MIN_AGE_MILLIS = 5_000L;

    public static void main(String[] args) {
        JedisPoolHolder.awaitRedisOrExit("event-processing");
        KafkaSettings.awaitKafkaOrExit("event-processing");
        int port = Integer.parseInt(System.getenv().getOrDefault("EVENT_PROCESSING_PORT", "8083"));
        JedisPool pool = JedisPoolHolder.create();
        KafkaPlayPublisher kafka = new KafkaPlayPublisher(
            new KafkaProducer<>(KafkaSettings.ingestProducer(KafkaSettings.bootstrap())), Topics.GAME_EVENTS);
        EventProcessingServiceImpl eps = new EventProcessingServiceImpl(pool, kafka, System::currentTimeMillis);

        // The retry loop: plays still in the outbox after 5 s are sent again (a crashed pod, a Kafka outage).
        OutboxRelay relay = new OutboxRelay(new Outbox(pool), kafka, System::currentTimeMillis, RELAY_MIN_AGE_MILLIS);
        ScheduledExecutorService relayLoop = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "outbox-relay"));
        relayLoop.scheduleWithFixedDelay(() -> {
            try {
                relay.runOnce();
            } catch (RuntimeException e) {
                System.err.println("[EventProcessingService] retry loop: " + e.getMessage());
            }
        }, RELAY_EVERY_MILLIS, RELAY_EVERY_MILLIS, TimeUnit.MILLISECONDS);

        Javalin app = Javalin.create(cfg -> cfg.jsonMapper(new GsonJsonMapper())).start(port);

        app.get("/health", ctx -> ctx.json(Collections.singletonMap("status", "ok")));

        // Duplicate and stale are not errors: the sender did its job, there was just nothing new.
        // Only INVALID is a 400. A Redis failure throws, so Javalin answers 500 and the poller retries.
        app.post("/events", ctx -> {
            ProcessResult result = eps.processJson(ctx.body());
            ctx.status(result == ProcessResult.INVALID ? 400 : 200)
               .json(Collections.singletonMap("result", result.name().toLowerCase()));
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            relayLoop.shutdownNow();
            kafka.close();
            pool.close();
        }));

        System.out.println("[EventProcessingService] listening on :" + port);
    }
}
