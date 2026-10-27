package io.mindoracare.notification.email.domain.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "email_template", schema = "notification")
@Getter
@Setter
public class EmailTemplateEntity {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_definition_id", nullable = false)
    private NotificationDefinitionEntity definition;

    @Column(name = "subject", length = 120)
    private String subject;

    @Column(name = "body_html", columnDefinition = "TEXT")
    private String bodyHtml;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "sender_name")
    private String senderName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
