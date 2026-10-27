package io.mindoracare.notification.email.infrastructure.repository;

import io.mindoracare.notification.email.domain.entity.EmailTemplateEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class EmailTemplateRepository implements PanacheRepositoryBase<EmailTemplateEntity, UUID> {

    private final ConcurrentHashMap<String, EmailTemplateEntity> cache = new ConcurrentHashMap<>();

    public EmailTemplateEntity findByCode(String code) {
        return cache.computeIfAbsent(code, c -> 
            find("definition.code = ?1 AND active = true", c).firstResult()
        );
    }

    public void clearCache() {
        cache.clear();
    }
}
