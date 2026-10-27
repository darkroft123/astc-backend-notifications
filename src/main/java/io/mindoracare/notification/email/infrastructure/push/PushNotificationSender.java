package io.mindoracare.notification.email.infrastructure.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.annotation.PostConstruct;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class PushNotificationSender {

    private static final Logger LOG = Logger.getLogger(PushNotificationSender.class);

    @Inject
    PushNotificationConfig config;

    private FirebaseApp firebaseApp;
    private FirebaseMessaging firebaseMessaging;

    @PostConstruct
    void init() {
        if (!config.enabled) {
            LOG.info("Push notifications disabled");
            return;
        }

        try {
            GoogleCredentials credentials;
            
            if (config.serviceAccountBase64 != null && !config.serviceAccountBase64.isBlank()) {
                byte[] decoded = Base64.getDecoder().decode(config.serviceAccountBase64);
                InputStream credentialsStream = new ByteArrayInputStream(decoded);
                credentials = GoogleCredentials.fromStream(credentialsStream);
            } else if (config.serviceAccountPath != null && !config.serviceAccountPath.isBlank()) {
                credentials = GoogleCredentials.fromStream(
                    getClass().getClassLoader().getResourceAsStream(config.serviceAccountPath));
            } else {
                LOG.warn("Firebase service account not configured, push notifications will not work");
                return;
            }

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();

            firebaseApp = FirebaseApp.initializeApp(options);
            firebaseMessaging = FirebaseMessaging.getInstance(firebaseApp);
            LOG.info("Firebase initialized successfully");
        } catch (Exception e) {
            LOG.error("Failed to initialize Firebase", e);
        }
    }

    public boolean send(PushNotificationMessage message) {
        if (firebaseMessaging == null) {
            LOG.warn("Firebase not initialized, skipping push notification");
            return false;
        }

        try {
            Notification notification = Notification.builder()
                    .setTitle(message.title)
                    .setBody(message.body)
                    .setImage(message.imageUrl)
                    .build();

            Map<String, String> data = new HashMap<>();
            if (message.data != null) {
                data.putAll(message.data);
            }
            data.put("notificationCode", message.notificationCode);
            if (message.actionLink != null) {
                data.put("actionLink", message.actionLink);
            }

            Message fcmMessage = Message.builder()
                    .setToken(message.fcmToken)
                    .setNotification(notification)
                    .putAllData(data)
                    .setAndroidConfig(AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .build())
                    .setApnsConfig(ApnsConfig.builder()
                            .setAps(Aps.builder()
                                    .setContentAvailable(true)
                                    .build())
                            .build())
                    .build();

            String response = firebaseMessaging.send(fcmMessage);
            LOG.infof("Push notification sent successfully: %s", response);
            return true;

        } catch (FirebaseMessagingException e) {
            LOG.errorf("Failed to send push notification: %s", e.getMessage());
            return false;
        } catch (Exception e) {
            LOG.error("Unexpected error sending push notification", e);
            return false;
        }
    }

    public boolean sendMulticast(PushNotificationMessage message, java.util.List<String> tokens) {
        if (firebaseMessaging == null || tokens == null || tokens.isEmpty()) {
            return false;
        }

        try {
            Notification notification = Notification.builder()
                    .setTitle(message.title)
                    .setBody(message.body)
                    .setImage(message.imageUrl)
                    .build();

            Map<String, String> data = new HashMap<>();
            if (message.data != null) {
                data.putAll(message.data);
            }
            data.put("notificationCode", message.notificationCode);
            if (message.actionLink != null) {
                data.put("actionLink", message.actionLink);
            }

            MulticastMessage multicastMessage = MulticastMessage.builder()
                    .addAllTokens(tokens)
                    .setNotification(notification)
                    .putAllData(data)
                    .build();

            BatchResponse response = firebaseMessaging.sendEachForMulticast(multicastMessage);
            LOG.infof("Multicast push sent: %d/%d success", response.getSuccessCount(), tokens.size());
            return response.getSuccessCount() > 0;

        } catch (Exception e) {
            LOG.error("Failed to send multicast push notification", e);
            return false;
        }
    }

    public static class PushNotificationMessage {
        public String fcmToken;
        public String title;
        public String body;
        public String imageUrl;
        public String actionLink;
        public String notificationCode;
        public Map<String, String> data;
    }
}