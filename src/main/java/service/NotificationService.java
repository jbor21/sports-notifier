package service;

import model.Notification;
import java.util.List;

/**
 * Reads a user's notification history. Delivery itself is Stage 2's deliver script (Delivery).
 */
public interface NotificationService {

    /**
     * Retrieves recent notification history for a user.
     *
     * @param userId unique identifier of the user
     * @param limit maximum number of notifications to return
     * @return recent notifications for the user
     */
    List<Notification> getNotificationHistory(String userId, int limit);
}