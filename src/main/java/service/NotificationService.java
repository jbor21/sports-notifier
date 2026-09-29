package service;

import model.Notification;
import java.util.List;

/**
 * Handles creation, storage, and delivery of notifications to users.
 */
public interface NotificationService {

    /**
     * Sends a notification to a list of users.
     *
     * @param userIds users who should receive the notification
     * @param notification notification payload
     */
    void sendNotification(List<String> userIds, Notification notification);

    /**
     * Sends a notification to a single user.
     *
     * @param userId user who should receive the notification
     * @param notification notification payload
     */
    void sendNotification(String userId, Notification notification);

    /**
     * Retrieves recent notification history for a user.
     *
     * @param userId unique identifier of the user
     * @param limit maximum number of notifications to return
     * @return recent notifications for the user
     */
    List<Notification> getNotificationHistory(String userId, int limit);
}