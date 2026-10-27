package io.mindoracare.notification.email.infrastructure.mail;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Properties;

@ApplicationScoped
public class SmtpEmailSender {

    private static final Logger LOG = Logger.getLogger(SmtpEmailSender.class);

    @ConfigProperty(name = "custom.mail.host", defaultValue = "smtp.gmail.com")
    String host;

    @ConfigProperty(name = "custom.mail.port", defaultValue = "587")
    int port;

    @ConfigProperty(name = "custom.mail.username", defaultValue = "")
    String username;

    @ConfigProperty(name = "custom.mail.password", defaultValue = "")
    String password;

    @ConfigProperty(name = "custom.mail.ssl", defaultValue = "false")
    boolean sslEnabled;

    @ConfigProperty(name = "custom.mail.start-tls", defaultValue = "required")
    String startTls;

    @ConfigProperty(name = "custom.mail.mock", defaultValue = "false")
    boolean mock;

    @ConfigProperty(name = "email.sender.address")
    String senderAddress;

    @ConfigProperty(name = "email.sender.name")
    String senderName;

    public boolean send(String toEmail, String toName, String subject, String htmlBody) {
        if (toEmail == null || toEmail.isBlank()) {
            LOG.warn("Email not sent: recipient is empty");
            return false;
        }

        if (mock) {
            LOG.infof("📬 [SMTP MOCK] Pretending to send email to=%s, subject=%s", toEmail, subject);
            return true;
        }

        LOG.infof("🔌 [SMTP CONNECTING] Initializing JavaMail session for host=%s:%d, username=%s", host, port, username);

        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", String.valueOf(port));
            props.put("mail.smtp.auth", "true");
            
            // For Gmail, we need 587 + STARTTLS or 465 + SSL
            if (port == 465 || sslEnabled) {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.socketFactory.port", String.valueOf(port));
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
                if ("required".equalsIgnoreCase(startTls)) {
                    props.put("mail.smtp.starttls.required", "true");
                }
            }
            
            // Set 30-second timeouts to prevent hanging
            props.put("mail.smtp.connectiontimeout", "30000");
            props.put("mail.smtp.timeout", "30000");
            props.put("mail.smtp.writetimeout", "30000");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });

            String from = (senderName == null || senderName.isBlank())
                    ? senderAddress
                    : senderName + " <" + senderAddress + ">";

            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
            message.setSubject(subject);
            message.setContent(htmlBody, "text/html; charset=utf-8");

            LOG.infof("🚀 [SMTP DISPATCHING] Transport.send() called. Thread might block here...");
            Transport.send(message);

            LOG.infof("✅ [SMTP SUCCESS] Email successfully dispatched via standard Jakarta Mail to recipient=%s, subject=%s", toEmail, subject);
            return true;

        } catch (Exception e) {
            LOG.errorf(e, "❌ [SMTP ERROR] Failed to deliver email to recipient=%s, subject=%s. Error details: %s", toEmail, subject, e.getMessage());
            return false;
        }
    }
}
