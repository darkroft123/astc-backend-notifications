package io.mindoracare.notification.email.domain.entity;

import java.util.UUID;

import io.mindoracare.notification.email.domain.enums.EmailLayoutPartType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "email_layout_part", schema = "notification")
@Getter
@Setter
public class EmailLayoutPartEntity {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "part_type", nullable = false, length = 20)
    private EmailLayoutPartType partType;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
