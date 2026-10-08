package app;

import gateway.GatewayMain;
import service.impl.EventProcessingServiceMain;
import service.impl.GameStateServiceMain;
import service.impl.HistoryWriterMain;
import service.impl.NotificationServiceMain;
import service.impl.Stage1WorkerMain;
import service.impl.Stage2WorkerMain;
import service.impl.UserPreferenceServiceMain;
import simulator.EspnPollerMain;
import simulator.EventSimulatorMain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One image runs every service; the SERVICE env var picks which main starts. */
public final class Launcher {

    @FunctionalInterface
    interface MainMethod {
        void run(String[] args) throws Exception;
    }

    static final Map<String, MainMethod> SERVICES;
    static {
        Map<String, MainMethod> m = new LinkedHashMap<>();
        m.put("gateway", GatewayMain::main);
        m.put("user-pref", UserPreferenceServiceMain::main);
        m.put("game-state", GameStateServiceMain::main);
        m.put("event-processing", EventProcessingServiceMain::main);
        m.put("stage1-worker", Stage1WorkerMain::main);
        m.put("stage2-worker", Stage2WorkerMain::main);
        m.put("notification", NotificationServiceMain::main);
        m.put("history", HistoryWriterMain::main);
        m.put("simulator", EventSimulatorMain::main);
        m.put("espn-poller", EspnPollerMain::main);
        SERVICES = Collections.unmodifiableMap(m);
    }

    private Launcher() {}

    static MainMethod resolve(String service) {
        String valid = "Valid values: " + String.join(", ", SERVICES.keySet());
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("SERVICE is not set. " + valid);
        }
        MainMethod main = SERVICES.get(service);
        if (main == null) {
            throw new IllegalArgumentException("Unknown SERVICE \"" + service + "\". " + valid);
        }
        return main;
    }

    public static void main(String[] args) throws Exception {
        MainMethod main;
        try {
            main = resolve(System.getenv("SERVICE"));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        main.run(args);
    }
}
