package alerts;

import model.GameEvent;

/**
 * One accepted play as the detectors see it: the game's whole position after the play. Built from the
 * {@link GameEvent} Stage 1 reads; detectors never decode {@code seq}.
 *
 * @param clockTenths tenths of a second left in the period; 0 for MLB; -1 when the feed sent no clock
 */
public record Play(String gameId, long version, String league, String homeTeam, String awayTeam,
                   int homeScore, int awayScore, String phase, String status, String stage,
                   int period, String half, int clockTenths, int outs, int seasonType, String detail,
                   long detectedAtMillis) {

    public static Play of(GameEvent e) {
        return new Play(e.getGameId(), e.getVersion(), e.getLeague(), e.getHomeTeam(), e.getAwayTeam(),
            e.getHomeScore(), e.getAwayScore(), phaseOf(e), e.getGameStatus(), e.getStage() == null ? "IN_PLAY" : e.getStage(),
            e.getPeriod(), e.getHalf() == null ? "TOP" : e.getHalf(), e.getClockTenths(), e.getOuts(),
            e.getSeasonType() == 0 ? 2 : e.getSeasonType(), e.getDetail(), e.getTimestamp());
    }

    /** Plays from before 3.3 (or a sender that doesn't say) carry no phase: read it from the status. */
    private static String phaseOf(GameEvent e) {
        if (e.getPhase() != null && !e.getPhase().isEmpty()) return e.getPhase();
        String s = e.getGameStatus();
        if ("FINAL".equals(s) || "CANCELED".equals(s)) return "FINAL";
        if ("SCHEDULED".equals(s) || "POSTPONED".equals(s)) return "PRE";
        return "LIVE";
    }

    public boolean live() {
        return "LIVE".equals(phase);
    }

    /** The game is over with a result (not canceled). */
    public boolean isFinal() {
        return "FINAL".equals(status);
    }

    public int score(Side s) {
        return s == Side.HOME ? homeScore : awayScore;
    }

    public String team(Side s) {
        return s == Side.HOME ? homeTeam : awayTeam;
    }

    /** True between periods: the clock isn't running and isn't shown. */
    public boolean betweenPeriods() {
        return "END_PERIOD".equals(stage) || "HALFTIME".equals(stage);
    }
}
