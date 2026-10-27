# ASTC CN Notification Email

Microservicio independiente de envío de correos electrónicos mediante SMTP.
Consume mensajes de RabbitMQ, resuelve plantillas desde la base de datos,
reemplaza variables y envía el correo.

## Stack

- Java 21 + Quarkus 3.x
- Hibernate ORM + Panache (bloqueante)
- SmallRye Reactive Messaging (RabbitMQ consumer)
- Quarkus Mailer (SMTP)
- Apache Commons Text (`StringSubstitutor` con `{{}}`)

## Arquitectura

```
smms-cn-backend-notification              astc-cn-notification-email
┌────────────────────────────┐            ┌──────────────────────────────────┐
│ ProcessNotification        │            │ RabbitMQTopologySetup            │
│ UseCaseImpl                │            │  (configura DLQ con TTL + retry) │
│                            │ ┌────────┐ │                                  │
│ email-notification-out ────┼►│RabbitMQ│◄┤ EmailNotificationConsumer        │
│ (Emisor)                   │ │        │ │  (Message<JsonObject>)           │
│ exchange=email.notification│ │        │ │         │                        │
│ rk=email.notification      │ └────────┘ │         ▼                        │
│                            │            │ EmailNotificationService         │
└────────────────────────────┘            │  (resuelve plantilla,            │
                                          │   reemplaza vars)                │
                                          │         │                        │
                                          │         ▼                        │
                                          │ SmtpEmailSender                  │
                                          │  (Quarkus Mailer → SMTP)         │
                                          └──────────────────────────────────┘
```

## Flujo completo

### 1. Frontend → GraphQL → Outbox

El frontend envía una mutación GraphQL al servicio **assistance**:

| Mutación | Archivo frontend | Use case backend | Outbox event |
|----------|-----------------|------------------|-------------|
| `RegisterAttendance` | `frontend-assistance/app/services/assistance.service.ts:191` | `RegisterAttendanceUseCase.java:201` | `aggregateType="Attendance"`, `eventType="attendance_registered"` |
| `SaveJustification` | `frontend-assistance/app/services/assistance.service.ts:314` | `SaveJustificationUseCase.java:94` | `aggregateType="Justification"`, `eventType="SendNotificationByQueue"` |
| `JustifyAbsence` | `frontend-assistance/app/services/assistance.service.ts:581` | `JustifyAbsenceUseCase.java:37` | **Comentado** — no publica outbox |

El `OutboxEventPublisher` (en `assistance/infrastructure/messaging/OutboxEventPublisher.java:20`) escribe en la tabla `outbox_events` con `published=false`.

**⚠ Estado actual**: El relay Outbox → RabbitMQ **no está implementado**. La tabla `outbox_events` almacena los eventos pero no hay un `@Scheduled` que los lea y publique al exchange `notification.event.exchange`.

Para pruebas, se debe publicar directamente en RabbitMQ (ver sección de pruebas más abajo).

### 2. Notification Service (smms-cn-backend-notification)

Cuando un mensaje llega a `notification.event.exchange` (rk: `notification.event.key`):

- **Consumer**: `NotificationConsumer.java:24` (`@Incoming("notification-event-in")`)
- **Payload**: `NotificationMessage` con `userId`, `templateCode`, `variables`
- **Procesador**: `ProcessNotificationUseCaseImpl.java:60` — consulta `notification_definition` + `email_template` flags, verifica preferencias del usuario, y enruta a 5 canales.

#### Canales de salida

| Canal | Exchange | Routing Key | Servicio destino |
|-------|----------|-------------|-----------------|
| `email-notification-out` (legacy) | `notification-internal` | `email` | `EmailNotificationConsumer` (Brevo/SendGrid/SQS) |
| `email-notification-out` (nuevo) | `email.notification` | `email.notification` | `EmailNotificationConsumer` (SMTP) |
| `push-notification-out` | `notification-internal` | `push` | Push service |
| `inapp-notification-out` | `notification-internal` | `inapp` | In-app service |
| `support-mailbox-email-out` | `notification-internal` | `support-mailbox` | Support mailbox |

### 3. Email Notification (este microservicio)

- **Consumer**: `EmailNotificationConsumer.java:31` (`@Incoming("email-notification-in")`)
- **Payload**: `EmailNotificationMessage` — ver contrato abajo
- **Service**: `EmailNotificationService.java:35` — busca plantilla por `notificationCode`, reemplaza `{{VAR}}` con `StringSubstitutor`, ensambla layout HTML
- **SMTP**: `SmtpEmailSender.java:31` — envía vía Quarkus Mailer

## Contrato del mensaje (`EmailNotificationMessage`)

```json
{
  "notificationCode": "AST00001",
  "type": "EMAIL",
  "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "toEmail": "usuario@example.com",
  "toName": "Juan Pérez",
  "variables": {
    "PROJECT_NAME": "Proyecto Alpha",
    "CHECKIN_TIME": "09:00",
    "ATTENDANCE_DATE": "2026-07-06"
  }
}
```

| Campo | Tipo | Obligatorio | Descripción |
|-------|------|-------------|-------------|
| `notificationCode` | String | **SÍ** | Código de notificación (ej: `AST00001`). Busca plantilla en `email_template`. |
| `type` | String | **SÍ** | `"EMAIL"`. Reservado para `"PUSH"`. |
| `userId` | String | **SÍ** | UUID del usuario destino. |
| `toEmail` | String | **SÍ** | Email destino. Vacío → descarta sin reintentar. |
| `toName` | String | No | Nombre del destinatario. |
| `variables` | Map<String,String> | **SÍ** | Valores para `{{VAR}}` en la plantilla. |

## Variables de Entorno

### Database
| Variable | Default | Descripción |
|----------|---------|-------------|
| `DB_HOST` | `postgres` | Host PostgreSQL |
| `DB_PORT` | `5432` | Puerto |
| `DB_NAME` | `app` | Base de datos |
| `DB_USERNAME` | `postgres` | Usuario |
| `DB_PASSWORD` | `postgres123` | Contraseña |

### RabbitMQ
| Variable | Default | Descripción |
|----------|---------|-------------|
| `RABBITMQ_HOST` | `localhost` | Host |
| `RABBITMQ_PORT` | `5672` | Puerto |
| `RABBITMQ_USER` | `guest` | Usuario |
| `RABBITMQ_PASS` | `guest` | Contraseña |
| `RABBITMQ_EXCHANGE_NAME` | `email.notification` | Exchange de entrada |
| `RABBITMQ_QUEUE_NAME` | `email-notification.queue` | Cola de entrada |
| `RABBITMQ_ROUTING_KEY` | `email.notification` | Routing key |
| `RABBITMQ_DLX_EXCHANGE` | `DLX` | Exchange dead-letter (compartido) |

### Retry
| Variable | Default | Descripción |
|----------|---------|-------------|
| `EMAIL_NOTIFICATION_MAX_RETRIES` | `3` | Intentos máximos antes de descartar |
| `EMAIL_NOTIFICATION_DLQ_TTL_MS` | `60000` | Ms en DLQ antes de reintentar |

### SMTP
| Variable | Default | Descripción |
|----------|---------|-------------|
| `SMTP_HOST` | `localhost` | Servidor SMTP |
| `SMTP_PORT` | `587` | Puerto |
| `SMTP_USER` | _(vacío)_ | Usuario |
| `SMTP_PASS` | _(vacío)_ | Contraseña |
| `SMTP_SSL` | `false` | SSL |
| `SMTP_TLS` | `true` | STARTTLS |
| `SMTP_MOCK` | `false` | Mock para pruebas |

### Remitente
| Variable | Default |
|----------|---------|
| `EMAIL_SENDER` | `noreply@astc.com` |
| `EMAIL_SENDER_NAME` | `ASTC - Sistema de Asistencia` |

## Migración (evitar duplicados)

El servicio principal publica el mismo correo en **dos canales simultáneamente**:

| Canal | Proveedor | Control |
|-------|-----------|---------|
| `notification-internal` (rk: `email`) | Brevo / SendGrid / SQS (legacy) | `EMAIL_INTERNAL_DELIVERY_ENABLED=false` |
| `email.notification` (rk: `email.notification`) | SMTP directo (nuevo) | `EMAIL_NOTIFICATION_ENABLED=false` |

**Fases recomendadas:**
1. Dual delivery — ambos activos, validar
2. `EMAIL_INTERNAL_DELIVERY_ENABLED=false` — solo SMTP
3. Limpiar canal legacy

## Guía de prueba completa (end-to-end)

A continuación se detalla cómo probar el flujo completo desde el frontend
hasta la entrega del correo, con referencias exactas al código.

---

### Prerrequisitos

```bash
# 1. Iniciar infraestructura
docker compose up -d postgres rabbitmq

# 2. Iniciar los servicios en dev mode (3 terminales)
cd services/smms-cn-backend-notification && ./mvnw quarkus:dev   # puerto 8080
cd services/assistance && ./mvnw quarkus:dev                     # puerto 8082
cd services/astc-cn-notification-email && ./mvnw quarkus:dev    # puerto 8086

# 3. Verificar health
curl http://localhost:8080/q/health
curl http://localhost:8082/q/health
curl http://localhost:8086/q/health

# 4. Consola RabbitMQ: http://localhost:15672 (guest/guest)
```

---

### Paso 1: Acción en el frontend

En la aplicación frontend-assistance:

1. Ir al dashboard del Team Member
2. Hacer clic en **"Registrar Asistencia"** (botón de check-in)

Esto ejecuta la mutación `RegisterAttendance` definida en:
- `frontend-assistance/app/services/assistance.service.ts:191`

---

### Paso 2: Mutación GraphQL ejecutada

La mutación enviada al backend es:
```graphql
mutation RegisterAttendance($input: RegisterAttendanceInput!) {
  registerAttendance(input: $input) {
    id
  }
}
```

Con payload:
```json
{
  "input": {
    "projectId": "uuid-del-proyecto",
    "latitude": -12.0464,
    "longitude": -77.0428,
    "photo": "base64..."
  }
}
```

**Endpoint**: `http://localhost:8082/graphql`

---

### Paso 3: Microservicio que recibe la solicitud

**Servicio**: `assistance`  
**Resolver**: `AssistanceMutationResolver.java` → `registerAttendance()`  
**Use case**: `RegisterAttendanceUseCase.java` → `execute()`

Archivos:
- `services/assistance/src/main/java/com/smms/assistance/infrastructure/controller/graphql/AssistanceMutationResolver.java`
- `services/assistance/src/main/java/com/smms/assistance/application/usecase/RegisterAttendanceUseCase.java`

---

### Paso 4: Creación del evento outbox

En `RegisterAttendanceUseCase.java:201`:
```java
outboxPublisher.publish(
    "Attendance",
    entity.getId(),
    "attendance_registered",
    "{\"attendanceId\":\"...\",\"userId\":\"...\",\"photoUrl\":\"...\"}"
);
```

Que escribe en la tabla `outbox_events` con `published=false`.

**Archivo**: `services/assistance/src/main/java/com/smms/assistance/infrastructure/messaging/OutboxEventPublisher.java:20`

**⚠ Importante**: Actualmente **no existe un relay** que lea de `outbox_events` y publique a RabbitMQ. Para las pruebas, saltar al **Paso 5 alternativo** (publicación directa).

---

### Paso 5 alternativo: Publicar directamente en RabbitMQ

Dado que el outbox relay no está implementado, hay que publicar el mensaje
directamente en el exchange `notification.event.exchange`.

#### Opción A: Consola RabbitMQ

1. Ir a http://localhost:15672 (guest/guest)
2. **Exchanges** → `notification.event.exchange`
3. **Publish message**:
   - Routing key: `notification.event.key`
   - Headers: _(vacío)_
   - Payload:
```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "templateCode": "AST00001",
  "variables": {
    "USER_NAME": "Juan Pérez",
    "PROJECT_NAME": "Proyecto Alpha",
    "CHECKIN_TIME": "09:00",
    "ATTENDANCE_DATE": "2026-07-06"
  }
}
```

#### Opción B: Línea de comandos (curl + RabbitMQ HTTP API)

```bash
curl -u guest:guest -X POST http://localhost:15672/api/exchanges/%2F/notification.event.exchange/publish \
  -H "Content-Type: application/json" \
  -d '{
    "properties": {},
    "routing_key": "notification.event.key",
    "payload": "{\"userId\":\"550e8400-e29b-41d4-a716-446655440000\",\"templateCode\":\"AST00001\",\"variables\":{\"USER_NAME\":\"Juan Pérez\",\"PROJECT_NAME\":\"Proyecto Alpha\",\"CHECKIN_TIME\":\"09:00\",\"ATTENDANCE_DATE\":\"2026-07-06\"}}",
    "payload_encoding": "string"
  }'
```

---

### Paso 6: Verificar que el mensaje llegó a RabbitMQ

**Consola RabbitMQ**:

1. Ir a **Queues** → `notification.event.queue`
2. Verificar que `Ready` aumentó en 1
3. Hacer clic en la cola → **Get messages** para ver el contenido

**Logs del notification service** (`smms-cn-backend-notification`):
```
INFO Dispatching notification for user 550e8400-e29b-41d4-a716-446655440000 via queues
```

---

### Paso 7: Verificar que el notification service procesó el mensaje

El mensaje es recibido por:

**Archivo**: `services/smms-cn-backend-notification/src/main/java/io/mindoracare/notification/infrastructure/queue/NotificationConsumer.java:24`
```java
@Incoming("notification-event-in")
```

Que llama a `ProcessNotificationUseCaseImpl.process()`.

Si la notificación tiene `emailTplActive=true` y el usuario tiene email habilitado,
se publica al exchange `email.notification`.

**Log esperado**:
```
INFO Enqueuing email notification for code=AST00001 user=550e8400...
```

---

### Paso 8: Verificar que el nuevo microservicio consumió el mensaje

**Log en `astc-cn-notification-email`**:
```
INFO Processing email notification: code=AST00001 to=usuario@example.com attempt=1/3
```

**Archivo**: `services/astc-cn-notification-email/src/main/java/io/mindoracare/notification/email/infrastructure/queue/EmailNotificationConsumer.java:57`

---

### Paso 9: Verificar plantilla encontrada y variables reemplazadas

**Log esperado** (solo si `LOG_LEVEL=DEBUG`):
```
DEBUG Email template found for code=AST00001
DEBUG Variables substituted: {USER_NAME=Juan Pérez, PROJECT_NAME=Proyecto Alpha, ...}
```

**Archivo**: `services/astc-cn-notification-email/src/main/java/io/mindoracare/notification/email/application/service/EmailNotificationService.java:36`

Si la plantilla **no** se encuentra:
```
WARN  Email template not found for code: AST00001
```
→ El método retorna `false` → NACK → reintento.

---

### Paso 10: Verificar envío SMTP exitoso

**Log esperado**:
```
INFO Email sent successfully to=usuario@example.com subject=Tu asistencia en Proyecto Alpha
```

**Archivo**: `services/astc-cn-notification-email/src/main/java/io/mindoracare/notification/email/infrastructure/mail/SmtpEmailSender.java:35`

Si el envío falla:
```
ERROR Failed to send email to=usuario@example.com: Connection refused
```
→ `send()` retorna `false` → `EmailNotificationService.send()` retorna `false` → NACK → DLQ → reintento.

---

### Paso 11: Verificar reintentos y DLQ

#### Escenario: SMTP caído

1. Configurar `SMTP_HOST=localhost` y `SMTP_PORT=9999` (puerto inexistente)
2. Publicar un mensaje en `email.notification`
3. Observar en RabbitMQ:

   **Cola `email-notification.queue`**: El mensaje aparece, es entregado al consumidor.
   **Cola `email-notification.queue.dlq`**: El mensaje aparece después del NACK (inmediato si no hay TTL configurado).

   El `RabbitMQTopologySetup` (`services/astc-cn-notification-email/src/main/java/io/mindoracare/notification/email/infrastructure/queue/RabbitMQTopologySetup.java`)
   configura la DLQ con:
   - `x-message-ttl`: `EMAIL_NOTIFICATION_DLQ_TTL_MS` (default 60000ms)
   - `x-dead-letter-exchange`: `email.notification` (vuelve al exchange original)

4. Después de 60 segundos, el mensaje vuelve de la DLQ a la cola principal
5. El consumidor lo reintenta (log: `Retry attempt 2/3 for message`)
6. Tras 3 intentos fallidos:
   ```
   ERROR Max retries reached (3/3) for message, discarding.
   ```
   → **ACK definitivo** (no reintenta más)

**Archivo**: `EmailNotificationConsumer.java:39`

#### Verificar el contador x-death

En la consola RabbitMQ:
1. **Queues** → `email-notification.queue.dlq`
2. **Get messages** → mostrar payload
3. En la pestaña **Headers** debe aparecer `x-death` con `count=1` (o más)

---

### Paso 12: Verificar feature flags

#### Deshabilitar canal legacy (evitar duplicados)

En `smms-cn-backend-notification`:
```bash
EMAIL_INTERNAL_DELIVERY_ENABLED=false
```

Esto hace que `ProcessNotificationUseCaseImpl.java:131` no ejecute el emitter interno.

#### Deshabilitar canal nuevo (solo legacy)

```bash
EMAIL_NOTIFICATION_ENABLED=false
```

Esto hace que `ProcessNotificationUseCaseImpl.java:144` no ejecute el emitter al exchange `email.notification`.

---

### Resumen de archivos clave

| Propósito | Archivo | Línea |
|-----------|---------|-------|
| Frontend RegisterAttendance | `frontend-assistance/app/services/assistance.service.ts` | 191 |
| Frontend SaveJustification | `frontend-assistance/app/services/assistance.service.ts` | 314 |
| Outbox publisher | `assistance/.../OutboxEventPublisher.java` | 20 |
| Outbox registerAttendance | `assistance/.../RegisterAttendanceUseCase.java` | 201 |
| Outbox saveJustification | `assistance/.../SaveJustificationUseCase.java` | 94 |
| Notification consumer | `smms-cn-backend-notification/.../NotificationConsumer.java` | 24 |
| Notification processor | `smms-cn-backend-notification/.../ProcessNotificationUseCaseImpl.java` | 60 |
| Email notification consumer | `astc-cn-notification-email/.../EmailNotificationConsumer.java` | 31 |
| Email notification service | `astc-cn-notification-email/.../EmailNotificationService.java` | 35 |
| SMTP sender | `astc-cn-notification-email/.../SmtpEmailSender.java` | 31 |
| DLQ topology | `astc-cn-notification-email/.../RabbitMQTopologySetup.java` | 73 |
| Contract DTO (nuevo) | `astc-cn-notification-email/.../EmailNotificationMessage.java` | 110 |
| Contract DTO (compartido) | `smms-cn-backend-notification/.../shared/.../EmailNotificationMessage.java` | 19 |
| Exchange config (emitter) | `smms-cn-backend-notification/application-app.properties` | 86-91 |
| Exchange config (consumer) | `astc-cn-notification-email/application.properties` | 27-37 |
| Feature flags | `smms-cn-backend-notification/.../ProcessNotificationUseCaseImpl.java` | 46-50 |
| Docker compose | `docker-compose.yml` | 315-357 |
