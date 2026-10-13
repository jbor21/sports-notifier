package tools;

import alerts.Detectors;
import alerts.GameLabels;
import catalog.AlertCatalog;
import catalog.FollowKind;
import catalog.Teams;
import checker.Api;
import checker.Claims;
import checker.Cleanup;
import checker.EndCondition;
import checker.ExpectedPush;
import checker.Faults;
import checker.Finding;
import checker.FollowFreeze;
import checker.FollowMix;
import checker.FollowSnapshot;
import checker.HistoryRow;
import checker.Lag;
import checker.Oracle;
import checker.RedisClock;
import checker.PlayReader;
import checker.Report;
import checker.RunId;
import checker.Session;
import checker.SetupException;
import checker.Snapshots;
import checker.SocketClient;
import checker.SocketLog;
import checker.Verifier;
import com.google.gson.JsonObject;
import feed.HttpEventSink;
import feed.Poller;
import feed.RecordedScoreFeed;
import feed.TeamIds;
import model.GameEvent;
import model.GameSnapshot;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import service.impl.Follow;
import service.impl.FollowSets;
import simulator.Replay;
import util.Db;
import util.JedisPoolHolder;
import util.KafkaSettings;
import util.Topics;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The correctness checker (Phase 4.1 spec; DESIGN.md decision 14). One process is one run: replay a
 * recorded set through the real pipeline to synthetic users on real sockets, then check that every
 * user got exactly the pushes they should have, once, in game order, in history and on every socket.
 *
 * Order: prime the replay (every game gets its record), sign up users and make their follows, open the
 * sockets, T0 and the follow snapshot, start the replay, wait for a steady end, then recompute what was
 * expected from game-events and the frozen follows and compare. {@code CHECK_MODE=clean} instead removes
 * every leftover synthetic user.
 */
public final class Checker {

    private static final Map<String, String> GROUPS = Map.of(
            Topics.STAGE1_GROUP, Topics.GAME_EVENTS,
            Topics.STAGE2_GROUP, Topics.NOTIFICATIONS,
            Topics.HISTORY_GROUP, Topics.NOTIFICATIONS);

    private final String gateway = env("GATEWAY_URL", "http://gateway:8080");
    private final String epsUrl = env("EPS_URL", "http://event-processing:8083");
    private final String set = env("CHECK_SET", "ci");
    private final double speed = Double.parseDouble(env("CHECK_SPEED", "10"));
    private final int users = Integer.parseInt(env("CHECK_USERS", "500"));
    private final long seed = Long.parseLong(env("CHECK_SEED", "1"));
    private final boolean keep = "1".equals(env("CHECK_KEEP", ""));
    private final String fault = emptyToNull(env("CHECK_FAULT", ""));
    private final Duration runTimeout = Duration.ofSeconds(Long.parseLong(env("RUN_TIMEOUT_SECONDS", "300")));
    private final String password = env("CHECKER_PASSWORD", "checker-pass");

    private final DataSource db;
    private final JedisPool pool;
    private final Api api;

    private final List<Session> sessions = new ArrayList<>();
    private final Map<String, List<String>> followIds = new ConcurrentHashMap<>();
    private final List<SocketLog> logs = new ArrayList<>();
    private final List<SocketClient> clients = new ArrayList<>();
    /** The user a self-test fault was applied to; null until it is. */
    private volatile String faultUser;

    private Checker(DataSource db, JedisPool pool) {
        this.db = db;
        this.pool = pool;
        this.api = new Api(URI.create(gateway));
    }

    public static void main(String[] args) throws Exception {
        JedisPoolHolder.awaitRedisOrExit("checker");
        DataSource db = Db.fromEnv("checker");
        int code;
        try (JedisPool pool = JedisPoolHolder.create()) {
            if ("clean".equals(env("CHECK_MODE", "run"))) {
                System.out.println("[Checker] removed " + Cleanup.cleanAll(db, pool) + " synthetic users; sub:* sets will be rebuilt");
                code = 0;
            } else {
                code = new Checker(db, pool).run();
            }
        }
        System.exit(code);
    }

    /** The replay set, or a setup failure naming it (exit 2, before any user or game exists). */
    static List<RecordedScoreFeed.Line> loadSet(String set) {
        try {
            return RecordedScoreFeed.load(set);
        } catch (IllegalArgumentException e) {
            throw new SetupException(e.getMessage(), e);
        }
    }

    private int run() throws Exception {
        if (fault != null) Faults.primaryKind(fault);              // reject an unknown fault before doing anything
        List<RecordedScoreFeed.Line> lines;
        try {
            lines = loadSet(set);
        } catch (SetupException e) {
            System.out.println("SETUP FAILED: " + e.getMessage());
            return 2;
        }
        long runStart = System.currentTimeMillis();
        String runId = RunId.of(runStart / 1000);
        RecordedScoreFeed feed = new RecordedScoreFeed(lines, runId, runStart,
                System::currentTimeMillis, speed);
        Replay replay = new Replay(feed, new Poller(feed, pool, new HttpEventSink(epsUrl), System::currentTimeMillis),
                pool, Replay.pollMillis(speed));
        System.out.println("[Checker] run " + runId + ": set " + set + " at " + speed + "x, " + users + " users, seed " + seed
                + (fault == null ? "" : ", fault " + fault));

        List<FollowMix.UserPlan> plans;
        List<String> userIds = new ArrayList<>();
        try {
            replay.prime(Duration.ofSeconds(60));
            plans = FollowMix.plan(seed, users, gameRefs(feed), AlertCatalog.load());
            setUp(runId, plans, userIds);
            openSockets(plans, userIds);
        } catch (SetupException | IllegalStateException e) {
            System.out.println("SETUP FAILED: " + e.getMessage());
            clients.forEach(SocketClient::close);
            cleanUp(runId);
            return 2;
        }

        try {
            return runChecked(runId, runStart, feed, replay, plans, userIds);
        } catch (SetupException e) {
            System.out.println("SETUP FAILED: " + e.getMessage());
            return 2;
        } finally {
            replay.stop();
            clients.forEach(SocketClient::close);
            if (keep) {
                System.out.println("[Checker] KEEP=1: sign in as " + RunId.username(runId, 0) + " / " + password
                        + "; make check-clean removes the run's users");
            } else {
                cleanUp(runId);
            }
        }
    }

    /** From T0 to the verdict; the caller stops the replay, closes the sockets, and cleans up whatever happens. */
    private int runChecked(String runId, long runStart, RecordedScoreFeed feed, Replay replay,
                           List<FollowMix.UserPlan> plans, List<String> userIds) throws Exception {
        Thread.sleep(1000);                                          // every follow write is strictly before T0
        Instant t0 = Snapshots.dbNow(db);
        long offsetAtStart = redisClockOffset();
        List<FollowSnapshot> start = Snapshots.follows(db, userIds);
        applyFaultBeforeRun(plans, userIds);
        replay.start();
        // scripts/chaos.sh times its fault from this line.
        System.out.println("[Checker] run started: T0 " + t0 + ", replay "
                + (long) Math.ceil(feed.lengthMillis() / speed / 1000) + " s at " + Report.speed(speed) + "x");

        EndCondition.State state = waitForEnd(replay);
        replay.stop();
        Instant runEnd = Snapshots.dbNow(db);
        long clockOffset = (offsetAtStart + redisClockOffset()) / 2;
        List<FollowSnapshot> end = Snapshots.follows(db, userIds);
        clients.forEach(SocketClient::close);

        Map<String, List<GameEvent>> plays = PlayReader.read(KafkaSettings.bootstrap(), Topics.GAME_EVENTS, runStart - 5_000, runId);
        GameLabels labels = new GameLabels(Teams.load());
        List<Follow> follows = start.stream().map(FollowSnapshot::follow).toList();
        List<ExpectedPush> expected = new Oracle(Detectors.standard(labels), labels).expected(plays, follows);
        List<HistoryRow> history = Snapshots.history(db, userIds, RunId.gameMarker(runId));
        Map<String, Long> deliveredAt = Claims.deliveredAt(pool, expected);
        Verifier.Result result = Verifier.verify(runId, expected, history, logs, deliveredAt, clockOffset);

        List<Finding> other = new ArrayList<>(FollowFreeze.compare(start, end, t0, runEnd));
        if (state.kind() == EndCondition.Kind.TIMED_OUT) {
            other.add(new Finding("timeout", "-", null, null, "no steady end " + runTimeout.toSeconds() + " s after the replay: " + state.reason()));
        }
        int twoTabs = (int) plans.stream().filter(FollowMix.UserPlan::twoTabs).count();
        int playCount = plays.values().stream().mapToInt(List::size).sum();
        System.out.print(Report.render(new Report.Header(runId, set, speed, users, twoTabs, feed.gameIds().size(),
                playCount, expected.size()), result, other));

        List<Finding> all = new ArrayList<>(result.findings());
        all.addAll(other);
        if (fault != null) {
            boolean caught = faultUser != null && Faults.passes(fault, faultUser, all);
            System.out.println("[Checker] self-test " + fault + ": " + (caught ? "caught (" + Faults.primaryKind(fault) + ")" : "NOT caught"));
            return caught ? 0 : 1;
        }
        return all.isEmpty() ? 0 : 1;
    }

    /** The set's games and their league-qualified team ids, for the follow mix. */
    private static List<FollowMix.GameRef> gameRefs(RecordedScoreFeed feed) {
        List<FollowMix.GameRef> out = new ArrayList<>();
        for (Map.Entry<String, GameSnapshot> g : feed.firstSnapshots().entrySet()) {
            GameSnapshot s = g.getValue();
            String home = TeamIds.of(s.league(), s.homeTeam()), away = TeamIds.of(s.league(), s.awayTeam());
            if (home != null && away != null) out.add(new FollowMix.GameRef(g.getKey(), s.league(), home, away));
        }
        return out;
    }

    /** Signs up every user and makes their follows, 16 at a time (bcrypt makes signup the slow part). */
    private void setUp(String runId, List<FollowMix.UserPlan> plans, List<String> userIds) throws Exception {
        ExecutorService pool16 = Executors.newFixedThreadPool(16);
        try {
            List<Future<Session>> done = new ArrayList<>();
            for (FollowMix.UserPlan u : plans) {
                done.add(pool16.submit(() -> {
                    Session s = api.signup(RunId.username(runId, u.n()), password);
                    for (FollowMix.FollowPlan f : u.follows()) {
                        String id = api.follow(s, f.kind(), f.targetId()).get("id").getAsString();
                        followIds.computeIfAbsent(s.userId(), k -> new ArrayList<>()).add(id);
                        if (f.alerts() != null) api.patchAlerts(s, id, f.alerts());
                    }
                    return s;
                }));
            }
            // Every user that got through is kept, even after one failed, so cleanup can unfollow them.
            RuntimeException first = null;
            for (Future<Session> f : done) {
                try {
                    Session s = f.get();
                    sessions.add(s);
                    userIds.add(s.userId());
                } catch (java.util.concurrent.ExecutionException e) {
                    if (first == null) {
                        first = e.getCause() instanceof SetupException se ? se
                                : new SetupException("setting up a user failed", e.getCause());
                    }
                }
            }
            if (first != null) throw first;
        } finally {
            pool16.shutdownNow();
        }
        System.out.println("[Checker] " + sessions.size() + " users and their follows created");
    }

    /** One socket per user (two for some), then waits until every socket has had its hello. */
    private void openSockets(List<FollowMix.UserPlan> plans, List<String> userIds) throws InterruptedException {
        URI ws = URI.create(gateway.replaceFirst("^http", "ws") + "/api/v1/ws");
        for (int i = 0; i < plans.size(); i++) {
            for (int tab = 0; tab < (plans.get(i).twoTabs() ? 2 : 1); tab++) {
                SocketLog log = new SocketLog(userIds.get(i), tab);
                SocketClient c = new SocketClient(ws, sessions.get(i).sid(), log, System::currentTimeMillis, Duration.ofSeconds(25));
                logs.add(log);
                clients.add(c);
                c.start();
            }
        }
        long until = System.currentTimeMillis() + 60_000;
        while (clients.stream().anyMatch(c -> !c.helloSeen())) {
            if (System.currentTimeMillis() > until) {
                long open = clients.stream().filter(c -> !c.helloSeen()).count();
                throw new SetupException(open + " sockets got no hello within 60 s", null);
            }
            Thread.sleep(200);
        }
        System.out.println("[Checker] " + clients.size() + " sockets open");
    }

    /**
     * srem and follow act on the first single-team follower (row 1) with game start on: their game
     * start push is the first one a lost write or a changed switch would affect. dup waits for the
     * first push (see waitForEnd).
     */
    private void applyFaultBeforeRun(List<FollowMix.UserPlan> plans, List<String> userIds) {
        if (fault == null || fault.equals("dup")) return;
        for (int i = 0; i < plans.size(); i++) {
            FollowMix.UserPlan u = plans.get(i);
            FollowMix.FollowPlan f = u.follows().get(0);
            JsonObject start = f.alerts() == null ? null : f.alerts().getAsJsonObject("GAME_START");
            if (u.row() != 1 || start == null || !start.get("enabled").getAsBoolean()) continue;
            String user = userIds.get(i);
            if (fault.equals("srem")) {
                Faults.srem(pool, FollowSets.key(FollowKind.TEAM, f.targetId(), "GAME_START"), user);
            } else {
                String followId = followIds.get(user).get(0);
                api.patchAlerts(sessions.get(i), followId, switchTo("GAME_START", false));
                api.patchAlerts(sessions.get(i), followId, switchTo("GAME_START", true));
            }
            System.out.println("[Checker] fault " + fault + " applied to " + user + " (team " + f.targetId() + ", game start)");
            faultUser = user;
            return;
        }
        throw new SetupException("no user to apply fault " + fault + " to", null);
    }

    private static JsonObject switchTo(String type, boolean on) {
        JsonObject s = new JsonObject();
        s.addProperty("enabled", on);
        JsonObject alerts = new JsonObject();
        alerts.add(type, s);
        return alerts;
    }

    /** Redis's clock minus ours: Stage 2's delivery times are Redis's, socket times are ours (Phase 4.2 spec, 7.1). */
    private long redisClockOffset() {
        try (Jedis jedis = pool.getResource()) {
            return RedisClock.offsetMillis(jedis, System::currentTimeMillis);
        }
    }

    private EndCondition.State waitForEnd(Replay replay) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaSettings.bootstrap()))) {
            EndCondition end = new EndCondition(System::currentTimeMillis, replay::feedDone, replay::notFinal, () -> {
                try {
                    return Lag.read(admin, GROUPS);
                } catch (Exception e) {
                    return Map.of("lag unreadable: " + e.getMessage(), 1L);   // never "no lag" when we can't tell
                }
            }, Duration.ofSeconds(10), runTimeout);
            boolean duplicated = false;
            long lastNote = 0;
            while (true) {
                EndCondition.State s = end.poll();
                if (s.kind() != EndCondition.Kind.WAITING) {
                    System.out.println("[Checker] run ended: " + s.kind() + (s.reason().isEmpty() ? "" : " (" + s.reason() + ")"));
                    return s;
                }
                if ("dup".equals(fault) && !duplicated) duplicated = duplicateFirstArrival();
                if (System.currentTimeMillis() - lastNote > 30_000) {
                    System.out.println("[Checker] waiting: " + s.reason());
                    lastNote = System.currentTimeMillis();
                }
                Thread.sleep(1000);
            }
        }
    }

    /** The dup fault: the first push any socket got, published to that user's gateways again. */
    private boolean duplicateFirstArrival() {
        for (SocketLog log : logs) {
            List<SocketLog.Arrival> a = log.arrivals();
            if (a.isEmpty()) continue;
            Faults.duplicate(pool, log.userId(), a.get(0).body());
            System.out.println("[Checker] fault dup: re-published " + a.get(0).messageId() + " to " + log.userId());
            faultUser = log.userId();
            return true;
        }
        return false;
    }

    private void cleanUp(String runId) {
        try {
            int n = Cleanup.run(api, sessions, followIds, db, pool, runId);
            System.out.println("[Checker] removed the run's " + n + " users");
        } catch (RuntimeException e) {
            System.out.println("[Checker] cleanup failed, run make check-clean: " + e.getMessage());
        }
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
