package io.mindoracare.notification.email.infrastructure.repository;

import io.mindoracare.notification.email.domain.entity.PushNotificationEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PushNotificationRepository implements PanacheRepositoryBase<PushNotificationEntity, UUID> {

    public List<PushNotificationEntity> findByUserId(UUID userId, int page, int size) {
        return find("userId = ?1 ORDER BY createdAt DESC", userId)
                .page(page, size)
                .list();
    }

    public long countUnsentByUserId(UUID userId) {
        return count("userId = ?1 AND sent = false", userId);
    }
}