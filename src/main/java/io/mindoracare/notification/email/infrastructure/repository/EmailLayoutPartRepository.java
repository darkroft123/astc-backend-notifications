package io.mindoracare.notification.email.infrastructure.repository;

import io.mindoracare.notification.email.domain.entity.EmailLayoutPartEntity;
import io.mindoracare.notification.email.domain.enums.EmailLayoutPartType;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class EmailLayoutPartRepository implements PanacheRepositoryBase<EmailLayoutPartEntity, UUID> {

    private final ConcurrentHashMap<String, Map<EmailLayoutPartType, String>> cache = new ConcurrentHashMap<>();

    public String findContentByNameAndPartType(String name, EmailLayoutPartType partType) {
        Map<EmailLayoutPartType, String> layoutParts = findAllPartsForLayout(name);
        return layoutParts.get(partType);
    }

    /**
     * Trae BASE, MAIL y FOOTER en una sola query (IN) en lugar de 3 SELECT separados.
     * Reduce 3 roundtrips a la DB a 1 solo. Cachea el resultado.
     */
    public Map<EmailLayoutPartType, String> findAllPartsForLayout(String name) {
        return cache.computeIfAbsent(name, n -> {
            long start = System.currentTimeMillis();
            List<EmailLayoutPartType> types = List.of(
                    EmailLayoutPartType.BASE,
                    EmailLayoutPartType.MAIL,
                    EmailLayoutPartType.FOOTER);

            List<EmailLayoutPartEntity> parts = find(
                    "name = ?1 AND partType IN ?2 AND active = true", n, types).list();

            Map<EmailLayoutPartType, String> result = new EnumMap<>(EmailLayoutPartType.class);
            for (EmailLayoutPartEntity part : parts) {
                result.put(part.getPartType(), part.getContent());
            }
            long elapsed = System.currentTimeMillis() - start;
            org.jboss.logging.Logger.getLogger(EmailLayoutPartRepository.class)
                    .infof("[PERF] findAllPartsForLayout('%s') -> %d parts in %d ms (antes: 3 queries separadas, ahora cacheado)",
                            n, parts.size(), elapsed);
            return result;
        });
    }

    public void clearCache() {
        cache.clear();
    }
}
