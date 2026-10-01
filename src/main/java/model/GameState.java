package model;

/**
 * Represents the current state of a game, including teams, score, and status..
 */
public class GameState {

    private final String gameId;
    private final String homeTeam;
    private final String awayTeam;
    private final String status;
    private final int homeScore;
    private final int awayScore;

    /**
     * Creates a new game state object.
     *
     * @param gameId unique identifier of the game
     * @param homeTeam home team identifier or name
     * @param awayTeam away team identifier or name
     * @param status current game status, such as IN_PROGRESS or FINAL
     * @param homeScore current score of the home team
     * @param awayScore current score of the away team
     */
    public GameState(String gameId, String homeTeam, String awayTeam, String status, int homeScore, int awayScore) {
        this.gameId = gameId;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.status = status;
        this.homeScore = homeScore;
        this.awayScore = awayScore;
    }

    /**
     * @return the game identifier
     */
    public String getGameId() {
        return gameId;
    }

    /**
     * @return the home team
     */
    public String getHomeTeam() {
        return homeTeam;
    }

    /**
     * @return the away team
     */
    public String getAwayTeam() {
        return awayTeam;
    }

    /**
     * @return the current game status
     */
    public String getStatus() {
        return status;
    }

    /**
     * @return the home team score
     */
    public int getHomeScore() {
        return homeScore;
    }

    /**
     * @return the away team score
     */
    public int getAwayScore() {
        return awayScore;
    }
}
