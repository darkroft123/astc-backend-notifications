package io.mindoracare.notification.email.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "in_app_notification", 
    schema = "notification",
    indexes = {
        @Index(name = "idx_inapp_user_created", columnList = "user_id, created_at DESC"),
        @Index(name = "idx_inapp_user_read", columnList = "user_id, read"),
        @Index(name = "idx_inapp_notification_code", columnList = "notification_code")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InAppNotificationEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "notification_code", nullable = false, length = 50)
    private String notificationCode;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "icon", length = 100)
    private String icon;

    @Column(name = "color", length = 50)
    private String color;

    @Column(name = "action_link", length = 500)
    private String actionLink;

    @Column(name = "read", nullable = false)
    private Boolean read = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = java.util.UUID.randomUUID();
        }
        if (read == null) {
            read = false;
        }
    }
}