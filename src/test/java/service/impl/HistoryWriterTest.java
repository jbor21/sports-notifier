package service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariDataSource;
import model.GameEvent;
import model.NotificationBody;
import model.NotificationMessage;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testsupport.KafkaTestSupport;
import testsupport.PostgresTestSupport;
import util.Db;
import util.KafkaSettings;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The history writer: its own consumer group on notifications, batched inserts (spec 7.2). */
class HistoryWriterTest {

    private static final Gson GSON = new Gson();

    private String notifications;
    private KafkaProducer<String, String> producer;
    private HikariDataSource db;
    private final List<HistoryWriter> writers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        notifications = "notifications-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(notifications, 48);
        producer = new KafkaProducer<>(KafkaSettings.fanoutProducer(KafkaTestSupport.bootstrap()));
        // A short socket timeout, so a query against a frozen database fails instead of hanging.
        db = Db.create(PostgresTestSupport.jdbcUrl(), PostgresTestSupport.user(), PostgresTestSupport.password(), 2, 3);
    }

    @AfterEach
    void tearDown() {
        PostgresTestSupport.unpauseIfPaused();
        writers.forEach(HistoryWriter::close);
        producer.close();
        db.close();
    }

    private HistoryWriter writer() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                KafkaSettings.consumer(KafkaTestSupport.bootstrap(), "history-" + UUID.randomUUID()));
        consumer.subscribe(List.of(notifications));
        HistoryWriter w = new HistoryWriter(consumer, new HistoryStore(db));
        writers.add(w);
        return w;
    }

    private static NotificationMessage message(String userId, long version) {
        GameEvent play = new GameEvent("mlb-1", "mlb-1:" + version, "SCORE_UPDATE", "CHW", 1234L, "CHW scores!",
                "CHW", "CLE", (int) version, 0, "IN_PROGRESS", "MLB", 10 + version).withVersion(version);
        alerts.Play p = alerts.Play.of(play);
        alerts.Alert run = alerts.Alert.of("mlb-1:v" + version, "RUN_SCORED", alerts.Alert.P_SCORE, alerts.Side.HOME, "CHW scores!");
        JsonObject body = NotificationBody.of(p, java.util.List.of(run), new alerts.GameLabels(catalog.Teams.load()));
        return NotificationMessage.of(p, userId, body);
    }

    private RecordMetadata send(NotificationMessage m) throws Exception {
        return producer.send(new ProducerRecord<>(notifications, m.getUserId(), GSON.toJson(m))).get();
    }

    private static void drain(HistoryWriter w, int records) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        int handled = 0;
        while (handled < records) {
            assertTrue(System.nanoTime() < deadline, "handled only " + handled + " of " + records);
            try {
                handled += w.pollOnce(Duration.ofMillis(200));
            } catch (RuntimeException e) {
                // a failed batch is rewound; the next poll retries it
            }
        }
    }

    /** {userId, messageId, createdAt} per row, in each user's created_at order. */
    private List<String[]> rows() throws Exception {
        List<String[]> out = new ArrayList<>();
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, message_id, created_at, league, game_id, home_team, away_team, body"
                             + " FROM notifications ORDER BY user_id, created_at, message_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new String[] {rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant().toString(),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)});
            }
        }
        return out;
    }

    @Test
    void insertsOneRowPerMessageWithTheRecordTimestamp() throws Exception {
        String bob = UUID.randomUUID().toString();
        RecordMetadata meta = send(message(bob, 1));
        drain(writer(), 1);
        List<String[]> rows = rows();
        assertEquals(1, rows.size());
        String[] r = rows.get(0);
        assertEquals(bob, r[0]);
        assertEquals("mlb-1:v1", r[1]);
        assertEquals(Instant.ofEpochMilli(meta.timestamp()).toString(), r[2], "created_at is when Stage 1 sent it");
        assertEquals("MLB", r[3]);
        assertEquals("mlb-1", r[4]);
        assertEquals("mlb-chw", r[5]);
        assertEquals("mlb-cle", r[6]);
        assertTrue(r[7].contains("\"RUN_SCORED\""), "the API Notification is stored as is");
    }

    @Test
    void rereadBatchInsertsNothingNew() throws Exception {
        String bob = UUID.randomUUID().toString();
        for (long v = 1; v <= 3; v++) send(message(bob, v));
        drain(writer(), 3);
        drain(writer(), 3);   // a new group re-reads everything from the start, like a crash before commit
        assertEquals(3, rows().size());
    }

    @Test
    void redeliveredDuplicateKeepsTheFirstTime() throws Exception {
        String bob = UUID.randomUUID().toString();
        RecordMetadata first = send(message(bob, 1));
        Thread.sleep(50);
        send(message(bob, 1));   // Stage 1 crashed after sending and sent it again
        drain(writer(), 2);
        List<String[]> rows = rows();
        assertEquals(1, rows.size());
        assertEquals(Instant.ofEpochMilli(first.timestamp()).toString(), rows.get(0)[2]);
    }

    @Test
    void postgresPausedThenBackLosesAndDoublesNothing() throws Exception {
        List<String> users = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        HistoryWriter w = writer();
        PostgresTestSupport.pause();
        for (long v = 1; v <= 25; v++) for (String u : users) { send(message(u, v)); Thread.sleep(2); }
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        boolean failed = false;
        while (!failed && System.nanoTime() < deadline) {
            try {
                w.pollOnce(Duration.ofMillis(500));
            } catch (RuntimeException e) {
                failed = true;   // the insert timed out; the batch was rewound, not committed
            }
        }
        assertTrue(failed, "a batch failed while Postgres was frozen");
        PostgresTestSupport.unpause();
        drain(w, 50);
        List<String[]> rows = rows();
        assertEquals(50, rows.size(), "each message once");
        for (String u : users) {
            List<String> ids = rows.stream().filter(r -> r[0].equals(u)).map(r -> r[1]).toList();
            List<String> expected = new ArrayList<>();
            for (long v = 1; v <= 25; v++) expected.add("mlb-1:v" + v);
            assertEquals(expected, ids, "created_at order is lane order");
        }
    }

    @Test
    void unreadableRecordsAreSkipped() throws Exception {
        String bob = UUID.randomUUID().toString();
        producer.send(new ProducerRecord<>(notifications, bob, "{not json")).get();
        producer.send(new ProducerRecord<>(notifications, "not-a-uuid", GSON.toJson(message("not-a-uuid", 1)))).get();
        send(message(bob, 2));
        drain(writer(), 3);
        assertEquals(1, rows().size());
    }

    @Test
    void gameNotAnObjectIsSkipped() throws Exception {
        // PR #13 minor 2: a body whose "game" isn't an object threw outside the batch's guard.
        String bob = UUID.randomUUID().toString();
        NotificationMessage bad = message(bob, 1);
        bad.getNotification().addProperty("game", "not an object");
        send(bad);
        send(message(bob, 2));
        drain(writer(), 2);
        assertEquals(1, rows().size());
    }
}
