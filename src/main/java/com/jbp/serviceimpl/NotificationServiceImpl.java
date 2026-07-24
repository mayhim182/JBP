package com.jbp.serviceimpl;

import com.jbp.dto.NotificationResponse;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.model.Notification;
import com.jbp.model.NotificationType;
import com.jbp.model.User;
import com.jbp.repository.NotificationRepository;
import com.jbp.repository.UserRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.EmailSender;
import com.jbp.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationServiceImpl implements NotificationService {

    private static final String EMAIL_SUBJECT = "Update from JBP";

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final EmailSender emailSender;

    @Override
    @Transactional
    public void createNotification(Long recipientId, NotificationType type, String message) {
        User recipient = userRepository.findById(recipientId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + recipientId));

        Notification notification = Notification.builder()
                .recipient(recipient)
                .type(type)
                .message(message)
                .read(false)
                .build();
        notificationRepository.save(notification);
        emailSender.send(recipient.getEmail(), EMAIL_SUBJECT, message);
        log.info("Notification created for user {} (type {})", recipientId, type);
    }

    @Override
    public List<NotificationResponse> getMyNotifications() {
        Long userId = currentUserProvider.getCurrentUserId();
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void markAsRead(Long notificationId) {
        Long userId = currentUserProvider.getCurrentUserId();
        Notification notification = notificationRepository.findByIdAndRecipientId(notificationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found with id: " + notificationId));
        notification.setRead(true);
        notificationRepository.save(notification);
    }

    @Override
    @Transactional
    public void markAllAsRead() {
        Long userId = currentUserProvider.getCurrentUserId();
        List<Notification> unread = notificationRepository.findByRecipientIdAndReadFalse(userId);
        unread.forEach(notification -> notification.setRead(true));
        notificationRepository.saveAll(unread);
    }

    private NotificationResponse toResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .type(notification.getType())
                .message(notification.getMessage())
                .read(notification.isRead())
                .createdAt(notification.getCreatedAt())
                .build();
    }
}
