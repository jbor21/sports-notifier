package service;

import model.UserPreferences;

/**
 * Manages user subscription preferences and cached subscription mappings.
 */
public interface UserPreferenceService {

    /**
     * Saves a user's notification and subscription preferences.
     *
     * @param userId unique identifier of the user
     * @param preferences updated user preferences
     */
    void savePreferences(String userId, UserPreferences preferences);

    /**
     * Retrieves the current preferences for a given user.
     *
     * @param userId unique identifier of the user
     * @return the user's saved preferences
     */
    UserPreferences getPreferences(String userId);

    /**
     * Updates cached subscription mappings used by pub/sub workers
     * to quickly determine which users should receive notifications.
     *
     * @param userId unique identifier of the user whose preferences changed
     * @param preferences updated user preferences
     */
    void updateSubscriptionCache(String userId, UserPreferences preferences);
}