package io.mindoracare.notification.email.domain.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Contrato del mensaje publicado en RabbitMQ para el envío asíncrono de correos.
 *
 * <p>Este mensaje es publicado por el servicio principal
 * ({@code smms-cn-backend-notification}) y consumido por el microservicio
 * {@code astc-cn-notification-email}.</p>
 *
 * <h3>Campos</h3>
 * <table border="1">
 *   <tr><th>Campo</th><th>Tipo</th><th>Obligatorio</th><th>Descripción</th></tr>
 *   <tr>
 *     <td>{@code notificationCode}</td>
 *     <td>{@code String}</td>
 *     <td><b>SÍ</b></td>
 *     <td>
 *       Código único de la notificación (ej: {@code AST00001}, {@code ASP000001}).
 *       Se usa para obtener la plantilla ({@code email_template}) asociada
 *       desde la base de datos.
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>{@code type}</td>
 *     <td>{@code String}</td>
 *     <td><b>SÍ</b></td>
 *     <td>
 *       Tipo de notificación. Actualmente solo {@code "EMAIL"} está soportado.
 *       Reservado para futuros tipos como {@code "PUSH"}.
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>{@code userId}</td>
 *     <td>{@code String}</td>
 *     <td><b>SÍ</b></td>
 *     <td>
 *       Identificador UUID del usuario destino. Se usa para trazabilidad y logs.
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>{@code toEmail}</td>
 *     <td>{@code String}</td>
 *     <td><b>SÍ</b></td>
 *     <td>
 *       Dirección de correo electrónico del destinatario. Debe ser un email
 *       válido y no vacío. Si es {@code null} o vacío, el mensaje se descarta
 *       sin reintentar.
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>{@code toName}</td>
 *     <td>{@code String}</td>
 *     <td>No</td>
 *     <td>
 *       Nombre del destinatario para el saludo del correo. Puede ser
 *       {@code null} o vacío. Si se omite, se usa el email como nombre
 *       de visualización.
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>{@code variables}</td>
 *     <td>{@code Map<String, String>}</td>
 *     <td><b>SÍ</b></td>
 *     <td>
 *       Mapa de variables para reemplazar los placeholders {@code {{VAR}}}
 *       en la plantilla. Las claves deben coincidir exactamente con los
 *       nombres de las variables definidas en la plantilla (ej:
 *       {@code PROJECT_NAME}, {@code USER_NAME}). Puede estar vacío si
 *       la plantilla no usa variables.
 *     </td>
 *   </tr>
 * </table>
 *
 * <h3>Ejemplo de mensaje JSON</h3>
 * <pre>
 * {
 *   "notificationCode": "AST00001",
 *   "type": "EMAIL",
 *   "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
 *   "toEmail": "usuario@example.com",
 *   "toName": "Juan Pérez",
 *   "variables": {
 *     "PROJECT_NAME": "Proyecto Alpha",
 *     "CHECKIN_TIME": "09:00",
 *     "ATTENDANCE_DATE": "2026-07-06"
 *   }
 * }
 * </pre>
 *
 * <h3>Compatibilidad</h3>
 * <p>Cualquier servicio que publique un mensaje con esta estructura será
 * procesado por el microservicio de notification email, sin importar el origen.</p>
 */
@RegisterForReflection
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailNotificationMessage {

    @JsonProperty("notificationCode")
    private String notificationCode;

    @JsonProperty("type")
    private String type;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("toEmail")
    private String toEmail;

    @JsonProperty("toName")
    private String toName;

    @JsonProperty("variables")
    private Map<String, String> variables;
}
