package alerts;

/** Test plays: {@code PlayBuilder.nfl("g1").live().period(4).clock(2520).score(17, 20).build()}. */
public final class PlayBuilder {

    private String gameId;
    private long version = 1;
    private String league;
    private String home;
    private String away;
    private int homeScore;
    private int awayScore;
    private String phase = "LIVE";
    private String status = "IN_PROGRESS";
    private String stage = "IN_PLAY";
    private int period = 1;
    private String half = "TOP";
    private int clockTenths = 9000;
    private int outs;
    private int seasonType = 2;
    private String detail;

    private PlayBuilder(String league, String gameId, String home, String away) {
        this.league = league;
        this.gameId = gameId;
        this.home = home;
        this.away = away;
        if ("MLB".equals(league)) clockTenths = 0;
    }

    /** Bears (home) vs Raiders. */
    public static PlayBuilder nfl(String gameId) { return new PlayBuilder("NFL", gameId, "CHI", "LV"); }
    /** Celtics (home) vs Lakers. */
    public static PlayBuilder nba(String gameId) { return new PlayBuilder("NBA", gameId, "BOS", "LAL"); }
    /** Cubs (home) vs Cardinals. */
    public static PlayBuilder mlb(String gameId) { return new PlayBuilder("MLB", gameId, "CHC", "STL"); }
    /** Bruins (home) vs Rangers. */
    public static PlayBuilder nhl(String gameId) { return new PlayBuilder("NHL", gameId, "BOS", "NYR"); }

    /** A copy to vary for the next play; the version goes up by one. */
    public PlayBuilder next() {
        PlayBuilder b = new PlayBuilder(league, gameId, home, away);
        b.version = version + 1;
        b.homeScore = homeScore; b.awayScore = awayScore;
        b.phase = phase; b.status = status; b.stage = stage;
        b.period = period; b.half = half; b.clockTenths = clockTenths; b.outs = outs;
        b.seasonType = seasonType; b.detail = detail;
        return b;
    }

    public PlayBuilder pre() { phase = "PRE"; status = "SCHEDULED"; stage = "IN_PLAY"; period = 0; return this; }
    public PlayBuilder live() { phase = "LIVE"; status = "IN_PROGRESS"; stage = "IN_PLAY"; return this; }
    public PlayBuilder fin() { phase = "FINAL"; status = "FINAL"; stage = "IN_PLAY"; return this; }
    public PlayBuilder status(String s) { status = s; return this; }
    public PlayBuilder phase(String p) { phase = p; return this; }
    public PlayBuilder stage(String s) { stage = s; return this; }
    public PlayBuilder period(int p) { period = p; return this; }
    public PlayBuilder clock(int tenths) { clockTenths = tenths; return this; }
    public PlayBuilder half(String h) { half = h; return this; }
    public PlayBuilder score(int h, int a) { homeScore = h; awayScore = a; return this; }
    public PlayBuilder teams(String h, String a) { home = h; away = a; return this; }
    public PlayBuilder season(int t) { seasonType = t; return this; }
    public PlayBuilder detail(String d) { detail = d; return this; }
    public PlayBuilder version(long v) { version = v; return this; }

    public Play build() {
        return new Play(gameId, version, league, home, away, homeScore, awayScore, phase, status, stage,
            period, half, clockTenths, outs, seasonType, detail, 1_790_000_000_000L);
    }
}
