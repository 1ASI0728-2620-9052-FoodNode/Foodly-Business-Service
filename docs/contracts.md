# Contratos de integración y alcance

## Trazabilidad

RF-04: perfil, cocina, etiquetas, precios, ubicación y horarios. RF-05: galerías de huariques/platos, subidas Cloudinary y eliminación diferida. RF-06: reemplazo de carta y cambios puntuales de precio/disponibilidad. RF-07: consultas autenticadas de perfil, galerías y carta.

Se conservan las relaciones y tipos de los diagramas 5.3. `OpeningHours`, `ImageReference`, etiquetas y versiones amplían el modelo mínimo para RF-04/RF-05 y concurrencia. `business_outbox` es la colección adicional DD-08. DD-02/DD-03 se concretan en MongoDB y REST/RabbitMQ. DD-06 se implementa en el servicio con JWT/propietario; Kong es configuración externa.

## IAM: contrato preparado, pendiente de coordinar

La [comparación actualizada y propuesta JWT v1](iam-business-jwt.md) fija el SHA revisado, detalla los cambios por servicio y registra 30 pruebas de compatibilidad JJWT 0.13.0 → Nimbus. Los valores propuestos de issuer/audience todavía requieren acuerdo e implementación en IAM; no se verificó un login remoto.

Business valida JWT; no emite tokens ni administra credenciales/cuentas. El informe indica validación independiente de firma/expiración mediante clave compartida, sin especificar algoritmo. La revisión inicial de IAM `develop` en `850c6d1f45398f3dc5592920d84520146f880c3e` confirmó firma explícita HS256 y clave Base64 externa de al menos 32 bytes. El emisor se revalidó el 24 de noviembre de 2026 en `df6ac4581579b98eee67ab4dc55e78771d0f77f5`, manteniendo ese comportamiento. Sus tokens no incluyen `iss` ni `aud`, por lo que **el contrato actual no es completamente compatible** con la validación estricta de Business. El [análisis y configuración local](local-auth.md) enlaza la evidencia exacta.

Claims obligatorios: `sub` (ID opaco de hasta 128 caracteres), `iss`, `aud`, `exp`. Se valida `nbf` cuando existe. Se usa la tolerancia predeterminada de Spring Security (60 s). Roles: `role` o `roles`, configurable con `IAM_OWNER_ROLE`; el código IAM revisado usa `HUARIQUE_OWNER` y `GASTRONOMIC_EXPLORER`, mientras que el predeterminado de Business es `OWNER`. Issuer y audience son configurables y aún deben acordarse con IAM. No se exige UUID a `sub`, para desacoplar su persistencia. La validación de esos claims no se desactiva para aceptar el formato incompleto de IAM.

Si IAM usa otro algoritmo/claims, adaptar la configuración de seguridad tras acordar el contrato. Conservar validación criptográfica y propiedad; no reemplazarlas con `X-Owner-Id`. Se prueban localmente tokens firmados, expirados y de firma/issuer/audience incorrectos. La interoperabilidad con IAM externo está pendiente.

## Eventos Business v1

Exchange topic durable configurable: `foodly.business`.

| Tipo | Routing key | Snapshot `data` |
|---|---|---|
| HuariqueRegistrado | `business.HuariqueRegistrado` | Huarique completo |
| PerfilDelHuariqueActualizado | `business.PerfilDelHuariqueActualizado` | Huarique completo |
| EstadoAbiertoCerradoCambiado | `business.EstadoAbiertoCerradoCambiado` | Huarique completo |
| MenuCreado | `business.MenuCreado` | Menú completo |
| MenuActualizado | `business.MenuActualizado` | Menú completo |

Sobre JSON:

```json
{
  "eventId": "UUID estable en cada reintento",
  "type": "HuariqueRegistrado",
  "schemaVersion": 1,
  "huariqueId": "UUID",
  "aggregateVersion": 0,
  "occurredAt": "2026-11-13T20:00:00Z",
  "data": {}
}
```

`data` contiene el snapshot de dominio, incluida su versión/IDs. Huarique incluye `ownerId`, `location: {latitude, longitude}`, `openStatus: OPEN|CLOSED` y perfil. Menu incluye `id`, `huariqueId` y categorías/platos. Las imágenes contienen `id`, `publicId` y `url`; el ID técnico no aparece en DTOs HTTP.

`messageId` AMQP es `eventId`; mensaje persistente, content type JSON. No se garantiza orden global: reintentos o varios workers pueden reordenar. Consumidores deben deduplicar por `eventId` y descartar versiones obsoletas **por agregado**. Huarique y Menu tienen secuencias de versión independientes. `MenuCreado` distingue la primera carta. Se deben pactar equivalencias con los nombres ingleses de eventos usados por Radar (`HuariqueRegistered`, `HuariqueUpdated`, `OpeningStatusChanged`); aquí se conservan los nombres de Business en 5.3.

## DD-08: alcance demostrado y pendiente

Implementado en el productor:

- Transacción MongoDB entre catálogo/evento, requiere replica set/Atlas y permisos de transacciones/índices.
- Índices únicos por propietario y huarique del menú; bloqueo optimista y rechazo de versiones obsoletas.
- Claim atómico del outbox, lease 60 s, lotes de hasta 20, backoff exponencial de 2 a 256 s (la expresión actual limita el exponente a 8).
- Publisher confirms correlacionados, mandatory returns, marcado posterior y retención de mensajes sin ruta.
- Recuperación de leases vencidas y `eventId` estable. Una caída después de publicar y antes de marcar puede duplicar la entrega.
- Eliminaciones Cloudinary internas con reintento y borrado idempotente (`ok`/`not found`).

Verificado con MongoDB temporal real: atomicidad/rollback, unicidad, bloqueo optimista y mapeo. La suite unitaria del publicador modela ack, nack y NO_ROUTE con mocks. Además, la [sonda real RabbitMQ del 13 de noviembre de 2026](rabbitmq-windows.md) verificó el servidor completo con Outbox activo, confirmación y enrutamiento a colas durables, conservación del evento durante la parada del broker y publicación del mismo ID/cuerpo al recuperarlo. El primer mensaje durable sobrevivió al reinicio controlado. Esa sonda usa MongoDB/RabbitMQ reales, sin mocks, y conserva los nuevos registros y mensajes.

Pendiente en el sistema: colas y bindings durables Radar/Community; ack manual después de actualizar proyecciones; deduplicación y control de versión en consumidores; reconciliación periódica Business–Radar; pruebas de caída abrupta, particiones y recuperación bajo carga. La parada/recuperación controlada del broker sí fue verificada aisladamente. No se garantiza no pérdida/no duplicación extremo a extremo ni ≤5 s. No se implementa `Idempotency-Key` HTTP.

Contrato de reconciliación propuesto: un coordinador autorizado recorre `GET /business-api/huariques?page=N&size=100`, compara `(id, version, location, openStatus)` con Radar y hace upsert de la versión más reciente. CLOSED se proyecta inactivo. Debe repetir/comprobar al finalizar para cubrir cambios durante la paginación: las páginas no son una instantánea consistente. No existe borrado público de huariques. El coordinador y su planificación **no están implementados**. Business no accede a Redis.

Operación: supervisar pendientes `publishedAt: null`, antigüedad, `attempts` y `lastError` en `business_outbox`. No borrar pendientes ni marcar manualmente enviados. Corregir la dependencia y dejar reintentar. No hay purga automática: definir retención/backup. Antes de escalar, verificar leasing, tiempos y manejo de orden/versiones bajo carga.

Cloudinary/MongoDB no comparten transacción: la carga precede al guardado. Si falla la transacción se intenta borrar el nuevo asset; una caída entre pasos o compensación fallida puede dejar huérfanos. Se requiere auditoría/limpieza operativa; el fallo de compensación registra el publicId. La eliminación de referencias y su tarea de limpieza sí comparten transacción MongoDB. El CDN se invalida eventualmente. DD-07 contempla Integration System, pero su API no se proporcionó: el adaptador directo de 5.3 se mantiene tras el puerto sustituible.

Compose cubre dependencias, salud y reinicio de demo en un host (DD-10). No se ejecutó en este entorno sin Docker y no constituye alta disponibilidad. Producción requiere TLS, autenticación MongoDB, backup/restauración y pruebas de fallos/reconciliación coordinadas.

Referencias técnicas de los adaptadores: [Upload API Cloudinary](https://cloudinary.com/documentation/image_upload_api_reference), [firmas Cloudinary](https://cloudinary.com/documentation/authentication_signatures), [confirms/returns Spring AMQP](https://docs.spring.io/spring-amqp/docs/3.1.0-RC1/api/org/springframework/amqp/rabbit/connection/CorrelationData.html).
