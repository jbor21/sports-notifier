package games;

/**
 * One day's game list: start times in {@code [fromMillis, toMillis)}, optionally one league, one team
 * (league-qualified id, e.g. {@code nfl-chi}), or one API status (e.g. {@code in_progress}).
 */
public record GameQuery(long fromMillis, long toMillis, String league, String teamId, String status) {}
