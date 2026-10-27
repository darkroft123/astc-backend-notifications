package io.mindoracare.notification.email.infrastructure.push;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PushNotificationConfig {

    @ConfigProperty(name = "push.notification.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "push.firebase.service-account-base64")
    String serviceAccountBase64;

    @ConfigProperty(name = "push.firebase.service-account-path")
    String serviceAccountPath;
}