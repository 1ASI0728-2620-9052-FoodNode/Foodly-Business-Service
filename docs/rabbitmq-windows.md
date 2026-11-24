# RabbitMQ y Transactional Outbox en Windows

El entorno aislado usa RabbitMQ 4.2.9 y Erlang/OTP 27.3.4.19 (ZIP oficiales, hashes SHA256 fijados en `scripts/rabbitmq.ps1`). La [matriz oficial](https://www.rabbitmq.com/docs/4.2/which-erlang) admite Erlang 27. El [arranque manual de RabbitMQ](https://www.rabbitmq.com/docs/4.2/install-windows-manual) permite ejecutar el broker como aplicación con `-detached`.

No se instalan servicios Windows, no se modifica el PATH global ni se cambia una instalación existente. La descarga/instalación de estos ZIP requiere autorización explícita; el script no la ejecuta implícitamente al iniciar el broker.

```powershell
# Solo después de autorizar la descarga e instalación portátil (~230 MB de descarga):
.\scripts\rabbitmq.ps1 -Action Install -InstallApproved
.\scripts\rabbitmq.ps1 -Action Initialize
.\scripts\rabbitmq.ps1 -Action Start
.\scripts\rabbitmq.ps1 -Action Status
.\scripts\rabbitmq.ps1 -Action Stop
```

Puerto AMQP: `127.0.0.1:5673`; distribución: 25673; EPMD: 4370. Todos se limitan a loopback. Nodo: `foodly_business_dev@localhost`. Datos, software, cookie Erlang y credenciales: `.local/rabbitmq`, ignorado por Git, ACL de usuario. No borrar este directorio ni usar `reset`, `purge_queue` o `delete_vhost` para reiniciar. El script conserva las credenciales entre arranques y no las imprime.

Para cargar las variables del broker en la misma terminal:

```powershell
. .\scripts\rabbitmq.ps1 -Action Load
```

Se configuran `RABBITMQ_ADDRESSES=amqp://127.0.0.1:5673`, `RABBITMQ_VHOST=/foodly-business-dev`, y usuario/contraseña privados. No se usa el usuario guest ni se incluyen credenciales en archivos versionados.

## Topología de desarrollo

Exchange topic durable: `foodly.business`, configurable mediante `BUSINESS_EVENTS_EXCHANGE`. La cola auditora de desarrollo `foodly.business.dev.audit` es durable y tiene bindings explícitos para:

- `business.HuariqueRegistrado`
- `business.PerfilDelHuariqueActualizado`
- `business.EstadoAbiertoCerradoCambiado`
- `business.MenuCreado`
- `business.MenuActualizado`

Esta cola pertenece al entorno de verificación; no representa consumidores implementados de Radar o Community. Las colas de esos servicios siguen siendo responsabilidad de sus equipos.

## Aislamiento de la prueba real

La verificación prepara una base MongoDB nueva `foodly_rabbitmq_it_<identificador>` en el replica set de 27018 y dos colas durables nuevas por ejecución. Inicia una instancia completa de Business en 8082, con Outbox activo y JWT local. No reutiliza ni limpia la base `foodly_business`, ni reinicia el servidor existente de 8080. Así los eventos anteriores no se publican ni se marcan manualmente para completar esta prueba.

Se conservarán los registros y los mensajes nuevos. La comprobación del contenido de las colas usa ack manual y requeue, sin purgar mensajes. La caída y recuperación solo detienen/inician el nodo nuevo, no servicios del sistema. Publisher confirms, mensajes persistentes y mandatory returns permanecen activados; `publishedAt` solo debe cambiar después de la confirmación.

La salud de la instancia de verificación debe responder 200 con broker disponible, 503 durante la caída y 200 tras recuperarlo. Esto no prueba deduplicación de consumidores ni reconciliación Business–Radar.

Con el broker nuevo y MongoDB 27018 disponibles:

```powershell
.\scripts\test-rabbitmq-outbox.ps1
```

La sonda inicia el servidor completo y lo cierra al terminar. Guarda un huarique/evento, verifica el mensaje persistente en una cola real y observa `publishedAt`. Después detiene el broker nuevo, crea un menú/evento, verifica que continúe pendiente tras el fallo real, reinicia el broker y espera el reintento automático sin cambiar manualmente `nextAttemptAt` ni `publishedAt`. Comprueba que el primer mensaje durable sobrevivió al reinicio. Los informes quedan en `.local/rabbitmq/verification-<id>.json`; los logs se sanitizan y las claves no se imprimen.

La parada es una interrupción controlada del broker, no una prueba de corte eléctrico o corrupción de disco. Los éxitos de este escenario no equivalen a exactly-once ni demuestran recuperación de todos los fallos posibles.

## Resultado real del 13 de noviembre de 2026

RabbitMQ 4.2.9 quedó activo en 5673 con Erlang/OTP 27.3.4.19, sin servicios Windows nuevos. EPMD quedó aislado en 4370 y la cookie se conserva bajo `.local/rabbitmq/home`; el lanzador la pasa explícitamente al runtime para no crear ni reemplazar la cookie del perfil global. La salida de las herramientas CLI se sanitiza. Los ZIP se extrajeron con soporte de rutas largas, sin modificar la política de Windows.

Ejecución: `55a8f7c9227d40f79565d26ff96149b3`, **17 verificaciones reales exitosas**, sin mocks:

| Escenario | Evidencia |
|---|---|
| Publicación normal | Evento `5eaaecb3-2fc4-43b0-9d6b-d9a3e8eb2d61`, `HuariqueRegistrado`, confirmado en intento 1, `publishedAt` registrado. |
| Broker detenido | Evento `63cf6747-18ec-44f3-8912-2e089319cfe8`, `MenuCreado`, conservado con `publishedAt=null` y error de integración tras un intento real. |
| Recuperación | El mismo evento/JSON se publicó automáticamente en intento 4; se limpió `lastError` y se registró `publishedAt`. No se alteraron manualmente sus tiempos de reintento. |
| Enrutamiento | `messageId`, tipo, routing key, cuerpo y entrega persistente coincidieron con MongoDB en las dos colas dedicadas. |
| Persistencia del broker | El primer mensaje seguía presente con el mismo ID/cuerpo después del reinicio. Los mensajes se reencolaron, no se borraron. |
| Salud HTTP de Business 8082 | 200 antes de la parada, 503 durante la parada, 200 después de la recuperación. |

Informe completo: `.local/rabbitmq/verification-55a8f7c9227d40f79565d26ff96149b3.json`. Base retenida: `foodly_rabbitmq_it_55a8f7c9227d40f79565d26ff96149b3`. Las colas específicas de esta ejecución terminan en `.huarique` y `.menu`; la cola auditora también conserva sus copias. La instancia HTTP 8082 se cerró al terminar la sonda; el broker quedó operativo.

Maven: **96 exitosas, 0 fallidas, 0 errores y 0 omitidas**. El `clean verify` inicial del workspace no pudo borrar el JAR abierto por el servidor 8080; se ejecutó correctamente sobre una copia aislada de los mismos `src`, `pom.xml` y wrapper en `.local/maven-rabbitmq-verification-3bc38bbe8a584ae482bf10c0f4734534`, sin copiar credenciales ni datos. No se detuvo el servidor existente para reconstruir su JAR.

En aquella verificación, el servidor previo de 8080 no se reconfiguró y conservó su conexión anterior a 5672. El único cambio de configuración del microservicio fue exponer `RABBITMQ_VHOST` (predeterminado `/`); el publicador y el worker no necesitaron cambios para superar la prueba. Para usar el broker nuevo en una futura sesión de Business, cargar primero `rabbitmq.ps1 -Action Load` y luego `local-auth.ps1 -Action Start`, en una terminal libre y después de decidir cómo procesar el backlog existente. Esa operación puede publicar eventos anteriores y no se ejecutó en esta validación.

Pendientes: integración de consumidores reales Radar/Community, deduplicación, reconciliación y ensayos de caída abrupta/particiones prolongadas. Esta evidencia acota la garantía al recorrido y parada controlada probados.
