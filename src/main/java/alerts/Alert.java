package alerts;

import java.util.Comparator;
import java.util.List;

/**
 * One detected alert.
 *
 * @param id       deterministic: the same moment always gets the same id (spec 2.4), so Stage 2's dedup
 *                 catches a re-detection after a crash or a Redis rollback
 * @param type     what the user sees as the alert's kind, e.g. {@code TOUCHDOWN}
 * @param switches the follow switches that reach it; usually just {@code type}
 * @param priority headline order inside one push, lower first (spec 5.3)
 * @param side     the team it is about, or null
 * @param text     ready to show, with the score and the position
 */
public record Alert(String id, String type, List<String> switches, int priority, Side side, String text) {

    public static final int P_OVERTIME = 1;
    public static final int P_SCORE = 2;
    public static final int P_TRY = 3;
    public static final int P_LEAD = 4;
    public static final int P_CLOSE = 5;
    public static final int P_PERIOD = 6;
    public static final int P_FLOW = 7;

    /** Priority, then home before away (no side last), then id. */
    public static final Comparator<Alert> ORDER = Comparator.comparingInt(Alert::priority)
        .thenComparingInt(a -> a.side() == null ? 2 : a.side().ordinal())
        .thenComparing(Alert::id);

    /** An alert whose only switch is its type. */
    public static Alert of(String id, String type, int priority, Side side, String text) {
        return new Alert(id, type, List.of(type), priority, side, text);
    }
}
