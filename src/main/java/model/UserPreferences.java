package model;

import java.util.List;

/**
 * Represents a user's subscription and notification preferences..
 */
public class UserPreferences {

    private final String userId;
    private final List<String> followedTeams;
    private final List<String> followedGames;
    private final List<String> followedLeagues;
    private final List<String> notificationTypes;

    /**
     * Creates a new user preference object.
     *
     * @param userId unique identifier of the user
     * @param followedTeams teams the user follows
     * @param followedGames games the user follows
     * @param followedLeagues leagues the user follows
     * @param notificationTypes notification categories enabled by the user
     */
    public UserPreferences(String userId, List<String> followedTeams, List<String> followedGames, List<String> followedLeagues, List<String> notificationTypes) {
        this.userId = userId;
        this.followedTeams = followedTeams;
        this.followedGames = followedGames;
        this.followedLeagues = followedLeagues;
        this.notificationTypes = notificationTypes;
    }

    /**
     * @return the user identifier
     */
    public String getUserId() {
        return userId;
    }

    /**
     * @return list of followed teams
     */
    public List<String> getFollowedTeams() {
        return followedTeams;
    }

    /**
     * @return list of followed games
     */
    public List<String> getFollowedGames() {
        return followedGames;
    }

    /**
     * @return list of followed leagues
     */
    public List<String> getFollowedLeagues() {
        return followedLeagues;
    }

    /**
     * @return list of enabled notification types
     */
    public List<String> getNotificationTypes() {
        return notificationTypes;
    }
}