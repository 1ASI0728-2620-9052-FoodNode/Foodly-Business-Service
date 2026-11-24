# Imágenes y Cloudinary

## Estado y arquitectura

La revisión conserva la sección 5.3 del informe Foodly: `ImageController` recibe HTTP y devuelve DTOs; `ImageApplicationService` autoriza y coordina los casos de uso mediante el puerto `ImageStorage`; `ImageStorageAdapter` implementa ese puerto en infraestructura. El dominio conserva `ImageReference`, sin SDK, HTTP ni credenciales. MongoDB almacena referencias, no archivos binarios. El adaptador directo corresponde al componente Cloudinary de 5.3; la mediación por Integration System propuesta en DD-07 sigue dependiendo de un contrato externo.

La última prueba completa de almacenamiento real tiene estado **PASSED**, con las credenciales corregidas y un UUID nuevo: subida de PNG de 1x1, consulta de metadatos, eliminación exclusiva del fixture y consulta final HTTP 404. Los cuatro indicadores son `true` y no quedan recursos pendientes de esa prueba. El intento histórico fallido y su consulta posterior 404 se conservan documentados más abajo. No se ha validado mediante este probe el flujo HTTP Business con asociación persistida ni el worker de limpieza. `/actuator/health` comprueba MongoDB/RabbitMQ, no Cloudinary.

## Configuración privada en Windows

| Variable | Valor necesario |
|---|---|
| `CLOUDINARY_CLOUD_NAME` | Cloud name de una cuenta de desarrollo autorizada |
| `CLOUDINARY_API_KEY` | API key de esa cuenta |
| `CLOUDINARY_API_SECRET` | API secret correspondiente, solo en backend |

El adaptador usa la API HTTPS global `https://api.cloudinary.com/v1_1/{cloud_name}/image`. No interpreta `CLOUDINARY_URL`, no necesita un upload preset unsigned y no admite configuración de endpoints regionales. La cuenta debe permitir cargas firmadas y destrucción; la prueba real también requiere lectura de recursos mediante Admin API. No se debe reutilizar una cuenta de producción sin autorización.

Puedes exportar las tres variables mediante tu gestor de secretos, o configurar el archivo privado de desarrollo de forma interactiva:

```powershell
# No contacta Cloudinary. Solicita API key y secret con entrada oculta.
.\scripts\cloudinary.ps1 -Action Configure
.\scripts\cloudinary.ps1 -Action Status

# Dot-source: carga variables únicamente en esta sesión de PowerShell.
. .\scripts\cloudinary.ps1 -Action Load
```

El archivo `.local/cloudinary/credentials.xml` está ignorado por Git. Los SecureString se cifran con Windows DPAPI para el usuario y equipo actuales; el directorio tiene ACL privada. No es un archivo portable ni un mecanismo de secretos de producción. El script no sobrescribe credenciales existentes ni mezcla variables incompletas con el archivo. `Status` muestra solo presencia, nunca valores, y no prueba conectividad. No envíes claves por chat.

Configure y Load rechazan caracteres de control y espacios al principio/final sin corregir ni recortar secretos automáticamente. Esto incluye el caso en que una consola guarda el control de Ctrl+V en lugar del texto del portapapeles. Usa la opción **Pegar** del menú de la consola para la entrada oculta. Si la entrada no es válida, Configure no escribe el archivo y Load no exporta las variables del archivo. Status informa `configured` y `formatValid` para distinguir presencia de formato admisible; ninguno de los dos valida autenticación contra la cuenta.

El archivo actual se conservó como evidencia. Para reingresar credenciales, ejecutar manualmente estos pasos locales desde el repositorio, con el usuario que creó el archivo; el respaldo permanece cifrado e ignorado por Git:

```powershell
Rename-Item -LiteralPath .local/cloudinary/credentials.xml -NewName ('credentials-before-reentry-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.xml')
.\scripts\cloudinary.ps1 -Action Configure
. .\scripts\cloudinary.ps1 -Action Load
.\scripts\cloudinary.ps1 -Action Status
```

Estos comandos están documentados para el usuario: no se ejecutaron durante el diagnóstico. No repetir la prueba de subida hasta verificar primero el ID pendiente y contar con autorización para las operaciones externas.

### Permisos sin administrador

El script modifica exclusivamente la DACL: una regla FullControl para el SID del usuario actual, heredable a archivos y subdirectorios, sin herencia del directorio padre. No cambia propietario/grupo ni consulta o escribe la SACL de auditoría.

La implementación anterior usaba `Set-Acl` con un descriptor completo. El [proveedor FileSystem de PowerShell](https://github.com/PowerShell/PowerShell/blob/master/src/System.Management.Automation/namespaces/FileSystemSecurity.cs) intenta primero escribir todas las secciones, incluida auditoría, lo que puede solicitar `SeSecurityPrivilege`. La corrección construye el descriptor con `AccessControlSections.Access` y lo persiste directamente mediante .NET, que guarda las secciones modificadas. Después comprueba la DACL aplicada antes de solicitar o guardar credenciales. No requiere ejecutar PowerShell como administrador ni conceder privilegios de auditoría. Un fallo de permisos detiene Configure; no continúa hasta el mensaje de éxito.

Regresión local, sin cuenta ni solicitudes Cloudinary:

```powershell
.\scripts\test-cloudinary-credentials.ps1
```

El test exige un token no administrador y sin `SeSecurityPrivilege`, usa entradas sintéticas y ejecuta Configure, Load y Status en una carpeta temporal aislada. DACL, archivo y cifrado/descifrado DPAPI son reales. Comprueba permisos privados, ausencia de secretos en la salida, exclusión de Git, rechazo de sobrescritura y un AccessDenied real que detiene Configure sin pedir secretos ni escribir credenciales. Restaura el entorno y elimina exclusivamente sus fixtures nuevos.

Resultado histórico de la corrección de ACL en Windows PowerShell **5.1.26100.9711**: **14 comprobaciones correctas**. Se comprobó el descifrado del archivo existente, sin verificar entonces su formato. El diagnóstico posterior añadió regresiones de entrada con controles, texto mezclado y espacios; su ejecución local ampliada está pendiente del resultado del usuario, porque la revisión automática de ejecución no pudo completarse por límite de uso. La rama de compatibilidad PowerShell 7 está preparada, pero no se ejecutó porque `pwsh` no está instalado. Estas verificaciones no arrancan Business ni ejecutan su worker.

Spring Boot toma estas variables al arrancar y no importa `.env` automáticamente. Cargar variables en otra consola no modifica el servidor existente en 8080. Esta revisión no lo reinicia ni cambia su entorno.

Las cargas firman los parámetros ordenados `overwrite`, `public_id` y `timestamp` con SHA-1, según la [documentación de firmas](https://cloudinary.com/documentation/authentication_signatures). El secret no viaja en la solicitud. Cada carga solicita un UUID bajo `foodly/business/` y `overwrite=false` para no sobrescribir un activo existente.

## Operaciones y validaciones

Base para huarique: `/business-api/huariques/{id}/images`.
Base para plato: `/business-api/huariques/{id}/menu/dishes/{dishId}/images`.

| Método | Sufijo | Comportamiento |
|---|---|---|
| POST | base | Multipart `file`; sube y asocia al propietario; 201 con Location |
| PUT | `/{imageId}` | Reemplaza referencia, devuelve nuevo ID; 200; encola limpieza de la anterior |
| GET | base o `/{imageId}` | Referencias `{id,url}` desde MongoDB; 200; no consulta Cloudinary |
| DELETE | `/{imageId}` | Elimina referencia y encola limpieza en Outbox transaccionalmente; 204 |

Las consultas requieren JWT válido. Las escrituras exigen rol propietario y `sub` igual a `ownerId`, comprobado antes de contactar al proveedor. Un plato o reemplazo inexistente se rechaza antes de subir. Límite: 20 referencias por huarique y 10 por plato; son límites de la implementación actual, no requisitos adicionales atribuidos al informe.

Se admiten PNG, JPEG y WebP con MIME y firma binaria compatibles; máximo 5 MiB. Se rechazan datos nulos, vacíos, tipo incompatible y exceso de tamaño. La validación local identifica cabeceras: **no decodifica completamente la imagen**. Cloudinary debe aceptar el archivo para completar la carga. Spring limita cada archivo a 5 MB y la solicitud multipart a 6 MB.

Las URLs deben ser HTTPS absolutas, tener host y no contener credenciales. Una respuesta inválida de Cloudinary se trata como fallo de dependencia (503), no como error del usuario (400). Los errores públicos no incluyen cuerpos externos ni secretos. El adaptador usa timeouts de conexión de 5 s y lectura de 15 s.

Si falla una carga o su respuesta, se intenta destruir exclusivamente el UUID solicitado por esa carga. Si falla la persistencia de la referencia, la aplicación también intenta compensar la carga nueva. La compensación es de mejor esfuerzo; si falla se registra el public ID para reconciliación manual. No existe una transacción distribuida entre MongoDB y Cloudinary: una caída del proceso, una respuesta ambigua o un fallo de limpieza pueden dejar recursos huérfanos. La reconciliación automática sigue pendiente.

Un DELETE 204 acredita la eliminación de metadatos y el trabajo Outbox encolado. **No acredita el borrado externo final**. El worker procesa después `internal.ImageDeletionRequested`; esas tareas no se publican en RabbitMQ. La [API destroy](https://cloudinary.com/documentation/image_upload_api_reference#destroy_by_public_id) puede invalidar el CDN de forma eventual. Esta revisión no ejecuta el worker sobre eventos pendientes.

## Prueba real preparada, ejecución con autorización

Compilar y comprobar la preparación local no requiere cuenta ni crea recursos:

```powershell
.\scripts\test-cloudinary.ps1 -PrepareOnly
```

Solo después de autorizar expresamente las operaciones en la cuenta configurada:

```powershell
.\scripts\test-cloudinary.ps1 -ExternalOperationsApproved
```

La bandera representa autorización consciente; el script no debe invocarse con ella sin consentimiento. La ejecución crea un recurso y consume cuota de API, almacenamiento y solicitudes según la cuenta. No se instalan componentes ni se crean cuentas.

La prueba usa el **adaptador de producción real**, genera una PNG válida de 1x1 píxel y realiza:

1. Carga firmada sin sobrescritura, con UUID nuevo.
2. Consulta autenticada de ese public ID por [Admin API](https://cloudinary.com/documentation/admin_api#get_details_of_a_single_resource), verificando PNG y dimensiones 1x1.
3. Destrucción exclusivamente del recurso recién subido, incluso si falla la consulta de metadatos.
4. Consulta posterior que debe devolver HTTP 404. Un 401, 403, 429, timeout o respuesta vacía nunca se considera prueba de borrado.

Guarda un recibo sin credenciales en `.local/cloudinary/verification-<uuid>.json`. Solo declara `PASSED` si confirma los cuatro pasos. Si el proceso se interrumpe, consulta el recibo y los public IDs registrados antes de cualquier limpieza manual autorizada. No hay eliminación masiva ni aceptación de IDs arbitrarios.

El probe no inicia Spring Boot, no abre puertos, no accede a MongoDB/RabbitMQ y no procesa Outbox. Prueba almacenamiento real; no demuestra por sí solo el flujo HTTP completo con asociación persistida ni la limpieza de una tarea Outbox contra una cuenta real. Esas comprobaciones siguen pendientes y deben usar fixtures nuevos autorizados. No se ejecuta automáticamente con Maven ni añade pruebas omitidas.

## Resultado de la prueba real autorizada

El 13 de noviembre de 2026 se ejecutó una sola vez `scripts/test-cloudinary.ps1 -ExternalOperationsApproved`, sin `-PrepareOnly`, con el adaptador de producción y las credenciales del archivo privado. No se reimplementó el flujo ni se usaron mocks para esta ejecución. Se preparó la PNG de 1x1 con public ID único; la llamada real de subida lanzó `IntegrationException` y el intento de compensación de ese mismo public ID también falló. Se detuvieron las operaciones externas y no se volvió a subir una imagen.

| Indicador | Evidencia de esta ejecución |
|---|---|
| `uploadConfirmed` | No confirmado: la llamada no devolvió una referencia válida |
| `adminReadConfirmed` | No ejecutado: el flujo no llegó a Admin API |
| `destroyConfirmed` | No confirmado: se registró un fallo de limpieza en el adaptador |
| `adminAbsentConfirmed` | No ejecutado: no hay evidencia de ausencia |

El recibo original es `.local/cloudinary/verification-94dfe17c-0e3a-43a8-b4b7-dc2271a3ba7f.json`, con estado `FAILED_OR_INCOMPLETE`. Como la subida lanzó la excepción antes de devolver, el helper no llegó a guardar el public ID ni los cuatro indicadores en ese recibo. El ID sí apareció en el mensaje sanitizado de fallo de compensación y se conserva en el informe complementario privado `.local/cloudinary/observation-3cfa1cfc9ef54b2884c49dd553c5cf96.json`. Allí `false` significa **no confirmado**, no prueba de que el recurso exista o no exista. El recurso debe considerarse pendiente de verificación; no se declara creado ni eliminado.

Limitación del recibo histórico: el adaptador de esa ejecución transformaba las excepciones HTTP/transporte en un `IntegrationException` sin conservar su causa ni código HTTP. Ese código HTTP no puede reconstruirse a partir del recibo. El diagnóstico posterior sí encontró credenciales locales inválidas, como se explica a continuación, sin realizar consultas adicionales ni nuevos intentos. Sigue pendiente una consulta autorizada del public ID registrado y, si existe, su limpieza. No se realizaron otras operaciones sobre la cuenta.

Tras el intento, el SHA-256 del archivo de credenciales y del JAR original permanecieron iguales, y el proceso de 8080 conservó su identidad. No se alteraron las instancias persistentes MongoDB/RabbitMQ ni se ejecutó el worker sobre sus eventos. Las credenciales y recibos siguen ignorados por Git, sin archivos privados rastreados.

Maven `clean verify` terminó con **127 exitosas, 0 fallidas, 0 errores y 0 omitidas** en `.local/cv-e220686f/`, con código, configuración y wrapper idénticos por SHA-256 (60 archivos). Incluye las ocho pruebas con MongoDB temporal propio; las pruebas no usan la base de desarrollo. La primera copia, con una ruta más larga, produjo ocho errores porque su MongoDB temporal falló al abrir un archivo de métricas `diagnostic.data`; la ejecución en la ruta corta resolvió ese bloqueo sin modificar código. Se conservan los reportes del primer intento en `.local/maven-cloudinary-live-verification-f00c27452b7c4f7e8bbee03da8928e6d/target/surefire-reports/` y los finales en `.local/cv-e220686f/target/surefire-reports/`.

## Diagnóstico posterior sin nuevas solicitudes a Cloudinary

### Hallazgos confirmados y límites

- El informe privado original y el complementario sitúan el fallo dentro de `ImageStorageAdapter.upload`, antes de devolver una referencia y antes de consultar Admin API. Luego falló la compensación `POST /image/destroy` del mismo ID solicitado. El recibo no distingue si la excepción original surgió al enviar/recibir `POST /image/upload` o al validar su respuesta; no contiene mensaje, status ni causa original.
- `request` descartaba `RestClientException`, su código HTTP y su causa. La compensación sustituía el segundo error por otro genérico. El helper solo registraba la clase de la excepción y creaba los cuatro indicadores después de cada éxito, por lo que faltaban todos cuando fallaba la subida.
- Se descifraron las credenciales solo en memoria y se emitieron únicamente indicadores booleanos: ambos campos contenían exclusivamente controles de pegado. No eran API key/secret reales. La validación solo comprobaba longitud o ausencia de blancos y aceptaba esos controles. No hay evidencia de un fallo de cifrado DPAPI ni de permisos en esta fase.
- Esto confirma una configuración local inválida y un defecto de validación. No demuestra qué respuesta exacta dio Cloudinary: no se puede afirmar un 400/401/403 concreto ni reconstruir si hubo además un fallo de transporte. El estado del recurso sigue **DESCONOCIDO**, aunque la configuración defectuosa haga plausible un rechazo de autenticación.

### Revisión del contrato

Se contrastó con el código oficial disponible en GitHub, sin solicitudes a los endpoints de Cloudinary. No se identificó un defecto demostrable en el contrato de solicitudes revisado:

| Operación | Construcción revisada |
|---|---|
| Upload | `POST https://api.cloudinary.com/v1_1/{cloud}/image/upload`, multipart con `file`, `api_key`, `timestamp`, `public_id`, `overwrite=false`, `signature` |
| Firma upload | SHA-1 hexadecimal de `overwrite=false&public_id=<id>&timestamp=<epoch-seconds>` seguido del secret; `file`, `api_key` y `cloud_name` no se firman |
| Destroy | `POST .../image/destroy`, form URL-encoded con `api_key`, `timestamp`, `public_id`, `invalidate=true`, `signature` |
| Firma destroy | SHA-1 de `invalidate=true&public_id=<id>&timestamp=<epoch-seconds>` seguido del secret |
| Admin lookup | `GET .../resources/image/upload/{public_id}` y Basic Auth en cabecera; credenciales nunca en la URL |

El multipart usa bytes de archivo con filename `image`; el tipo de recurso es `image` y la firma binaria valida los formatos admitidos. No se utiliza un preset unsigned para solicitudes firmadas. La cuenta debe aceptar el algoritmo configurado y la clave debe corresponder al mismo cloud name: eso sigue sin validación remota.

Fuentes primarias: [firma ordenada y hash en Util.java](https://github.com/cloudinary/cloudinary_java/blob/master/cloudinary-core/src/main/java/com/cloudinary/Util.java), [parámetros upload/destroy en Uploader.java](https://github.com/cloudinary/cloudinary_java/blob/master/cloudinary-core/src/main/java/com/cloudinary/Uploader.java), [ruta resource y autenticación en Api.java](https://github.com/cloudinary/cloudinary_java/blob/master/cloudinary-core/src/main/java/com/cloudinary/Api.java).

### Correcciones y pruebas locales

- `CloudinaryIntegrationException` conserva una operación, un motivo enumerado, el código HTTP cuando Spring lo proporciona y únicamente un public ID de formato administrado. Clasifica HTTP, DNS, TLS, timeout, conexión, IO y respuesta inválida sin almacenar mensajes arbitrarios, cuerpos, cabeceras, firmas, URLs ni la excepción original.
- El adaptador registra solo ese diagnóstico, conserva por separado el diagnóstico de la compensación y distingue cuándo esa compensación sí fue confirmada. La respuesta pública de Business sigue siendo el error 503 genérico, sin detalles internos.
- El helper inicializa los cuatro indicadores a `false`, guarda la fase, el ID solicitado disponible en la excepción, el diagnóstico principal y el de limpieza. `false` siempre significa no confirmado, no recurso inexistente. El wrapper añade una redacción defensiva de posibles hashes de firma; no se habilitan logs HTTP de cuerpos/cabeceras.
- Configure, Load y el adaptador rechazan las credenciales malformadas antes de enviarlas. Load valida ambos valores antes de exportar variables desde el archivo y no reescribe el archivo existente.

Maven `clean verify`: **144 exitosas, 0 fallidas, 0 errores y 0 omitidas**, incluidas **17 regresiones nuevas** de HTTP/autenticación simulada, causas de transporte, ausencia de secretos, preservación de ambos errores, firma de destroy y rechazo de entradas inválidas sin solicitudes. Se ejecutó en `.local/cd-bfa93d6f/`; 62 archivos de código/configuración/wrapper coinciden por SHA-256 con el workspace. Reportes: `.local/cd-bfa93d6f/target/surefire-reports/`. Las ocho pruebas MongoDB usan su instancia temporal propia; las instancias persistentes y sus eventos no se usan.

En esa revisión, los tres scripts pasaron el parser de PowerShell y la regresión ampliada de credenciales y la compilación del helper quedaron pendientes por un límite de la revisión automática y falta de acceso a la caché Maven. Posteriormente el helper sí se compiló y ejecutó en la nueva prueba real PASSED documentada al final. La regresión PowerShell ampliada sigue sin un resultado nuevo registrado aquí. El JAR original conservó su SHA-256; no se reinició el servidor de 8080 durante el diagnóstico.

### Próximo paso propuesto: consultar el recurso antes de subir otra imagen

Después de reingresar y validar localmente las credenciales, pedir autorización para **una consulta GET únicamente** de `foodly/business/58ec0ee8-1349-4197-adf1-4b0de8285ae7`, con Basic Auth en cabecera, sin imprimirla. No ejecutar el script de subida para esta comprobación, porque crea otro UUID.

- HTTP 200: comprobar que `public_id` coincide; registrar formato, dimensiones y estado de existencia, sin modificarlo.
- HTTP 404 con autenticación/configuración correctas: registrar ausencia para ese ID y tipo de recurso `image/upload`.
- HTTP 401, 403, 429 u otro error: mantener estado desconocido y guardar únicamente el nuevo diagnóstico sanitizado. No interpretar un fallo de autorización como ausencia.

Si existe, proponer por separado la limpieza exclusivamente de ese ID. No se ejecutó ninguna de estas operaciones durante el diagnóstico.

## Consulta posterior de solo lectura: HTTP 404 confirmado

El 13 de noviembre de 2026, con autorización expresa limitada a una consulta, se ejecutó **un único GET real** mediante `scripts/get-cloudinary-resource.ps1`. El verificador de subida no se utilizó. El nuevo script carga las credenciales DPAPI mediante `cloudinary.ps1`, valida su formato local sin mostrarlas y consulta el endpoint Admin de `image/upload` con Basic Auth en cabecera. No usa redirecciones, reintentos ni métodos de escritura.

Resultado observado:

| Campo | Valor |
|---|---|
| Resultado | `NOT_FOUND` |
| HTTP | `404` |
| Public ID | `foodly/business/58ec0ee8-1349-4197-adf1-4b0de8285ae7` |
| Tipo de recurso | `image` / entrega `upload` |
| Diagnóstico | `RESOURCE_NOT_FOUND` |

El recurso no se encontró en la cuenta configurada y el tipo consultado. No hay una imagen encontrada que limpiar; esto no acredita el éxito del intento de eliminación anterior ni reconstruye el estado histórico. La consulta no modificó recursos externos. El archivo cifrado y el JAR conservaron sus hashes; el proceso de 8080 conservó su identidad. No se accedió a MongoDB/RabbitMQ ni se inició un worker.

Uso del modo preparado, solo con autorización para el ID correspondiente:

```powershell
.\scripts\get-cloudinary-resource.ps1 -PublicId 'foodly/business/58ec0ee8-1349-4197-adf1-4b0de8285ae7' -ReadOnlyRequestApproved
```

No repetir este comando para el ID ya verificado sin una necesidad y autorización nuevas. HTTP 200 exige ID coincidente y metadatos `image/upload`; devuelve solo los campos permitidos. HTTP 401/403, 429, otras respuestas o fallos de transporte dan `ERROR` y un diagnóstico sanitizado. Solo HTTP 404 da `NOT_FOUND`.

Prueba local del parser y la guardia, sin credenciales ni solicitudes:

```powershell
.\scripts\test-cloudinary-readonly.ps1
```

**11 comprobaciones correctas**: 200/404/401/403/429/500/302, metadatos inválidos, campos adicionales excluidos y rechazo sin autorización. Los dos scripts nuevos pasan el parser de PowerShell. No se repitió Maven para este cambio limitado a scripts.

## Nueva prueba completa autorizada: PASSED

El 13 de noviembre de 2026 se ejecutó una sola vez `scripts/test-cloudinary.ps1 -ExternalOperationsApproved`, usando las credenciales DPAPI corregidas. El helper compilado empleó el adaptador de producción real, sin mocks, sin iniciar Spring Boot ni el worker, y creó únicamente una PNG de 1x1 con el public ID nuevo:

`foodly/business/29ed5779-d302-47da-9dd7-af211914149b`

| Indicador | Resultado |
|---|---|
| `uploadConfirmed` | `true`: creación confirmada por la respuesta de subida |
| `adminReadConfirmed` | `true`: public ID coincidente, formato PNG y dimensiones 1x1 |
| `destroyConfirmed` | `true`: confirmación de eliminación del recurso de esta ejecución |
| `adminAbsentConfirmed` | `true`: consulta Admin posterior respondió HTTP 404 |

Estado final **PASSED**. No hubo errores ni reintentos de subida y no quedan recursos pendientes de esta ejecución. No se consultaron ni modificaron recursos distintos al fixture nuevo. El proceso de 8080, su JAR y el archivo cifrado conservaron identidad de proceso y hashes. No se modificaron las instancias persistentes de MongoDB/RabbitMQ ni se procesaron sus eventos.

Evidencia local sin credenciales ni firmas, ignorada por Git:

- Recibo original: `.local/cloudinary/verification-642fe019-4556-41f5-96ed-f4cb52807139.json`.
- Informe consolidado: `.local/cloudinary/summary-bf3518f18bb442ebbef075ee1a521f73.json`.
- Maven: `.local/cv-83628373/target/surefire-reports/`.

Maven `mvnw.cmd -o clean verify` terminó **BUILD SUCCESS: 144 exitosas, 0 fallidas, 0 errores y 0 omitidas**. La ejecución offline usó una copia corta aislada para conservar el JAR original en uso. Los 62 archivos de código, configuración y wrapper coinciden por SHA-256 con el workspace. Incluye las ocho pruebas MongoDB con su instancia temporal propia; no usan la base de desarrollo. Las pruebas Maven simuladas se distinguen de la prueba real Cloudinary descrita aquí.

## Verificación realizada

`clean verify` se ejecuta con `FOODLY_MONGOD` apuntando al ejecutable instalado para incluir las ocho pruebas de MongoDB real temporal. Se usa una copia aislada de `pom.xml`, `src` y wrapper en `.local/maven-cloudinary-verification-*` porque el JAR original está en uso por el servidor que debe conservarse en 8080.

La suite incluye validaciones de referencias y archivos, asociación a huarique/plato, consultas, permisos, límites de galerías, reemplazo, encolado de limpieza, compensación y errores HTTP. Sus pruebas de almacenamiento y controladores emplean mocks y se identifican como tales. Los resultados exactos y el alcance de la ejecución se entregan con los reportes Surefire; no equivalen a una carga real en Cloudinary.

Resultado del 13 de noviembre de 2026: **127 exitosas, 0 fallidas, 0 errores y 0 omitidas**, `BUILD SUCCESS`. Incluye 31 pruebas nuevas respecto de las 96 anteriores y las ocho pruebas MongoDB reales temporales. Reportes de esta ejecución: `.local/maven-cloudinary-verification-e3d0efb0c255473d92263deac48c3b9c/target/surefire-reports/`. Se compararon por SHA-256 los 60 archivos de código/configuración/wrapper ejecutados con sus originales: sin diferencias.

`test-cloudinary.ps1 -PrepareOnly` completó correctamente la compilación y generación de PNG, sin solicitudes externas. También se comprobó el rechazo de ejecución sin autorización. El JAR original conservó su SHA-256 y el servidor de 8080 su identidad de proceso; no se desplegaron estos cambios allí. No se ejecutó el worker contra eventos existentes.

## Archivos de esta revisión

| Archivos | Cambio |
|---|---|
| `src/main/java/com/foodly/business/infrastructure/images/ImageStorageAdapter.java` | Contrato firmado, compensación del UUID solicitado, rechazo de credenciales malformadas y diagnóstico sanitizado |
| `src/main/java/com/foodly/business/infrastructure/images/CloudinaryIntegrationException.java` | Metadata interna permitida de HTTP/transporte, sin conservar excepciones/cuerpos originales |
| `src/test/java/com/foodly/business/infrastructure/CloudinaryDiagnosticsTest.java` | 17 regresiones de códigos, causas, firma, compensación y credenciales inválidas sin solicitudes |
| `src/main/java/com/foodly/business/domain/model/ImageReference.java` | URL HTTPS absoluta con host y sin credenciales |
| `src/test/java/com/foodly/business/infrastructure/ImageStorageAdapterTest.java` | Contratos firmados, validaciones, errores y compensación; incluye respuesta con ID ajeno |
| `src/test/java/com/foodly/business/domain/ImageReferenceTest.java` | Cuatro casos de URL inválida |
| `src/test/java/com/foodly/business/application/GalleryOperationsTest.java` | Asociación, permisos, consulta, límites y limpieza encolada |
| `src/test/java/com/foodly/business/interfaces/rest/BusinessControllersTest.java` | Multipart, respuestas HTTP y autorización de imágenes |
| `scripts/cloudinary.ps1` | Configuración privada, carga de variables y estado sin secretos |
| `scripts/test-cloudinary-credentials.ps1` | Regresión no elevada con DACL/DPAPI reales y fallo de permisos seguro |
| `scripts/test-cloudinary.ps1` | Preparación local y guardia de autorización de la prueba real |
| `scripts/java/CloudinaryVerification.java` | Probe real con adaptador de producción, recibo y limpieza del fixture nuevo |
| `.env.example`, `README.md`, `docs/cloudinary.md` | Variables, ejecución, evidencias y límites documentados |
