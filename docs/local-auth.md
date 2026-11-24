# JWT para desarrollo local y contrato IAM

La [revisión actualizada IAM–Business](iam-business-jwt.md) revalidó `develop`, propone un contrato compartido y añade 30 pruebas JJWT → Nimbus (suite completa: 174 exitosas). Esta guía conserva el launcher de desarrollo independiente y sus verificaciones históricas; no sustituye la configuración pactada con IAM real.

## Evidencia de IAM

Se revisó en modo lectura `Foodly-IAM-Service`, rama `develop`, commit `850c6d1f45398f3dc5592920d84520146f880c3e` (13 de noviembre de 2026). Esta referencia documenta la revisión inicial; no afirma el estado actual de otras ramas.

El 24 de noviembre de 2026 se revalidó el [emisor de `develop` en `df6ac4581579b98eee67ab4dc55e78771d0f77f5`](https://github.com/1ASI0728-2620-9052-FoodNode/Foodly-IAM-Service/blob/df6ac4581579b98eee67ab4dc55e78771d0f77f5/src/main/java/com/foodly/iam/infrastructure/security/JwtTokenIssuer.java): mantiene HS256 y continúa sin emitir `iss` ni `aud`. La prueba de login desplegado sigue pendiente.

- [JwtTokenIssuer.java](https://github.com/1ASI0728-2620-9052-FoodNode/Foodly-IAM-Service/blob/850c6d1f45398f3dc5592920d84520146f880c3e/src/main/java/com/foodly/iam/infrastructure/security/JwtTokenIssuer.java) decodifica una clave Base64 de al menos 32 bytes y firma explícitamente con `Jwts.SIG.HS256`. Emite `sub`, `role`, `iat`, `exp`, pero **no `iss` ni `aud`**.
- [Role.java](https://github.com/1ASI0728-2620-9052-FoodNode/Foodly-IAM-Service/blob/850c6d1f45398f3dc5592920d84520146f880c3e/src/main/java/com/foodly/iam/domain/model/enums/Role.java) define `HUARIQUE_OWNER` y `GASTRONOMIC_EXPLORER`.
- [application.yaml](https://github.com/1ASI0728-2620-9052-FoodNode/Foodly-IAM-Service/blob/850c6d1f45398f3dc5592920d84520146f880c3e/src/main/resources/application.yaml) recibe la clave mediante `JWT_SECRET_BASE64` y configura expiración de 60 minutos.

Hay evidencia de compatibilidad criptográfica HS256/Base64, siempre que ambos usen la misma clave. **No hay compatibilidad completa con esos tokens actuales**: Business exige issuer y audience. No se han consumido tokens de un IAM desplegado ni se ha recibido su clave real. La ausencia de `iss`/`aud` debe resolverse en el contrato con el equipo IAM; Business no relaja su validación. No se modificó su repositorio.

## Variables que usa Business

| Variable / parámetro | Requisito |
|---|---|
| Algoritmo | **HS256 fijado en `SecurityConfiguration`**. No existe variable de entorno para cambiarlo; no se elige a partir del `alg` del token. Otros algoritmos y JWT sin firma se rechazan. |
| `IAM_JWT_SECRET_BASE64` | Base64 estándar; al menos 32 bytes decodificados. Para IAM real, la misma clave que recibe IAM como `JWT_SECRET_BASE64`. No reutilizar claves de tests. |
| `IAM_JWT_ISSUER` | Valor no vacío, coincidencia exacta con `iss`. Predeterminado `foodly-iam`, el que emite IAM. |
| `IAM_JWT_AUDIENCE` | Valor no vacío que debe estar incluido en `aud`. Predeterminado `foodly-business`. |
| `IAM_OWNER_ROLE` | Predeterminado `HUARIQUE_OWNER`, el rol de dueño que emite IAM. |

Business exige además `sub` no vacío de hasta 128 caracteres y `exp`. Comprueba expiración y `nbf` si existe, con la tolerancia predeterminada de Spring Security de 60 segundos. Admite rol singular `role` o lista `roles`. El dueño del recurso se compara con el `sub` verificado. Estos controles siguen activos también en desarrollo.

## Configuración local independiente

```powershell
# Crear una vez. Si ya existe, se valida y reutiliza; no se sobrescribe la clave.
.\scripts\local-auth.ps1 -Action Initialize

# Cargar en esta misma sesión PowerShell, sin mostrar la clave.
. .\scripts\local-auth.ps1 -Action Load

# Iniciar el JAR en esa configuración (sin depender de variables de otra terminal).
.\scripts\local-auth.ps1 -Action Start
```

Si la política de ejecución impide invocar scripts, usar `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-auth.ps1 -Action Start` (o `Initialize`); el cambio de política solo afecta ese proceso. Un `Load` en un proceso PowerShell separado no configura el entorno del IDE ni de la terminal padre.

`Initialize` crea una clave aleatoria CSPRNG de 32 bytes dentro de `.local/auth/business-jwt.json`, excluido de Git, y restringe la ACL de su directorio a la identidad Windows actual. **No muestra el valor de la clave.** Este archivo contiene una credencial local y no debe adjuntarse a issues ni copiarse a documentación. No existe un endpoint de emisión de tokens ni una implementación IAM alternativa.

El contrato local usa issuer `foodly-local-development`, audience `foodly-business-local`, rol de dueño `HUARIQUE_OWNER` y MongoDB en `127.0.0.1:27018`. Estos nombres locales no se presentan como valores emitidos por IAM. La clave es exclusiva de desarrollo y debe reemplazarse por la configuración real al integrar IAM. No se rota automáticamente: regenerarla invalidaría tokens locales existentes.

`Start` mantiene `OUTBOX_ENABLED=true`, conserva los valores RabbitMQ del entorno y no inventa credenciales del broker. Sanitiza su salida para ocultar la clave local incluso si una excepción futura incluyera ese valor. El log local sanitizado queda en `.local/auth/business-startup.log`. El launcher no imprime tokens.

El IDE puede heredar las variables de una terminal configurada o recibirlas en su configuración privada de ejecución. Spring Boot no carga `.env` por sí mismo. No pasar secretos en argumentos `--business.iam.secret-base64=...` ni copiarlos a `application.properties`.

## Arranque y límites de la verificación

Las rutas de ejecutables siguientes son ejemplos; ajustar a la instalación de cada equipo.

MongoDB debe estar iniciado con `scripts/mongodb.ps1 -Action Start`. Preparar el JAR con:

```powershell
$env:FOODLY_MONGOD = 'C:/tools/mongodb/bin/mongod.exe'
$env:MONGODB_URI = 'mongodb://127.0.0.1:27018/foodly_business?replicaSet=rs0'
.\mvnw.cmd clean verify
```

Además del JWT, el servicio requiere `RABBITMQ_USERNAME` y `RABBITMQ_PASSWORD`, y un broker real alcanzable mediante `RABBITMQ_ADDRESSES` (predeterminado `amqp://localhost:5672`). No desactivar Outbox para eludir esa dependencia. Las variables Cloudinary son necesarias para gestionar imágenes, pero pueden quedar ausentes al arrancar.

Las pruebas JWT cubren firma, algoritmo permitido, token sin firma, issuer, audience, expiración, ausencia de audiencia y el rol configurado de dueño mediante la cadena real de Spring Security en MockMvc. La prueba del formato actual IAM usa una fixture basada en el código revisado; no acredita interoperabilidad con un servicio remoto.

Con el servidor local activo en 8080 se puede ejecutar además:

```powershell
.\scripts\test-local-auth.ps1
# Mismo contrato JWT local, otra instancia; el valor predeterminado sigue siendo 8080:
.\scripts\test-local-auth.ps1 -BaseUri 'http://127.0.0.1:8082'
```

Esta comprobación hace únicamente GET: valida HTTP 200 con una fixture firmada con la clave local, 401 sin bearer o con firma/claims inválidos, y 403 para un rol que no coincide con `IAM_OWNER_ROLE`. El acceso autorizado a `/me` devuelve 404 porque el subject aleatorio no tiene huarique; no se registra ningún usuario ni establecimiento. Incluye el formato de claims de IAM actual como fixture rechazada. Los JWT se crean solo en memoria y no se muestran ni guardan. La salud global se informa separadamente de las verificaciones JWT.

`BaseUri` acepta la URL raíz HTTP(S) del servidor, sin ruta API, credenciales, query ni fragmento. Normaliza la barra final. La carga de JWT preserva `MONGODB_URI` de la consola: permite probar 8082 con `foodly_business_frontend_test` sin aplicar la selección de base del launcher. El probador no abre conexiones directas a MongoDB ni cambia la configuración de los procesos existentes.

## Verificación de Business 8082 para frontend

Verificación final del 13 de noviembre de 2026, con `test-local-auth.ps1 -BaseUri 'http://127.0.0.1:8082'`: **12 comprobaciones JWT exitosas, 0 fallidas, 0 omitidas**.

| Caso | HTTP observado |
|---|---|
| JWT local válido, consulta del catálogo | 200 |
| Bearer ausente | 401 |
| Firma incorrecta o JWT sin firma | 401 |
| `aud` ausente o incorrecta | 401 |
| `iss` ausente o incorrecto | 401 |
| JWT expirado | 401 |
| Formato IAM actual sin `iss`/`aud` | 401 |
| `HUARIQUE_OWNER` en `/business-api/huariques/me` | 404 de aplicación: autorizado, subject nuevo sin huarique |
| Rol `OWNER` distinto al configurado | 403 |

En aquella ejecución pasaron dos comprobaciones adicionales de salud: una mediante loopback y otra mediante la dirección LAN del equipo, ambas HTTP 200 y `UP`. Se observaron conexiones del proceso de 8082 hacia MongoDB 27018 y RabbitMQ 5673. La dirección concreta se omite porque depende del entorno. El GET autenticado del catálogo ejercita una lectura MongoDB. No se hicieron escrituras de prueba, no se validó el nombre de la base mediante un endpoint de configuración y no se publicó ningún mensaje de prueba.

La validación del parámetro rechazó seis entradas inválidas antes de cargar credenciales, y se comprobó estáticamente que el valor por defecto sigue siendo `http://127.0.0.1:8080`. También se ejecutó el probador con `MONGODB_URI` de `foodly_business_frontend_test` y se comprobó que esa variable conserva su valor. Resultado final: **14 comprobaciones HTTP solicitadas/de acceso LAN correctas y 6 de validación de URL correctas; 0 fallos en estas comprobaciones finales**. No se repitió Maven: el cambio es de PowerShell y documentación, sin cambios Java.

Incidencias de diagnóstico resueltas sin modificar el servidor:

- Windows PowerShell devuelve el contenido `application/vnd.spring-boot.actuator.v3+json` como `byte[]`. La comprobación adicional debió decodificar UTF-8 antes de `ConvertFrom-Json`; el cuerpo ya informaba `UP`, no era una caída del backend.
- Los intentos adicionales `GET /actuator/health/mongo` y `/rabbit` devolvieron 404. La configuración conserva `show-details=never` y no publica componentes; [Spring Boot oculta las subrutas cuando no muestra componentes](https://github.com/spring-projects/spring-boot/blob/v3.5.16/spring-boot-project/spring-boot-actuator/src/main/java/org/springframework/boot/actuate/health/HealthEndpointSupport.java). Estos 404 no acreditan una caída de MongoDB/RabbitMQ. Se conservaron las restricciones y se comprobaron salud global y conexiones existentes; no se cambiaron permisos para exponer detalles individuales.

Los listeners de 8080 y 8082 conservaron sus procesos, y el JAR original y archivo JWT conservaron sus hashes. No se reinició ningún servidor, no se procesaron eventos manualmente ni se modificaron MongoDB/RabbitMQ. No se imprimieron ni guardaron claves o bearer tokens.

Para conectar el frontend, usar la base URL del equipo que ejecuta Business, por ejemplo **`http://<IP_LAN_DEL_SERVIDOR>:8082`**, y las rutas `/business-api/...`. El marcador es un ejemplo y debe sustituirse; no representa un servidor actualmente disponible. El iPhone debe comprobar por sí mismo acceso a `/actuator/health`; la prueba LAN se ejecutó desde el equipo, no desde Expo Go, y no acredita todavía el acceso a través del firewall/Wi-Fi desde el teléfono. En el iPhone, `localhost` apunta al teléfono. El cliente enviará un bearer autorizado; la clave HS256 permanece en el backend y no debe incorporarse al bundle Expo. La interoperabilidad con el IAM desplegado sigue siendo una verificación separada.

## Resultado verificado el 13 de noviembre de 2026

- `local-auth.ps1 -Action Start` inició el servidor completo en 8080, con MongoDB en 27018 y el publicador Outbox activo. No se proporcionaron credenciales ficticias RabbitMQ.
- Se eliminó el bloqueo de `IAM_JWT_SECRET_BASE64` mediante la configuración local protegida. No se confirmó interoperabilidad con un IAM desplegado; el formato del commit revisado continúa rechazándose por falta de issuer/audience.
- `mvnw.cmd clean verify`: **96 exitosas, 0 fallidas, 0 errores, 0 omitidas**, incluidas las ocho pruebas MongoDB.
- `test-local-auth.ps1`: **12 verificaciones HTTP exitosas, 0 fallidas** contra el servidor real. Token válido: 200; tokens sin firma, sin claims requeridos, expirados o con firma incorrecta: 401. Rol de dueño configurado autorizado y rol OWNER distinto: 403.
- RabbitMQ **no impidió el arranque**. Sí impide la publicación y deja `/actuator/health` en HTTP 503: `RabbitHealthIndicator` informó `AmqpConnectException` causada por `ConnectException: Connection refused` en 5672. Faltan `RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD` y un broker disponible. Outbox mantiene pendientes/reintentos.
- Se comprobó que la clave local no aparece en fuentes ni en el log sanitizado, que Git ignora su archivo y que la ACL está protegida. Su valor no se imprimió.
