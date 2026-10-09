package service.impl;

import alerts.Alert;
import alerts.Detectors;
import alerts.GameLabels;
import alerts.Play;
import com.google.gson.JsonObject;
import model.NotificationBody;
import model.NotificationMessage;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import util.RedisPipelines;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Turns one play's alerts into messages: one per user per moment, plus the final as its own message
 * (Phase 3.3 spec, 5.2–5.4). Which alerts go in which push is {@link MomentPushes}; this class finds
 * the audiences in Redis and hands each user's push to the sink, every moment message before any final.
 *
 * The audience is read a page at a time (SSCAN), and for each page one SMISMEMBER per alert says which
 * of the moment's alerts each user gets, so memory holds one page however big the audience.
 */
public final class MomentFanout {

    private static final int PAGE_SIZE = 1000;
    private static final long TEMP_KEY_TTL_SECONDS = 60L;

    private final JedisPool pool;
    private final AlertAudience audience;
    private final GameLabels labels;

    public MomentFanout(JedisPool pool, AlertAudience audience, GameLabels labels) {
        this.pool = pool;
        this.audience = audience;
        this.labels = labels;
    }

    public void send(Play play, Detectors.Result result, BiConsumer<String, NotificationMessage> sink) {
        try (Jedis jedis = pool.getResource()) {
            for (List<Alert> group : MomentPushes.groups(result)) sendGroup(jedis, play, group, sink);
        }
    }

    /** One message per user reached by any of {@code alerts}, holding the ones they get, in headline order. */
    private void sendGroup(Jedis jedis, Play play, List<Alert> alerts, BiConsumer<String, NotificationMessage> sink) {
        AlertAudience.Built built = audience.build(jedis, play, alerts);
        String union = built.keys().get(0) + ":all";
        // Bodies differ only by which alerts a user gets: usually one or two shapes per moment.
        Map<String, JsonObject> bodies = new HashMap<>();
        try {
            if (alerts.size() == 1) {
                union = built.keys().get(0);
            } else {
                Pipeline p = jedis.pipelined();
                p.sunionstore(union, built.keys().toArray(new String[0]));
                p.expire(union, TEMP_KEY_TTL_SECONDS);
                RedisPipelines.syncChecked(p);
            }
            ScanParams params = new ScanParams().count(PAGE_SIZE);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> page = jedis.sscan(union, cursor, params);
                List<String> users = page.getResult();
                if (!users.isEmpty()) {
                    List<List<Boolean>> member = membership(jedis, built.keys(), users, alerts.size());
                    for (int u = 0; u < users.size(); u++) {
                        List<Boolean> in = new ArrayList<>(alerts.size());
                        StringBuilder shape = new StringBuilder();
                        for (int a = 0; a < alerts.size(); a++) {
                            in.add(member.get(a).get(u));
                            shape.append(in.get(a) ? '1' : '0');
                        }
                        List<Alert> theirs = MomentPushes.pick(alerts, in);
                        if (theirs.isEmpty()) continue;
                        JsonObject body = bodies.computeIfAbsent(shape.toString(), k -> NotificationBody.of(play, theirs, labels));
                        sink.accept(users.get(u), NotificationMessage.of(play, users.get(u), body));
                    }
                }
                cursor = page.getCursor();
            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
        } finally {
            audience.drop(jedis, built);
            jedis.del(union);
        }
    }

    /** For each alert, whether each user of the page is in its audience. A single alert needs no lookup. */
    private static List<List<Boolean>> membership(Jedis jedis, List<String> keys, List<String> users, int alerts) {
        if (alerts == 1) return List.of(java.util.Collections.nCopies(users.size(), true));
        String[] members = users.toArray(new String[0]);
        Pipeline p = jedis.pipelined();
        List<Response<List<Boolean>>> responses = new ArrayList<>();
        for (String key : keys) responses.add(p.smismember(key, members));
        RedisPipelines.syncChecked(p);
        List<List<Boolean>> out = new ArrayList<>();
        for (Response<List<Boolean>> r : responses) out.add(r.get());
        return out;
    }
}
