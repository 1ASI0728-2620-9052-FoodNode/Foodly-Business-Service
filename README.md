# Foodly — Business Microservice

Backend del contexto Business: Java 17, Spring Boot 3.5.16, Maven, MongoDB, RabbitMQ y Cloudinary. No implementa IAM ni accede a sus datos.

Basado en `TP_1ASI0728_202620_TP (1).pdf`, usado como referencia de requisitos: RF-04 a RF-07 (página PDF 51), decisiones 4.1.4/DD-08 (93–98), sección 5.3 (136–144) y sus diagramas de componentes, clases y MongoDB (142–144). Se usa la página física del PDF; el índice no coincide con las páginas finales. El documento no se copia al repositorio.

## Organización

- `domain`: agregado `Huarique`, entidades `Menu`, `MenuCategory`, `Dish`, objetos de valor, enums, repositorios y `BusinessDomainService`. Sin dependencias Spring/MongoDB.
- `application`: casos de uso, autorización por propietario y puertos `ImageStorage`/`EventOutbox`.
- `interfaces/rest`: controladores, DTOs, validaciones y errores HTTP comunes.
- `infrastructure`: MongoDB, transacciones/outbox, RabbitMQ, Cloudinary y validación de JWT externos.

`huariques` tiene índice único por propietario; `menus`, por huarique. Ubicación y rango de precios están embebidos en el huarique; categorías y platos, en el menú. Los importes son Decimal128, los identificadores UUID estándar. `business_outbox` amplía el esquema según DD-08.

## Ejecución

Para desarrollo nativo en Windows sin Docker, usar la [guía de MongoDB persistente en 27018](docs/mongodb-windows.md). Mantiene intacto el servicio existente en 27017 y explica inicio, apagado, variables de entorno y verificación real desde Spring Boot.

Para preparar las variables JWT sin incorporar claves al repositorio, consultar [autenticación local y compatibilidad IAM](docs/local-auth.md). El launcher local conserva la autenticación y el Outbox; los tokens actuales de IAM `develop` todavía necesitan `iss` y `aud` para cumplir el contrato de Business.

El [contrato JWT propuesto entre IAM y Business](docs/iam-business-jwt.md) incluye la comparación del código actual de `develop`, configuración por servicio y 30 pruebas de compatibilidad entre JJWT 0.13.0 y Nimbus. La integración con un IAM desplegado sigue pendiente de los cambios del productor; Business conserva sus validaciones.

La [guía RabbitMQ nativo y Outbox](docs/rabbitmq-windows.md) explica la instalación portátil autorizada, el broker aislado en 5673 y la verificación real de publicación, confirmaciones, enrutamiento, caída y recuperación sin modificar eventos anteriores.

La [guía de imágenes y Cloudinary](docs/cloudinary.md) detalla la configuración privada, autorización por propietario, borrado diferido y una prueba real preparada de carga/eliminación que requiere credenciales y autorización expresa. Compilarla con `scripts/test-cloudinary.ps1 -PrepareOnly` no realiza operaciones externas.

Requisitos: JDK 17, conexión para resolver dependencias Maven, MongoDB replica set/Atlas con transacciones, RabbitMQ y contrato JWT acordado con IAM. Cloudinary es necesario para subir imágenes; sin sus variables esas operaciones responden 503.

1. Consulta `.env.example` y configura las variables del proceso. **Spring Boot no importa `.env` automáticamente.** En PowerShell: `$env:NOMBRE='valor'`; en Bash: `export NOMBRE='valor'`. No publiques valores reales.
2. Usa infraestructura existente o `docker compose up -d` después de configurar `RABBITMQ_USERNAME` y `RABBITMQ_PASSWORD` en el entorno o `.env`. MongoDB queda sin autenticación, limitado al loopback del host, para demo local. Este Compose no es un despliegue de producción.
3. Espera los servicios saludables con `docker compose ps`. No reutilices puertos ocupados por otra instalación. El replica set anuncia `localhost:27017`: ejecutar Java en el host.
4. Configura secreto, issuer, audience y rol acordados con IAM. El arranque falla si falta configuración JWT obligatoria. No existe token de desarrollo que omita seguridad.
5. Ejecuta:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
# Alternativa, después de verificar:
java -jar target/business-service-0.0.1-SNAPSHOT.jar
```

En Linux/macOS usa `./mvnw`. Salud: `GET /actuator/health` (MongoDB/RabbitMQ; no valida Cloudinary). El arranque crea colecciones/índices y exige MongoDB disponible. Una instancia standalone no permite las escrituras transaccionales. Para apagar la demo: `docker compose down`; conserva volúmenes.

## Configuración

| Variable | Uso / predeterminado |
|---|---|
| `SERVER_PORT` | `8080` |
| `MONGODB_URI` | `mongodb://localhost:27017/foodly_business?replicaSet=rs0` |
| `RABBITMQ_ADDRESSES` | `amqp://localhost:5672`; CloudAMQP requiere la URI AMQPS/TLS proporcionada |
| `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | Credenciales externas obligatorias |
| `RABBITMQ_VHOST` | `/` por defecto; `/foodly-business-dev` en el broker nativo aislado |
| `BUSINESS_EVENTS_EXCHANGE` | `foodly.business`, exchange topic durable |
| `OUTBOX_ENABLED` | `true`; desactivarlo detiene entregas, sigue guardando eventos |
| `OUTBOX_DELAY_MS` | `1000` entre lotes |
| `IAM_JWT_SECRET_BASE64` | Obligatoria, sin valor por defecto. Clave compartida HS256 Base64 (la misma de `JWT_SECRET_BASE64` en IAM), al menos 32 bytes decodificados |
| `IAM_JWT_ISSUER` | `foodly-iam` por defecto; debe coincidir con `JWT_ISSUER` de IAM |
| `IAM_JWT_AUDIENCE` | `foodly-business` por defecto; debe estar incluido en `JWT_AUDIENCES` de IAM |
| `IAM_OWNER_ROLE` | `HUARIQUE_OWNER` por defecto, el rol de dueño que emite IAM |
| `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | Cuenta para cargas/eliminaciones |

## API y autorización

Todas las rutas Business exigen `Authorization: Bearer <JWT de IAM>`. Consultas: usuario autenticado. Escrituras: rol configurado y, sobre recursos existentes, `sub` igual a `ownerId`. El propietario del registro procede exclusivamente del JWT. `GET /me` exige rol de dueño. No se confía en encabezados de identidad del cliente.

Base `H = /business-api/huariques`:

| Método | Ruta | Resultado |
|---|---|---|
| POST | `H` | RF-04: registrar, 201 y Location; inicialmente CLOSED |
| GET | `H?page=0&size=20` | Catálogo paginado por UUID; tamaño 1–100, 200 |
| GET | `H/me` | Huarique del dueño autenticado, 200 |
| GET | `H/{id}` | RF-07: perfil, horarios, etiquetas y galería, 200 |
| PUT | `H/{id}` | RF-04: reemplazar perfil, 200; requiere `version` |
| PATCH | `H/{id}/open-status` | Estado y `version`, 200 |
| GET | `H/{id}/menu` | RF-07: carta, precios y disponibilidad, 200 |
| PUT | `H/{id}/menu` | RF-06: crear/reemplazar carta completa, 200 |
| PATCH | `H/{id}/menu/dishes/{dishId}/price` | Precio y `version` del menú, 200 |
| PATCH | `H/{id}/menu/dishes/{dishId}/availability` | Disponibilidad y `version` del menú, 200 |
| GET | `H/{id}/images` | Galería, 200 |
| GET | `H/{id}/images/{imageId}` | Metadatos, 200 |
| POST | `H/{id}/images` | RF-05: subir, 201 y Location |
| PUT | `H/{id}/images/{imageId}` | RF-05: reemplazar, 200, devuelve nuevo ID |
| DELETE | `H/{id}/images/{imageId}` | RF-05: quitar referencia y encolar borrado Cloudinary, 204 |

Las cinco rutas de imágenes también existen bajo `H/{id}/menu/dishes/{dishId}/images[/{imageId}]`. POST/PUT usan `multipart/form-data` con parte **file**. PNG/JPEG/WebP hasta 5 MiB, con MIME y firma compatibles; máximo 20 imágenes por local y 10 por plato. El menú devuelve platos disponibles e indisponibles con su indicador para ambas vistas.

Categorías y platos se gestionan por reemplazo de la carta: agregar los crea, mantener IDs los actualiza y omitirlos los elimina. Un menú puede estar vacío; un huarique puede aún no tener menú (GET devuelve 404).

Registro (para PUT agregar `version`):

```json
{
  "name": "El Sabor de Lima",
  "address": "Av. Ejemplo 123, Lima",
  "cuisineType": "CRIOLLA",
  "priceRange": {"minimum": 10.00, "maximum": 45.00},
  "location": {"latitude": -12.0464, "longitude": -77.0428},
  "tags": ["casero", "almuerzos"],
  "openingHours": [{"day": "MONDAY", "opensAt": "09:00:00", "closesAt": "18:00:00"}]
}
```

Cocinas: `CRIOLLA`, `MARINA`, `CHIFA`, `OTHER`. Horarios: días ISO en inglés y hora local de Lima; los intervalos terminan el mismo día y no se solapan. Para atención nocturna, dividir entre días. Días sin intervalos: sin horario publicado. OPEN/CLOSED es manual e independiente del horario. Importes en PEN, no negativos, hasta dos decimales y mínimo ≤ máximo.

Primera carta:

```json
{
  "categories": [{
    "name": "Fondos",
    "dishes": [{"name": "Arroz con pollo", "description": "Con salsa criolla", "price": 18.50, "available": true}]
  }]
}
```

Para editar enviar `version` del menú y conservar IDs de categorías/platos existentes. Los IDs omitidos se generan. `categories: []` elimina las categorías y platos. Las imágenes de platos retenidos se preservan; no se admiten sus metadatos por JSON. Al eliminar platos se encola la limpieza de sus imágenes.

Los PUT del perfil y modificaciones de menú/estado requieren la versión devuelta por GET. Para crear el menú, omitir `version`. MongoDB comprueba además la versión al escribir. Ante 409, consultar y resolver los cambios antes de reintentar. Las cargas de imágenes usan el mismo bloqueo optimista y compensan la carga nueva si falla la persistencia.

Ejemplos PATCH:

```json
{"openStatus": "OPEN", "version": 0}
```

```json
{"price": 20.00, "version": 0}
```

```json
{"available": false, "version": 1}
```

Errores JSON: `timestamp`, `status`, `error`, `message`, `path`, `fields`. HTTP 400 por entradas inválidas, 401 por JWT ausente/inválido, 403 por rol/propiedad, 404 por recursos inexistentes, 409 por duplicados/versiones, 413 por exceso de carga, 415 por tipo HTTP incompatible y 503 por dependencias no disponibles. No se exponen respuestas externas ni conexiones.

## Eventos y dependencias externas

Eventos: `HuariqueRegistrado`, `PerfilDelHuariqueActualizado`, `MenuCreado`, `MenuActualizado`, `EstadoAbiertoCerradoCambiado`. Catálogo y evento se guardan en una transacción MongoDB. Worker con mensajes persistentes, publisher confirms, mandatory returns, lease y reintentos; marca enviado después de confirmación y sin devolución por falta de ruta.

Routing key: `business.<tipo>`. **Radar/Community deben declarar colas durables y bindings.** No hay consumidores ficticios para conseguir confirmaciones. Sin bindings, los eventos quedan pendientes para reintento. Las tareas internas de limpieza Cloudinary no se envían al broker. Un DELETE 204 confirma la referencia eliminada y el trabajo encolado; el CDN se invalida de forma eventual.

El [contrato y alcance DD-08](docs/contracts.md) explica JWT, eventos y reconciliación pendiente. No se promete exactly-once ni propagación ≤5 s. El adaptador Cloudinary directo sigue 5.3; DD-07 propone Integration System. El puerto permite sustituirlo cuando exista ese contrato.

## Pruebas

Las rutas de herramientas de esta guía son ejemplos: ajustar `FOODLY_MONGOD` a la instalación local. Las evidencias fechadas describen ejecuciones históricas, no disponibilidad actual de servidores.

```powershell
.\mvnw.cmd clean verify
```

Incluye dominio, aplicación, MockMvc/HTTP, JWT firmados y contrato del publicador con mocks. El test de contexto sustituye creación de índices y desactiva outbox: verifica cableado, no conectividad. No exige servicios externos.

Para incluir transacciones con **MongoDB real temporal**:

`FOODLY_MONGOD` es la ruta absoluta al ejecutable `mongod.exe` (en Linux/macOS, `mongod`), **no una URL ni una cadena de conexión**. No se lee de `.env`: exportarla antes de ejecutar Maven. No requiere Docker ni `mongosh`.

```powershell
$env:FOODLY_MONGOD = 'C:/tools/mongodb/bin/mongod.exe'
.\mvnw.cmd clean verify
Remove-Item Env:FOODLY_MONGOD
```

`MongoTransactionsTest` inicia y detiene un proceso propio, en puerto libre, replica set de un nodo **rs0** y carpeta aleatoria bajo `target`. No usa la base de desarrollo ni sus volúmenes. Comprueba con `hello` que sea un PRIMARY de rs0 con un solo miembro. Verifica Decimal128/documentos embebidos, índices únicos, versiones, carta y reintentos/limpieza del worker. En el caso exitoso se confirma una transacción en las métricas reales de MongoDB; en rollback se escriben tanto el huarique como el evento real antes de inyectar el fallo, se comprueba que ambos desaparezcan y que aumente el contador de transacciones abortadas. Sin la variable se omite expresamente.

La variable solo configura el entorno de integración temporal. No transforma una instancia MongoDB existente en replica set ni cambia `MONGODB_URI`. Si el servicio local en 27017 es standalone, todavía requiere un replica set para ejecutar la aplicación; las pruebas no lo reconfiguran.

Reportes: `target/surefire-reports`. Las guías de RabbitMQ y Cloudinary registran pruebas reales separadas; no equivalen a una prueba completa entre servicios. Compose, consumidores externos y reconciliación siguen pendientes de validación. Las credenciales de tests no habilitan ningún acceso de desarrollo.

## Preparación para Git: verificación del 24 de noviembre de 2026

- Se corrigió el encabezado XML del POM, conservando dependencias y versiones, y se ampliaron las exclusiones de temporales, configuraciones privadas y material criptográfico.
- Maven Wrapper 3.3.4, modalidad only-script, conserva Maven 3.10.0. Se descargó el ZIP de [Maven Central](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/apache-maven-3.10.0-bin.zip) y se verificó contra su [SHA-512 oficial](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/apache-maven-3.10.0-bin.zip.sha512). El SHA-256 del ZIP verificado se fijó en `distributionSha256Sum`: `1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff`.
- Una copia temporal nueva, sin copiar target, .local, .git ni .env, ejecutó `clean verify` con Java 17: **BUILD SUCCESS**, código de salida **0**, **174 pruebas, 0 fallos, 0 errores y 0 omitidas**; tiempo Maven **31.863 s**. Incluye las ocho pruebas MongoDB con su replica set temporal en puerto libre.
- El build usó settings Maven vacíos y puertos reservados sin servicio para las conexiones predeterminadas de MongoDB/RabbitMQ. La prueba de integración sobrescribió la URI con la de su instancia desechable; no utilizó bases ni brokers persistentes. Los logs se conservaron fuera del repositorio.
- La primera invocación del launcher Windows terminó antes de las pruebas por el paso de argumentos; se corrigió la invocación sin modificar fuentes y se conservó su log. El resultado anterior corresponde a la ejecución final completa.
- No se ejecutaron operaciones Cloudinary ni la sonda RabbitMQ. Los archivos de src y el JAR original permanecieron iguales por SHA-256. Las pruebas reales externas históricas siguen documentadas por separado y no se presentan como repetidas.
- No se hizo staging, commit ni push. Al preparar el primer índice, registrar el permiso ejecutable de mvnw para Linux/macOS; hasta entonces se puede invocar con bash. IAM desplegado, consumidores y reconciliación mantienen los pendientes descritos en las guías.
