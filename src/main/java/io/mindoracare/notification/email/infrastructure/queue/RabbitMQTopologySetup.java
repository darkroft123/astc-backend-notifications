package io.mindoracare.notification.email.infrastructure.queue;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Configura la Dead Letter Queue (DLQ) con reintento automático mediante TTL.
 *
 * <p>SmallRye Reactive Messaging declara el exchange y la cola principal con
 * {@code x-dead-letter-exchange=DLX}. Este bean corre al iniciar la aplicación
 * y configura la DLQ con los parámetros necesarios para el reintento:</p>
 *
 * <ol>
 *   <li>Crea la DLQ ({@code <queue>.dlq}) con {@code x-message-ttl} para
 *       esperar antes de reintentar</li>
 *   <li>Configura {@code x-dead-letter-exchange} en la DLQ apuntando al exchange
 *       original, para que el mensaje vuelva a la cola principal después del TTL</li>
 *   <li>Configura {@code x-dead-letter-routing-key} con el routing key original</li>
 *   <li>Enlaza la DLQ al DLX con el routing key = nombre de la cola principal</li>
 * </ol>
 *
 * <p>El consumidor verifica el contador {@code x-death} para descartar mensajes
 * que exceden {@code email.notification.max-retries}.</p>
 *
 * <p>Si la DLQ ya existe de una ejecución anterior con argumentos distintos,
 * se elimina y se recrea para evitar errores de declaración.</p>
 */
@ApplicationScoped
public class RabbitMQTopologySetup {

    private static final Logger LOG = Logger.getLogger(RabbitMQTopologySetup.class);

    @ConfigProperty(name = "rabbitmq-host", defaultValue = "localhost")
    String host;

    @ConfigProperty(name = "rabbitmq-port", defaultValue = "5672")
    int port;

    @ConfigProperty(name = "rabbitmq-username", defaultValue = "guest")
    String username;

    @ConfigProperty(name = "rabbitmq-password", defaultValue = "guest")
    String password;

    @ConfigProperty(name = "mp.messaging.incoming.email-notification-in.queue.name",
            defaultValue = "email-notification.queue")
    String queueName;

    @ConfigProperty(name = "mp.messaging.incoming.email-notification-in.exchange.name",
            defaultValue = "email.notification")
    String exchangeName;

    @ConfigProperty(name = "mp.messaging.incoming.email-notification-in.routing-keys",
            defaultValue = "email.notification")
    String routingKey;

    @ConfigProperty(name = "email.notification.dlx-exchange", defaultValue = "DLX")
    String dlxExchange;

    @ConfigProperty(name = "email.notification.dlq-ttl-ms", defaultValue = "60000")
    int dlqTtlMs;

    @ConfigProperty(name = "email.notification.parking-exchange", defaultValue = "email.notification.parking")
    String parkingExchange;

    @ConfigProperty(name = "email.notification.parking-queue", defaultValue = "email.notification.queue.parking")
    String parkingQueue;

    void onStart(@Observes StartupEvent ev) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            String dlqName = queueName + ".dlq";

            channel.exchangeDeclare(dlxExchange, "direct", true);

            Map<String, Object> dlqArgs = new HashMap<>();
            dlqArgs.put("x-message-ttl", dlqTtlMs);
            dlqArgs.put("x-dead-letter-exchange", exchangeName);
            dlqArgs.put("x-dead-letter-routing-key", routingKey);

            declareDlqSafely(channel, dlqName, dlqArgs);
            channel.queueBind(dlqName, dlxExchange, queueName);

            channel.exchangeDeclare(parkingExchange, "direct", true);
            declareQueueSafely(channel, parkingQueue);
            channel.queueBind(parkingQueue, parkingExchange, routingKey);

            LOG.infof("RabbitMQ topology configured: dlq=%s dlx=%s ttl=%dms exchange=%s routingKey=%s parking=%s",
                    dlqName, dlxExchange, dlqTtlMs, exchangeName, routingKey, parkingQueue);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to configure RabbitMQ topology");
        }
    }

    private void declareDlqSafely(Channel channel, String dlqName, Map<String, Object> args) throws IOException {
        try {
            channel.queueDeclare(dlqName, true, false, false, args);
        } catch (IOException e) {
            LOG.warnf("DLQ already exists with incompatible args, deleting and recreating: %s", dlqName);
            channel.queueDelete(dlqName);
            channel.queueDeclare(dlqName, true, false, false, args);
        }
    }

    private void declareQueueSafely(Channel channel, String queueName) throws IOException {
        try {
            channel.queueDeclare(queueName, true, false, false, null);
        } catch (IOException e) {
            LOG.warnf("Queue already exists with incompatible args, deleting and recreating: %s", queueName);
            channel.queueDelete(queueName);
            channel.queueDeclare(queueName, true, false, false, null);
        }
    }
}
