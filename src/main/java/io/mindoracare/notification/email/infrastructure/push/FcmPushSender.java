package io.mindoracare.notification.email.infrastructure.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Startup
@ApplicationScoped
public class FcmPushSender {

    private static final Logger LOG = Logger.getLogger(FcmPushSender.class);

    @ConfigProperty(name = "push.google.service-account")
    java.util.Optional<String> googleServiceAccount;

    @ConfigProperty(name = "quarkus.mailer.host", defaultValue = "not-configured")
    String mailHost;

    @ConfigProperty(name = "quarkus.mailer.port", defaultValue = "0")
    int mailPort;

    @ConfigProperty(name = "quarkus.mailer.username", defaultValue = "not-configured")
    String mailUsername;

    @ConfigProperty(name = "quarkus.mailer.mock", defaultValue = "false")
    String mailMock;

    @ConfigProperty(name = "email.sender.address", defaultValue = "not-configured")
    String mailSender;

    private boolean enabled = false;

    @PostConstruct
    void init() {
        LOG.info("==================================================================");
        LOG.info("📢 DETECTING NOTIFICATION CONFIGURATION VARIABLES AT STARTUP:");
        LOG.infof("   - SMTP Host: %s", mailHost);
        LOG.infof("   - SMTP Port: %d", mailPort);
        LOG.infof("   - SMTP User: %s", mailUsername);
        LOG.infof("   - SMTP Mock (Simulator): %s", mailMock);
        LOG.infof("   - Email Sender Address: %s", mailSender);
        
        String sa = System.getProperty("push.google.service-account");
        LOG.infof("   - Firebase Service Account (Sys Property): %s", sa != null ? "PRESENT (length=" + sa.length() + ")" : "ABSENT");
        
        String envSa = System.getenv("GOOGLE_SERVICE_ACCOUNT");
        LOG.infof("   - Firebase Service Account (Env Var): %s", envSa != null ? "PRESENT (length=" + envSa.length() + ")" : "ABSENT");
        
        String configSa = googleServiceAccount.orElse(null);
        LOG.infof("   - Firebase Service Account (Config Prop): %s", configSa != null ? "PRESENT (length=" + configSa.length() + ")" : "ABSENT");
        LOG.info("==================================================================");

        try {
            if (sa == null || sa.isBlank()) sa = configSa;
            if (sa == null || sa.isBlank()) sa = envSa;

            if (sa != null && !sa.isBlank()) {
                InputStream stream;
                if (sa.trim().startsWith("{")) {
                    LOG.info("FcmPushSender: Parsing Google Service Account JSON directly...");
                    stream = new ByteArrayInputStream(sa.trim().getBytes(StandardCharsets.UTF_8));
                } else {
                    String trimmedPath = sa.trim();
                    LOG.infof("FcmPushSender: Loading Google Service Account file from path='%s'...", trimmedPath);
                    stream = new FileInputStream(trimmedPath);
                }

                try (stream) {
                    FirebaseOptions options = FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(stream))
                            .build();
                    FirebaseApp.initializeApp(options);
                    enabled = true;
                    LOG.info("✅ Firebase Cloud Messaging initialized successfully (REAL CONNECTION ACTIVE)");
                }
            } else {
                LOG.warn("⚠ FcmPushSender: No credentials configured. Running in MOCK/disabled mode.");
            }
        } catch (Exception e) {
            LOG.errorf("❌ Failed to initialize FcmPushSender: %s", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean sendPush(String token, String title, String body, Map<String, String> dataMap) {
        if (!enabled) {
            LOG.infof("[MOCK FCM PUSH] Target Token: %s | Title: %s | Body: %s | Data: %s", token, title, body, dataMap);
            return true;
        }

        try {
            Message.Builder builder = Message.builder()
                    .setToken(token.trim())
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build());

            if (dataMap != null && !dataMap.isEmpty()) {
                builder.putAllData(dataMap);
            }

            String response = FirebaseMessaging.getInstance().send(builder.build());
            LOG.infof("FCM Push sent successfully. Response name: %s", response);
            return true;
        } catch (Exception e) {
            LOG.errorf("Failed to send FCM Push to token %s: %s", token, e.getMessage());
            return false;
        }
    }
}
