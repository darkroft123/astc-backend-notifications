package io.mindoracare.notification.email.infrastructure.queue;

import io.mindoracare.notification.email.application.service.EmailNotificationService;
import io.mindoracare.notification.email.domain.model.EmailNotificationMessage;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class EmailNotificationConsumer {

    private static final Logger LOG = Logger.getLogger(EmailNotificationConsumer.class);

    @Inject
    EmailNotificationService emailNotificationService;

    @Channel("email-notification-parking")
    @Inject
    Emitter<String> parkingEmitter;

    @ConfigProperty(name = "email.notification.max-retries", defaultValue = "3")
    int maxRetries;

    @Incoming("email-notification-in")
    @Blocking
    public CompletionStage<Void> consume(Message<Object> raw) {
        String body = null;
        try {
            Object payloadRaw = raw.getPayload();
            if (payloadRaw instanceof byte[]) {
                body = new String((byte[]) payloadRaw, StandardCharsets.UTF_8);
            } else if (payloadRaw instanceof String) {
                body = (String) payloadRaw;
            } else {
                body = payloadRaw.toString();
            }

            if (exceedsMaxRetries(raw)) {
                LOG.errorf("💀 [Consumer] Message exceeded max retries (%d), parking for manual review", maxRetries);
                parkingEmitter.send(body);
                return acknowledge(raw);
            }

            JsonObject payload = new JsonObject(body);
            LOG.infof("📥 [Consumer] RECEIVED MESSAGE FROM RABBITMQ: %s", payload.encodePrettily());

            EmailNotificationMessage notificationMessage = payload.mapTo(EmailNotificationMessage.class);

            if (notificationMessage.getToEmail() == null || notificationMessage.getToEmail().isBlank()) {
                if (notificationMessage.getUserId() != null && !notificationMessage.getUserId().isBlank()) {
                    notificationMessage.setToEmail("user-" + notificationMessage.getUserId() + "@astc.local");
                } else {
                    LOG.warnf("Skipping message with empty toEmail and empty userId: code=%s", notificationMessage.getNotificationCode());
                    return acknowledge(raw);
                }
            }

            LOG.infof("Processing email notification: code=%s to=%s",
                    notificationMessage.getNotificationCode(), notificationMessage.getToEmail());

            boolean success = emailNotificationService.send(notificationMessage);

            if (success) {
                LOG.infof("Email notification sent: code=%s to=%s",
                        notificationMessage.getNotificationCode(), notificationMessage.getToEmail());
                LOG.infof("[Consumer] ACK explicito OK");
            } else {
                LOG.errorf("Email notification send returned false: code=%s to=%s",
                        notificationMessage.getNotificationCode(), notificationMessage.getToEmail());
                parkingEmitter.send(body);
                return acknowledge(raw);
            }

            return acknowledge(raw);

        } catch (Exception e) {
            LOG.errorf(e, "Error processing email notification: %s", e.getMessage());
            try {
                if (body != null) {
                    parkingEmitter.send(body);
                }
            } catch (Exception parkError) {
                LOG.errorf(parkError, "Could not park message after processing failure");
            }
            return acknowledge(raw);
        }
    }

    /**
     * Confirmacion explicita del mensaje a RabbitMQ. Sin esto el mensaje queda
     * en estado unacked y RabbitMQ lo reentrega cada consumer timeout (30 min).
     */
    private CompletionStage<Void> acknowledge(Message<Object> raw) {
        return raw.ack();
    }

    private boolean exceedsMaxRetries(Message<Object> message) {
        if (maxRetries <= 0) {
            return false;
        }
        java.util.Optional<IncomingRabbitMQMetadata> metadata =
                message.getMetadata(IncomingRabbitMQMetadata.class);
        if (metadata.isEmpty()) {
            return false;
        }
        List<?> xDeath = metadata.get().getHeader("x-death", List.class).orElse(List.of());
        if (xDeath.isEmpty()) {
            return false;
        }
        long totalDeliveries = 0;
        for (Object entry : xDeath) {
            if (entry instanceof Map<?, ?>) {
                Object count = ((Map<?, ?>) entry).get("count");
                if (count instanceof Number) {
                    totalDeliveries += ((Number) count).longValue();
                }
            }
        }
        return totalDeliveries >= maxRetries;
    }
}
