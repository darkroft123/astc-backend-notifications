package io.mindoracare.notification.email.infrastructure.repository;

import io.mindoracare.notification.email.domain.entity.DeviceTokenEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DeviceTokenRepository implements PanacheRepositoryBase<DeviceTokenEntity, UUID> {

    private final ConcurrentHashMap<UUID, List<DeviceTokenEntity>> cache = new ConcurrentHashMap<>();

    public List<DeviceTokenEntity> findByUserId(UUID userId) {
        return cache.computeIfAbsent(userId, uid -> find("userId = ?1", uid).list());
    }

    public DeviceTokenEntity findByToken(String token) {
        return find("token = ?1", token).firstResult();
    }

    public void clearCache() {
        cache.clear();
    }
}
