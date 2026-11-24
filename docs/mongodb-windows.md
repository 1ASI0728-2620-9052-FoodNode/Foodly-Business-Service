# MongoDB persistente para desarrollo en Windows

La instancia nativa de Foodly usa `127.0.0.1:27018`, replica set **rs0** de un nodo y base `foodly_business`. Es independiente del servicio standalone de 27017. No instala servicios, no cambia sus archivos de configuración ni elimina bases de datos.

Datos persistentes: `.local/mongodb-rs0-27018/data`, fuera de `target` y excluidos de Git. Log: `.local/mongodb-rs0-27018/mongod.log`. El marcador `instance.json` identifica el directorio administrado. `mvnw clean` no elimina esta instancia. No borres ni muevas `.local` para reiniciar MongoDB.

## Requisitos y arranque

JDK 17, Maven Wrapper y `mongod.exe` instalado. La ruta de ejemplo `C:/tools/mongodb/bin/mongod.exe` debe sustituirse por la instalación de cada equipo. No requiere Docker, mongosh ni permisos de administrador. Ejecutar desde la raíz del repositorio:

```powershell
# Ruta al ejecutable; no es una URL.
$env:FOODLY_MONGOD = 'C:/tools/mongodb/bin/mongod.exe'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\mongodb.ps1 -Action Start

# Configurar explícitamente la conexión del proceso Java/IDE.
$env:MONGODB_URI = 'mongodb://127.0.0.1:27018/foodly_business?replicaSet=rs0'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\mongodb.ps1 -Action Status
```

`Start` inicia un proceso de usuario en segundo plano, enlazado solo a loopback. Inicializa rs0 únicamente si aún no existe configuración de replicación; no ejecuta `rs.reconfig`. En siguientes arranques reutiliza los mismos datos. Si 27018 pertenece a otro proceso o el directorio no tiene un marcador compatible, se detiene sin adoptar ni modificar la instancia desconocida.

El helper utiliza el driver Java ya declarado por el proyecto; prepara su classpath con Maven y no descarga/instala mongosh. La configuración de ejemplo `.env.example` usa 27018, pero Spring Boot no carga `.env` automáticamente. Las variables deben estar en el proceso o en la configuración de ejecución del IDE.

## Detener y volver a iniciar

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\mongodb.ps1 -Action Stop
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\mongodb.ps1 -Action Start
```

`Stop` verifica puerto, dbPath, dirección de escucha y configuración rs0 antes de solicitar un apagado mediante el comando MongoDB `shutdown`. No utiliza `Stop-Service`, no termina todos los procesos mongod y conserva los archivos. `Status` comprueba que exista un PRIMARY de un solo miembro; no basta con que el puerto esté abierto.

## Verificar persistencia desde Spring Boot

Esta operación escribe datos nuevos y requiere autorización previa. Conserva **un huarique de verificación y su evento Outbox**; usa identificadores nuevos en cada ejecución. Otro registro y su evento se revierten mediante rollback. No borra ni cambia registros preexistentes.

```powershell
$env:MONGODB_URI = 'mongodb://127.0.0.1:27018/foodly_business?replicaSet=rs0'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\mongodb.ps1 -Action Verify -WriteVerificationData
```

La sonda inicia un contexto Spring Boot de persistencia con los servicios de aplicación, repositorios, `MongoTransactionManager` y `MongoEventOutbox` reales. No usa mocks. Confirma que:

- Huarique y evento asociado quedan guardados tras commit y aumenta `serverStatus.transactions.totalCommitted`.
- Ambas escrituras existen dentro de otra transacción; tras una excepción deliberada ninguna queda guardada y aumenta `totalAborted`.

La sonda no es el servidor HTTP completo: no crea credenciales IAM ni simula un broker RabbitMQ. Tampoco cambia `OUTBOX_ENABLED` ni la configuración del microservicio. El evento de verificación queda pendiente para el publicador real cuando se ejecute Business con RabbitMQ configurado. La emisión al broker no queda validada por esta comprobación MongoDB.

Los resultados `MONGODB_DEVELOPMENT` y `SPRING_BOOT_PERSISTENCE` muestran el estado, los IDs retenidos y los contadores. `Status` también consulta los pares de verificación retenidos (hasta diez) sin escribir datos. Para comprobar persistencia entre reinicios: ejecutar `Stop`, `Start` y `Status`, y comprobar que conserve los mismos IDs; no volver a ejecutar `Verify` esperando reutilizar el mismo registro.

## Ejecutar Foodly Business

Después de iniciar MongoDB, configurar los valores reales provistos por IAM y RabbitMQ en variables de entorno (o en el IDE), sin incorporarlos al repositorio:

- `MONGODB_URI`: la URI de 27018 indicada arriba.
- `IAM_JWT_SECRET_BASE64`, `IAM_JWT_ISSUER`, `IAM_JWT_AUDIENCE`; `IAM_OWNER_ROLE` si corresponde.
- `RABBITMQ_ADDRESSES`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`.
- Variables `CLOUDINARY_*` para operar con imágenes.

```powershell
.\mvnw.cmd spring-boot:run
# O después de clean verify:
java -jar target/business-service-0.0.1-SNAPSHOT.jar
```

Detener Business con Ctrl+C. MongoDB sigue siendo un proceso independiente; detenerlo con el comando anterior cuando corresponda. No establecer `OUTBOX_ENABLED=false` para eludir un RabbitMQ ausente. Si el broker falla, los eventos pendientes se conservan y el publicador reintenta; la salud global puede ser DOWN.

La falta de configuración JWT bloquea el arranque del servidor completo; la sonda de persistencia no demuestra integración con IAM ni entrega de eventos. El Compose opcional sigue usando 27017 y no debe iniciarse sobre el servicio existente sin revisar el conflicto de puertos.

## Pruebas automatizadas

```powershell
$env:FOODLY_MONGOD = 'C:/tools/mongodb/bin/mongod.exe'
$env:MONGODB_URI = 'mongodb://127.0.0.1:27018/foodly_business?replicaSet=rs0'
.\mvnw.cmd clean verify
```

Las ocho pruebas `MongoTransactionsTest` conservan su propio replica set y directorio temporal. Su `@DynamicPropertySource` sustituye la URI: no limpian ni escriben sobre la base persistente de 27018. La sonda manual anterior es la que verifica la instancia persistente.

## Verificación realizada el 13 de noviembre de 2026

- MongoDB 8.0.1 persistente en 27018, `rs0` de un nodo y PRIMARY comprobado por el driver.
- Conexión Spring Boot con adaptadores reales: `totalCommitted` pasó de 0 a 1 y `totalAborted` de 0 a 1. Ningún mock intervino en esta sonda.
- Se conservó el huarique `213dfc06-5649-49a7-848a-e5c609f360c8` con evento `00c5f380-d1d6-437e-87b2-83e95bc0b76a`. Ambos seguían presentes después de apagar y volver a iniciar la instancia. El evento sigue pendiente de publicación.
- `mvnw.cmd clean verify`: 91 pruebas exitosas, 0 fallidas, 0 errores y 0 omitidas, incluidas las ocho pruebas MongoDB temporales.
- El arranque del JAR completo con la nueva URI se intentó y falló por `IAM_JWT_SECRET_BASE64` ausente. También faltaban las demás variables IAM y RabbitMQ, y no había broker en 5672. No se crearon credenciales ficticias ni se desactivó el Outbox para eludirlo.
- El servicio MongoDB existente de 27017 se mantuvo intacto. No se instaló ningún servicio nuevo ni se eliminaron bases de datos o volúmenes.

La validación posterior de [autenticación local](local-auth.md) resolvió el bloqueo de clave ausente y consiguió arrancar el servidor completo. RabbitMQ continúa sin broker/credenciales: la salud global responde 503 y la entrega de eventos está pendiente, con Outbox activo.
