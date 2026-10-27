package io.mindoracare.notification.email.infrastructure.repository;

import io.mindoracare.notification.email.domain.entity.InAppNotificationEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class InAppNotificationRepository implements PanacheRepositoryBase<InAppNotificationEntity, UUID> {

    public List<InAppNotificationEntity> findByUserId(UUID userId, int page, int size) {
        return find("userId = ?1 ORDER BY createdAt DESC", userId)
                .page(page, size)
                .list();
    }

    public long countUnreadByUserId(UUID userId) {
        return count("userId = ?1 AND read = false", userId);
    }

    public boolean existsRecentNotification(String userId, String code, long seconds) {
        java.time.Instant since = java.time.Instant.now().minusSeconds(seconds);
        return count("userId = ?1 AND notificationCode = ?2 AND createdAt > ?3",
                UUID.fromString(userId), code, since) > 0;
    }
}