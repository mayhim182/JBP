package com.jbp.service;

import com.jbp.dto.NotificationResponse;
import com.jbp.model.NotificationType;

import java.util.List;

public interface NotificationService {

    /** Stores an in-app notification for the recipient and sends them an email. */
    void createNotification(Long recipientId, NotificationType type, String message);

    List<NotificationResponse> getMyNotifications();

    void markAsRead(Long notificationId);

    void markAllAsRead();
}
