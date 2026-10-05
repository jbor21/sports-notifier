package feed;

import model.GameSnapshot;

/**
 * Turns a snapshot's position in the game into one number: bigger means later. The Lua script
 * rejects any event whose seq is not bigger than the game's saved one, so the saved state never
 * goes back in game time, and two pollers can't undo each other.
 *
 * The number is 14 decimal digits, one block per field, most significant first, so the fields
 * can be read straight off the digits:
 *
 * <pre>
 *   phase | period | half | clock | outs | stage | score total
 *     1   |   2    |  1   |   5   |  1   |   1   |     3
 * </pre>
 *
 * <ul>
 *   <li>clock is 36000 minus tenths of a second left, so less time left is bigger (0 for MLB).</li>
 *   <li>half and outs are MLB only. Outs count only during a half: in "Mid" and "End" ESPN's
 *       outs are leftovers that flip between 2 and 0.</li>
 *   <li>score total comes last: it only breaks ties at a stopped clock (a touchdown, then the
 *       extra point). A correction after the clock moved has a bigger seq and wins, as it should.</li>
 * </ul>
 *
 * Example, NFL 4th quarter, 2:00 left, 24-21: 1 04 0 34800 0 0 045 = 10403480000045.
 * The largest possible value is under 10^14, well inside the 2^53 Lua compares exactly.
 */
public final class Seq {

    private static final int MAX_CLOCK_TENTHS = 36000;

    private Seq() {}

    public static long of(GameSnapshot s) {
        long clock = "MLB".equals(s.league())
            ? 0
            : MAX_CLOCK_TENTHS - clamp(s.clockTenths(), 0, MAX_CLOCK_TENTHS);
        boolean inHalf = s.half() == GameSnapshot.Half.TOP || s.half() == GameSnapshot.Half.BOTTOM;
        // Never 0: Event Processing rejects seq <= 0. Only an MLB game before the first inning with
        // no score can compute 0, and 1 is still below every other position.
        return Math.max(1, s.phase().ordinal()     * 10_000_000_000_000L
             + clamp(s.period(), 0, 99)            *    100_000_000_000L
             + s.half().ordinal()                  *     10_000_000_000L
             + clock                               *            100_000L
             + (inHalf ? clamp(s.outs(), 0, 3) : 0) *             10_000L
             + s.stage().ordinal()                 *              1_000L
             + Math.min(s.homeScore() + s.awayScore(), 999));
    }

    private static long clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
