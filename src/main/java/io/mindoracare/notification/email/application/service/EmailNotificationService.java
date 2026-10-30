package io.mindoracare.notification.email.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindoracare.notification.email.domain.entity.EmailTemplateEntity;
import io.mindoracare.notification.email.domain.entity.InAppNotificationEntity;
import io.mindoracare.notification.email.domain.entity.PushNotificationEntity;
import io.mindoracare.notification.email.domain.enums.EmailLayoutPartType;
import io.mindoracare.notification.email.domain.model.EmailNotificationMessage;
import io.mindoracare.notification.email.infrastructure.mail.SmtpEmailSender;
import io.mindoracare.notification.email.infrastructure.repository.EmailLayoutPartRepository;
import io.mindoracare.notification.email.infrastructure.repository.EmailTemplateRepository;
import io.mindoracare.notification.email.infrastructure.repository.InAppNotificationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.commons.text.StringSubstitutor;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class EmailNotificationService {

    private static final Logger LOG = Logger.getLogger(EmailNotificationService.class);
    private static final String DEFAULT_LAYOUT = "mindora-default";

    @Inject
    EmailTemplateRepository templateRepository;

    @Inject
    EmailLayoutPartRepository layoutPartRepository;

    @Inject
    InAppNotificationRepository inAppNotificationRepository;

    @Inject
    SmtpEmailSender smtpEmailSender;

    @Inject
    io.mindoracare.notification.email.infrastructure.repository.DeviceTokenRepository deviceTokenRepository;

    @Inject
    io.mindoracare.notification.email.infrastructure.repository.PushNotificationRepository pushNotificationRepository;

    @Inject
    io.mindoracare.notification.email.infrastructure.push.FcmPushSender fcmPushSender;

    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "app.notification.cooldown-seconds", defaultValue = "0")
    long cooldownSeconds;

    // ObjectMapper reutilizable — evita crear una instancia nueva por cada dispositivo en el loop
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private java.util.concurrent.ScheduledExecutorService cacheScheduler;

    @jakarta.annotation.PostConstruct
    void initCacheEviction() {
        cacheScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        cacheScheduler.scheduleAtFixedRate(() -> {
            try {
                if (deviceTokenRepository != null) deviceTokenRepository.clearCache();
                if (templateRepository != null) templateRepository.clearCache();
                if (layoutPartRepository != null) layoutPartRepository.clearCache();
            } catch (Exception e) {
                LOG.error("Error clearing repositories caches: " + e.getMessage());
            }
        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
    }

    @jakarta.annotation.PreDestroy
    void destroyCacheEviction() {
        if (cacheScheduler != null) {
            cacheScheduler.shutdown();
        }
    }

    public boolean send(EmailNotificationMessage message) {
        if (isSpamSuppressed(message)) {
            LOG.infof("✅ [EmailService] Anti-spam suppressed duplicate notification '%s' for user %s. Acknowledging message.",
                    message.getNotificationCode(), message.getUserId());
            return true;
        }

        LOG.infof("📬 [EmailService] Resolving template for code: %s", message.getNotificationCode());
        
        EmailTemplateEntity template = null;
        try {
            template = io.quarkus.narayana.jta.QuarkusTransaction.joiningExisting().call(() -> 
                templateRepository.findByCode(message.getNotificationCode())
            );
        } catch (Exception e) {
            LOG.warnf("Could not query template repository: %s", e.getMessage());
        }

        Map<String, Object> rawVars = message.getVariables() != null
                ? message.getVariables().entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> (Object) e.getValue()))
                : new HashMap<>();

        String toName = message.getToName();
        if (toName == null || toName.isBlank()) {
            toName = message.getToEmail() != null ? message.getToEmail() : "Usuario";
        }
        rawVars.put("name", toName);
        rawVars.put("NAME", toName);
        rawVars.put("USER_NAME", toName);
        rawVars.put("toName", toName);
        rawVars.put("TO_NAME", toName);

        Map<String, Object> vars = normalizeVariables(rawVars);
        LOG.infof("📬 [EmailService] Interpolating variables: %s", vars);

        String subject;
        String rawBody;

        if (template != null) {
            LOG.infof("📬 [EmailService] Found template ID=%s. SubjectTemplate='%s'", template.getId(), template.getSubject());
            subject = replaceVariables(template.getSubject(), vars);
            rawBody = replaceVariables(template.getBodyHtml(), vars);
        } else {
            LOG.warnf("⚠️ [EmailService] Email template not found for code: %s. Using default fallback.", message.getNotificationCode());
            subject = "Notificación del Sistema - " + message.getNotificationCode();
            rawBody = "<p>Hola <strong>" + toName + "</strong>,</p><p>Tiene una nueva actualización en el sistema (" + message.getNotificationCode() + ").</p><p>Saludos,<br/>Equipo ASTC</p>";
        }

        String finalBody = io.quarkus.narayana.jta.QuarkusTransaction.joiningExisting().call(() ->
            buildHtmlBody(rawBody, vars)
        );

        LOG.infof("📬 [EmailService] Dispatching email to SMTP Sender: toEmail=%s, toName=%s, subject='%s'", 
                message.getToEmail(), message.getToName(), subject);

        // Slow network SMTP call runs outside any transaction context
        boolean emailSent = false;
        try {
            emailSent = smtpEmailSender.send(
                    message.getToEmail(),
                    message.getToName(),
                    subject,
                    finalBody
            );
        } catch (Exception e) {
            LOG.errorf(e, "❌ [EmailService] SMTP transmission exception for recipient=%s, subject='%s'", message.getToEmail(), subject);
        }

        LOG.infof("📬 [EmailService] Proceeding to persist In-App and FCM notifications...");
        String plainBody = stripHtml(rawBody);
        
        // Persist writes in separate transaction blocks for resilience
        try {
            if (isBlankUserId(message.getUserId())) {
                LOG.infof("📬 [EmailService] userId vacío, omitiendo persistencia In-App/FCM para %s", message.getToEmail());
                return true;
            }
            try {
                io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                    persistInAppNotification(message, subject, plainBody);
                });
                LOG.infof("✅ [EmailService] In-App notification successfully written to database.");
            } catch (Exception inAppEx) {
                LOG.errorf(inAppEx, "❌ [EmailService] Failed to persist In-App notification for user=%s", message.getUserId());
            }

            try {
                io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                    sendPushNotification(message, subject, plainBody);
                });
                LOG.infof("✅ [EmailService] FCM push notification successfully processed.");
            } catch (Exception pushEx) {
                LOG.errorf(pushEx, "❌ [EmailService] Failed to process FCM push for user=%s", message.getUserId());
            }
        } catch (Exception e) {
            LOG.errorf(e, "❌ [EmailService] Failed to persist In-App/FCM notifications for recipient=%s", message.getToEmail());
        }

        return emailSent;
    }

    private boolean isBlankUserId(String userId) {
        return userId == null || userId.isBlank();
    }

    /**
     * Anti-spam: si el mismo usuario ya recibi\u00f3 la misma plantilla hace menos de cooldownSeconds,
     * se omite todo el flujo (correo, in-app y push). 0 desactiva el control.
     */
    private boolean isSpamSuppressed(EmailNotificationMessage message) {
        if (cooldownSeconds <= 0 || isBlankUserId(message.getUserId())) {
            return false;
        }
        try {
            Boolean duplicated = io.quarkus.narayana.jta.QuarkusTransaction.joiningExisting().call(() ->
                inAppNotificationRepository.existsRecentNotification(
                        message.getUserId(), message.getNotificationCode(), cooldownSeconds));
            if (Boolean.TRUE.equals(duplicated)) {
                LOG.warnf("\ud83d\udeab [AntiSpam] Notificaci\u00f3n '%s' para usuario %s ya enviada en los \u00faltimos %ds. Omitiendo duplicado.",
                        message.getNotificationCode(), message.getUserId(), cooldownSeconds);
                return true;
            }
            return false;
        } catch (Exception e) {
            LOG.warnf("Anti-spam check failed (permitiendo envio): %s", e.getMessage());
            return false;
        }
    }

    private void persistInAppNotification(EmailNotificationMessage message, String subject, String body) {
        if (isBlankUserId(message.getUserId())) {
            return;
        }
        try {
            UUID userId = UUID.fromString(message.getUserId());
            String code = message.getNotificationCode();

            InAppNotificationEntity inApp = InAppNotificationEntity.builder()
                    .userId(userId)
                    .notificationCode(code)
                    .title(subject)
                    .body(body)
                    .icon(null)
                    .color(null)
                    .actionLink(null)
                    .read(false)
                    .build();
            inAppNotificationRepository.persist(inApp);
            LOG.infof("Persisted in-app notification for user %s code %s", userId, code);
        } catch (Exception e) {
            LOG.warnf("Failed to persist in-app notification: %s", e.getMessage());
        }
    }

    private void sendPushNotification(EmailNotificationMessage message, String title, String body) {
        if (isBlankUserId(message.getUserId())) {
            return;
        }
        try {
            UUID userId = UUID.fromString(message.getUserId());
            List<io.mindoracare.notification.email.domain.entity.DeviceTokenEntity> devices =
                    deviceTokenRepository.findByUserId(userId);

            if (devices == null || devices.isEmpty()) {
                LOG.infof("No device tokens registered for user %s, skipping FCM push", userId);
                return;
            }

            Map<String, String> data = message.getVariables();
            if (data == null) {
                data = new HashMap<>();
            }

            // Serializar los datos UNA sola vez fuera del loop
            String dataJson;
            try {
                dataJson = OBJECT_MAPPER.writeValueAsString(data);
            } catch (Exception e) {
                dataJson = "{}";
                LOG.warnf("Error serializando FCM data payload: %s", e.getMessage());
            }

            long start = System.currentTimeMillis();
            // Acumular entities para hacer persist en batch al final
            List<PushNotificationEntity> pushEntities = new ArrayList<>(devices.size());

            for (io.mindoracare.notification.email.domain.entity.DeviceTokenEntity device : devices) {
                boolean success = fcmPushSender.sendPush(device.getToken(), title, body, data);

                PushNotificationEntity push = PushNotificationEntity.builder()
                        .userId(userId)
                        .notificationCode(message.getNotificationCode())
                        .title(title)
                        .body(body)
                        .icon(null)
                        .image(null)
                        .actionLink(null)
                        .data(dataJson)
                        .sent(success)
                        .sentAt(java.time.Instant.now())
                        .errorMessage(success ? null : "FCM sending failed. Check container logs.")
                        .build();

                pushEntities.add(push);
            }

            // Un solo persist en batch en lugar de N persist uno por uno
            pushNotificationRepository.persist(pushEntities);
            long elapsed = System.currentTimeMillis() - start;
            LOG.infof("[PERF] sendPushNotification -> %d dispositivos procesados en batch en %d ms (antes: %d inserts individuales)",
                    pushEntities.size(), elapsed, pushEntities.size());

        } catch (Exception e) {
            LOG.warnf("Failed to process FCM push notification: %s", e.getMessage());
        }
    }

    private static final Map<String, String> SPANISH_TRANSLATIONS = new HashMap<>();
    static {
        // Roles (exclusivamente: "Admin", "Jefe de Proyecto", "Trabajador", sin otros nombres)
        SPANISH_TRANSLATIONS.put("SUPER_ADMIN", "Admin");
        SPANISH_TRANSLATIONS.put("ADMIN", "Admin");
        SPANISH_TRANSLATIONS.put("ADMINISTRATOR", "Admin");
        SPANISH_TRANSLATIONS.put("PROJECT_MANAGER", "Jefe de Proyecto");
        SPANISH_TRANSLATIONS.put("PROJECT MANAGER", "Jefe de Proyecto");
        SPANISH_TRANSLATIONS.put("MANAGER", "Jefe de Proyecto");
        SPANISH_TRANSLATIONS.put("LEAD", "Jefe de Proyecto");
        SPANISH_TRANSLATIONS.put("TEAM_MEMBER", "Trabajador");
        SPANISH_TRANSLATIONS.put("TEAM MEMBER", "Trabajador");
        SPANISH_TRANSLATIONS.put("DEVELOPER", "Trabajador");
        SPANISH_TRANSLATIONS.put("MIEMBRO DEL EQUIPO", "Trabajador");
        SPANISH_TRANSLATIONS.put("MIEMBRO", "Trabajador");
        SPANISH_TRANSLATIONS.put("MEMBER", "Trabajador");
        SPANISH_TRANSLATIONS.put("USER", "Trabajador");

        // Asistencia Estados
        SPANISH_TRANSLATIONS.put("OUTSIDE_SCHEDULE_CHECKED_OUT", "Salida fuera de horario");
        SPANISH_TRANSLATIONS.put("OUTSIDE_SCHEDULE_CHECKED_IN", "Ingreso fuera de horario");
        SPANISH_TRANSLATIONS.put("ON_TIME", "Puntual");
        SPANISH_TRANSLATIONS.put("PRESENT", "Puntual / Presente");
        SPANISH_TRANSLATIONS.put("LATE", "Tardanza");
        SPANISH_TRANSLATIONS.put("EARLY_DEPARTURE", "Salida anticipada");
        SPANISH_TRANSLATIONS.put("ABSENT", "Inasistencia / Falta");
        SPANISH_TRANSLATIONS.put("JUSTIFIED", "Justificado");
        SPANISH_TRANSLATIONS.put("NOT_SCHEDULED", "Sin horario programado");

        // Alertas Estados / Tipos
        SPANISH_TRANSLATIONS.put("NO_PHOTO", "Sin fotografía");
        SPANISH_TRANSLATIONS.put("LOCATION_MISMATCH", "Ubicación fuera de rango");
        SPANISH_TRANSLATIONS.put("FACE_MISMATCH", "Rostro no coincide");
        SPANISH_TRANSLATIONS.put("UNKNOWN_DEVICE", "Dispositivo no reconocido");
        SPANISH_TRANSLATIONS.put("PENDING", "Pendiente");
        SPANISH_TRANSLATIONS.put("APPROVED", "Aprobada");
        SPANISH_TRANSLATIONS.put("REJECTED", "Rechazada");
        SPANISH_TRANSLATIONS.put("CANCELLED", "Cancelada");
        SPANISH_TRANSLATIONS.put("OBSERVED", "Observada");

        // Vacaciones Estados
        SPANISH_TRANSLATIONS.put("REQUESTED", "Solicitadas");
        SPANISH_TRANSLATIONS.put("FAILED", "Fallido");
        SPANISH_TRANSLATIONS.put("SUCCESS", "Exitoso");
    }

    private static String translateToSpanish(String val) {
        if (val == null) return null;
        String upper = val.trim().toUpperCase();
        return SPANISH_TRANSLATIONS.getOrDefault(upper, val);
    }

    private String buildHtmlBody(String rawBody, Map<String, Object> vars) {
        long start = System.currentTimeMillis();

        // Una sola query IN (BASE, MAIL, FOOTER) en lugar de 3 SELECT separados
        Map<EmailLayoutPartType, String> parts = layoutPartRepository.findAllPartsForLayout(DEFAULT_LAYOUT);

        String baseContent = parts.get(EmailLayoutPartType.BASE);
        if (baseContent == null) {
            LOG.warnf("BASE layout part not found for layout '%s', using raw body", DEFAULT_LAYOUT);
            return rawBody;
        }

        String mailContent  = parts.get(EmailLayoutPartType.MAIL);
        String footerContent = parts.get(EmailLayoutPartType.FOOTER);

        String wrappedContent = (mailContent != null)
                ? replaceVariables(mailContent, Map.of("CONTENT", rawBody))
                : rawBody;

        Map<String, Object> baseVars = new HashMap<>(vars);
        baseVars.put("EMAIL_CONTENT", wrappedContent);
        baseVars.put("FOOTER", footerContent != null ? footerContent : "");

        String result = replaceVariables(baseContent, baseVars);

        // Garantizar que cualquier tabla quede envuelta en un contenedor responsive
        if (result != null && result.contains("<table") && !result.contains("table-responsive")) {
            result = result.replaceAll("(?i)(<table[^>]*>)", "<div class=\"table-responsive\" style=\"width:100%;max-width:100%;overflow-x:auto;-webkit-overflow-scrolling:touch;margin:16px 0;\">$1")
                           .replaceAll("(?i)(</table>)", "$1</div>");
        }

        long elapsed = System.currentTimeMillis() - start;
        LOG.infof("[PERF] buildHtmlBody completado en %d ms (3 queries -> 1 query batch)", elapsed);
        return result;
    }

    private String replaceVariables(String text, Map<String, Object> vars) {
        if (text == null || vars == null || vars.isEmpty()) return text;
        String replaced = StringSubstitutor.replace(text, vars, "{", "}");
        return StringSubstitutor.replace(replaced, vars, "{{", "}}");
    }

    private String camelToScreamingSnake(String camel) {
        if (camel == null) return null;
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }

    private Map<String, Object> normalizeVariables(Map<String, Object> rawVars) {
        Map<String, Object> normalized = new HashMap<>();
        for (Map.Entry<String, Object> entry : rawVars.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || value == null) continue;

            if (value instanceof String strVal) {
                value = translateToSpanish(strVal);
            }

            normalized.put(key, value);
            normalized.put(key.toUpperCase(), value);
            normalized.put(key.toLowerCase(), value);

            String snake = camelToScreamingSnake(key);
            normalized.put(snake, value);
            normalized.put(snake.toLowerCase(), value);

            // Canonical mapping for role fields
            if ("role".equalsIgnoreCase(key) || "newRole".equalsIgnoreCase(key) 
                    || "memberRole".equalsIgnoreCase(key) || "roleName".equalsIgnoreCase(key)
                    || "role_name".equalsIgnoreCase(key)) {
                normalized.put("ROLE_NAME", value);
                normalized.put("NEW_USER_ROLE", value);
                normalized.put("role_name", value);
            }

            // Canonical mapping for name fields
            if ("name".equalsIgnoreCase(key)) {
                normalized.put("PROJECT_NAME", value);
                normalized.put("USER_NAME", value);
            }
            if ("projectName".equalsIgnoreCase(key) || "project_name".equalsIgnoreCase(key)) {
                normalized.put("PROJECT_NAME", value);
            }
            if ("userName".equalsIgnoreCase(key) || "user_name".equalsIgnoreCase(key)) {
                normalized.put("USER_NAME", value);
            }
            if ("comment".equalsIgnoreCase(key)) {
                normalized.put("REVIEW_COMMENT", value);
            }
            if ("reason".equalsIgnoreCase(key)) {
                normalized.put("JUSTIFICATION_REASON", value);
            }
        }

        // Hostname y Link
        Object linkObj = normalized.get("LINK");
        if (linkObj == null || linkObj.toString().isBlank()) {
            linkObj = "https://astc-auth.joyit.io";
            normalized.put("LINK", linkObj);
            normalized.put("link", linkObj);
        }
        String linkStr = linkObj.toString();
        String hostname = "astc-auth.joyit.io";
        try {
            java.net.URI uri = java.net.URI.create(linkStr);
            if (uri.getHost() != null) {
                hostname = uri.getHost();
            }
        } catch (Exception ignored) {}
        normalized.put("HOSTNAME", hostname);
        normalized.put("hostname", hostname);
        normalized.put("LINK_HOSTNAME", hostname);

        return normalized;
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        // Conservar enlaces mostrando el texto y la URL
        String text = html.replaceAll("(?i)<a\\s+[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", "$2 ($1)")
                          .replaceAll("(?i)<br\\s*/?>", "\n")
                          .replaceAll("(?i)</p>", "\n\n")
                          .replaceAll("(?i)</tr>", "\n")
                          .replaceAll("(?i)</th>", ": ")
                          .replaceAll("(?i)</td>\\s*<td[^>]*>", ": ")
                          .replaceAll("(?i)</td>", " ")
                          .replaceAll("(?i)</li>", "\n")
                          .replaceAll("<[^>]*>", "")
                          .replaceAll("[ \\t]+", " ")
                          .replaceAll("(?m)^[ \\t]+|[ \\t]+$", "")
                          .replaceAll("\\n{3,}", "\n\n");
        return text.replace("&nbsp;", " ")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .trim();
    }
}
