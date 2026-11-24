# Contrato JWT: IAM y Business

Estado (24 de noviembre de 2026): **contrato v1 alineado en código por ambos lados; pendiente de fusión y de la prueba real**. El PR [feature/jwt-issuer-audience de Foodly-IAM-Service (#3)](https://github.com/1ASI0728-2620-9052-FoodNode/Foodly-IAM-Service/pull/3) (rama en `026f07c`) hace que IAM emita `iss` y `aud` y exige ambos al validar; la rama `feature/iam-jwt-alignment` de Business adopta como predeterminados `iss=foodly-iam`, `aud=foodly-business` y rol `HUARIQUE_OWNER`. **Mientras el PR de IAM no esté fusionado y desplegado, IAM `develop` sigue sin emitir `iss`/`aud`** y la tabla de compatibilidad y la evidencia siguientes describen ese estado anterior. Historial: revisión inicial de `develop` realizada el 13 de noviembre de 2026 en `850c6d1f45398f3dc5592920d84520146f880c3e`. Revalidación del emisor el 24 de noviembre de 2026 en `df6ac4581579b98eee67ab4dc55e78771d0f77f5`: continúa firmando HS256 sin `iss` ni `aud`. La rama avanzó; las referencias iniciales se conservan como evidencia histórica, no como su HEAD actual.

Fuente revalidada: [JwtTokenIssuer.java en el SHA actual revisado](https://github.com/1ASI0728-2620-9052-FoodNode/Foodly-IAM-Service/blob/df6ac4581579b98eee67ab4dc55e78771d0f77f5/src/main/java/com/foodly/iam/infrastructure/security/JwtTokenIssuer.java).

Business conserva la validación criptográfica, issuer, audience, expiración y propiedad; no se relajó ninguna. Su alineación solo cambia valores predeterminados de configuración. No se obtuvieron secretos reales ni tokens de usuarios.

## Evidencia del productor

El [JwtTokenIssuer revisado](https://github.com/1ASI0728-2620-9052-FoodNode/Foodly-IAM-Service/blob/850c6d1f45398f3dc5592920d84520146f880c3e/src/main/java/com/foodly/iam/infrastructure/security/JwtTokenIssuer.java) decodifica `JWT_SECRET_BASE64`, exige al menos 32 bytes y firma explícitamente con `Jwts.SIG.HS256`. Emite `sub`, `role`, `iat` y `exp`, sin `iss` ni `aud`. Valida que la expiración configurada esté entre 1 y 60 minutos.

El [enum Role](https://github.com/1ASI0728-2620-9052-FoodNode/Foodly-IAM-Service/blob/850c6d1f45398f3dc5592920d84520146f880c3e/src/main/java/com/foodly/iam/domain/model/enums/Role.java) contiene `HUARIQUE_OWNER` y `GASTRONOMIC_EXPLORER`. El [POM IAM](https://github.com/1ASI0728-2620-9052-FoodNode/Foodly-IAM-Service/blob/850c6d1f45398f3dc5592920d84520146f880c3e/pom.xml) fija JJWT **0.13.0**. No se ejecutó ni modificó ese proyecto.

El [AuthController](https://github.com/1ASI0728-2620-9052-FoodNode/Foodly-IAM-Service/blob/850c6d1f45398f3dc5592920d84520146f880c3e/src/main/java/com/foodly/iam/interfaces/controllers/AuthController.java) expone `POST /iam/auth/login` y `POST /iam/auth/register`. [AuthResponseResource](https://github.com/1ASI0728-2620-9052-FoodNode/Foodly-IAM-Service/blob/850c6d1f45398f3dc5592920d84520146f880c3e/src/main/java/com/foodly/iam/interfaces/resources/AuthResponseResource.java) entrega `token`, `tokenType` (`Bearer`), `expiresAt`, `userId`, `name` y `role`. El cliente debe enviar el campo `token` como `Authorization: Bearer <token>` a Business; no enviar todo el JSON ni encabezados de identidad inventados.

## Compatibilidad actual

| Aspecto | IAM develop | Business actual | Resultado |
|---|---|---|---|
| Algoritmo | Emisión fijada a HS256 | Decoder fijado a HS256 | Compatible; HS384/HS512/none no se aceptan en Business |
| Firma y clave | JJWT; Base64 estándar decodificado, mínimo 32 bytes | Nimbus; Base64 estándar decodificado, mínimo 32 bytes | Compatible si se provisionan exactamente los mismos bytes; igualdad de claves reales no comprobada |
| `iss` | `develop`: no emitido. PR #3: `foodly-iam` | Obligatorio; coincidencia exacta con `IAM_JWT_ISSUER` (predeterminado `foodly-iam`) | `develop`: bloqueante, HTTP 401. Con el PR #3: compatible |
| `aud` | `develop`: no emitido. PR #3: `foodly-iam`, `foodly-business`, `foodly-radar`, `foodly-messaging`, `foodly-reservation`, `foodly-community` | Obligatorio; debe incluir `IAM_JWT_AUDIENCE` (predeterminado `foodly-business`) | `develop`: bloqueante, HTTP 401. Con el PR #3: compatible |
| `sub` | UUID del usuario como cadena | Cadena no vacía, hasta 128 caracteres | Compatible con UUID de 36 caracteres; Business no exige UUID |
| `role` | Una cadena: `HUARIQUE_OWNER` o `GASTRONOMIC_EXPLORER` | Lee `role` y también `roles`; permiso de dueño configurable | Compatible: el predeterminado de Business ya es `HUARIQUE_OWNER` |
| Prefijo del rol | Payload sin `ROLE_`; IAM añade el prefijo internamente al autenticar | Añade `ROLE_` internamente | No introducir el prefijo en el claim |
| `exp` | Emitido, 1–60 minutos; configuración actual 60 | Obligatorio; se comprueba expiración con tolerancia de 60 s | Compatible; Business no impone un máximo de TTL de 60 minutos |
| `iat` | Emitido como NumericDate | No obligatorio ni usado para limitar TTL | Regla obligatoria de emisión en la propuesta; no afirmar validación equivalente en Business |
| `nbf` | No emitido | Se valida si está presente | Compatible; opcional en la propuesta |
| Propiedad | `sub` corresponde a `userId` | `ownerId` deriva del subject; operaciones existentes comparan ambos | El rol de dueño por sí solo no autoriza recursos ajenos |

El parser IAM actual verifica con la clave, interpreta el subject como UUID y comprueba el rol; no configura requisitos de issuer/audience ni un algoritmo permitido único en su parser. La emisión sí fija HS256. Esa validación interna debe revisarse junto con la emisión cuando IAM adopte el contrato, sin modificarla desde Business.

## Contrato JWT v1

Los identificadores siguientes son los valores implementados en el PR #3 de IAM y en los predeterminados de Business; **no están desplegados en un IAM real hasta que ese PR se fusione**. Son identificadores exactos, no URLs de discovery o llamadas a un servicio.

Cabecera: `alg=HS256`. `typ=JWT` puede emitirse para claridad; Business actual no lo exige. No introducir otro algoritmo negociado a partir de la cabecera del token.

Payload ilustrativo, sin firma ni credenciales; las fechas son ejemplos fijos:

```json
{
  "iss": "foodly-iam",
  "aud": ["foodly-iam", "foodly-business", "foodly-radar", "foodly-messaging", "foodly-reservation", "foodly-community"],
  "sub": "a519c2ae-9ac5-40ac-9149-470bf3bb9e96",
  "role": "HUARIQUE_OWNER",
  "iat": 1794614400,
  "exp": 1794618000
}
```

- IAM emite `iss` exacto `foodly-iam` y un `aud` configurado en el servidor. Si el mismo token sirve para consultar el perfil IAM y Business, incluir ambos destinatarios. Business exige `foodly-business`; IAM debería exigir `foodly-iam` en sus propias rutas. No permitir al cliente elegir destinatarios arbitrarios.
- `sub` es el UUID estable del usuario, igual a `userId` en la respuesta IAM. No usar correo ni nombre visible como identidad. Business conserva su representación opaca y su límite de 128 caracteres.
- IAM emite un `role` singular, con uno de los valores exactos del enum y sin prefijo `ROLE_`. No emitir simultáneamente una lista `roles` contradictoria. Business conserva por ahora su soporte de la lista por compatibilidad; el contrato canónico usa `role`.
- IAM emite `iat` y `exp` como NumericDate en segundos UTC: `0 < exp - iat <= 3600`. No enviar milisegundos. Si se usa `nbf`, expresar también segundos y no retrasar sin intención el acceso.
- Business mantiene verificación de firma/issuer/audience/exp y `nbf` si existe. Su tolerancia actual es 60 s. Acordar y configurar explícitamente la tolerancia del consumidor IAM: su parser actual no establece `clockSkewSeconds`. Mantener los relojes sincronizados.
- El emisor proporciona siempre un rol. La política actual del consumidor permite GET autenticado sin rol y exige el rol de dueño para `/me` y escrituras; esos casos dan 403 si el permiso falta. No se promete que un token sin rol reciba 401 en todas las rutas.

Los requisitos de emisión sobre `iat`, TTL y rol no equivalen a una validación integral de esquema en Business. Requerir `iat`, imponer TTL máximo también en el consumidor, rechazar todos los roles desconocidos o retirar el formato `roles` requeriría otro cambio explícito de política y sus pruebas. No son necesarios para que los tokens válidos del contrato propuesto interoperen, y no se aplicaron en esta tarea.

## Cambios que necesita cada servicio

### IAM, para su integrante responsable

1. Añadir configuración externa de issuer y destinatarios: `JWT_ISSUER=foodly-iam` y `JWT_AUDIENCES=foodly-iam,foodly-business,foodly-radar,foodly-messaging,foodly-reservation,foodly-community`, enlazados a `foodly.jwt.issuer` y `foodly.jwt.audiences`. **Hecho en el PR #3 de IAM** (pendiente de fusión).
2. En `issue`, añadir issuer y audiencia a los claims, manteniendo HS256 explícito, UUID, rol, `iat` y `exp`. **Hecho en el PR #3.**
3. Al validar en IAM, exigir el issuer y el destinatario `foodly-iam`, conservar firma, expiración, UUID y enum de rol y fijar la tolerancia en 60 s. **Hecho en el PR #3.** No se añadió una lista blanca de algoritmos al parser de IAM: sigue pendiente.
4. Pruebas de emisión/validación de `iss` y `aud`, issuer incorrecto, audiencia ausente o sin `foodly-iam`, tolerancia y arranque con configuración inválida: **hechas en el PR #3** (`JwtTokenIssuerTest`). Comprobar que `expiresAt` en la respuesta coincide con `exp` sigue pendiente.
5. Provisionar la clave por un mecanismo privado para los dos procesos. No enviarla en chat, pull requests, logs, parámetros de comandos ni archivos versionados. **Pendiente (operación).**

El código IAM de este contrato vive en su propio repositorio y PR; esta rama de Business no lo modifica.

### Business

No necesita relajar ni sustituir el decoder. Sus predeterminados ya coinciden con el contrato; solo la clave se provisiona por entorno y **no tiene valor por defecto**:

| Business | IAM | Valor |
|---|---|---|
| `IAM_JWT_SECRET_BASE64` | `JWT_SECRET_BASE64` | Mismo valor Base64 que representa la misma clave aleatoria de al menos 32 bytes |
| `IAM_JWT_ISSUER` (predeterminado `foodly-iam`) | `JWT_ISSUER` | `foodly-iam` |
| `IAM_JWT_AUDIENCE` (predeterminado `foodly-business`) | `JWT_AUDIENCES` | Business usa `foodly-business`; IAM emite la lista acordada |
| `IAM_OWNER_ROLE` (predeterminado `HUARIQUE_OWNER`) | `Role.HUARIQUE_OWNER` | `HUARIQUE_OWNER` |

No comparar ni reutilizar claves de tests. La clave local de `scripts/local-auth.ps1` tiene issuer/audience independientes; ese launcher carga su propio contrato y no debe usarse para configurar IAM real. La configuración del servidor actual en 8080 permanece intacta. No se provisionó la clave real IAM ni se verificó que los procesos compartan sus bytes.

## Pruebas preparadas y realizadas

`IamJwtInteroperabilityTest` usa la misma biblioteca y versión de firma que el productor revisado: **JJWT 0.13.0**, únicamente como dependencia `test`. Sus fixtures reproducen los claims actuales y el contrato propuesto; el decoder Nimbus y los filtros de Spring Security son reales. Usa también el servicio de aplicación y la política de propietario reales, con repositorio/Outbox simulados. No es un IAM nuevo ni prueba un login desplegado, transacciones o persistencia real.

Las 30 comprobaciones originales cubren:

- Token propuesto de dueño aceptado, registro que deriva `ownerId` del `sub`, cambio del recurso propio y 403 para otro usuario con el mismo rol.
- Explorador con consulta permitida y ruta de dueño denegada. Rechazo de `OWNER`, `ROLE_HUARIQUE_OWNER`, minúsculas y rol desconocido en rutas administrativas.
- HTTP 401 para el formato IAM actual, issuer/audience ausentes o incorrectos, expiración ausente/vencida, subject ausente/blanco/largo, `nbf` futuro, clave distinta, uso incorrecto del texto Base64 como clave, HS384 y token sin firma. Esas peticiones no llegan a la aplicación.
- JJWT → Nimbus con Base64 decodificado y claves efímeras de 32, 48 y 64 bytes, manteniendo HS256 explícito.
- Caracterización de límites actuales: `iat` ausente, TTL de dos horas y lectura autenticada sin rol siguen siendo admitidos; la ruta de dueño sin rol permanece denegada. Estas pruebas documentan comportamiento existente, no desactivan controles.

Resultado: **`clean verify`: 174 exitosas, 0 fallidas, 0 errores y 0 omitidas**, 30 más que las 144 anteriores. Incluye las ocho pruebas MongoDB con su replica set temporal propio. Se ejecutó una copia aislada en `.local/iam-1437e86e/` para conservar el JAR original. Reportes: `.local/iam-1437e86e/target/surefire-reports/`. Las claves de prueba se generan en memoria, no se usan secretos reales y no se imprimen JWT. JJWT no pasa al classpath de producción.

Se compararon por SHA-256 los 63 archivos de código, configuración y wrapper: sin diferencias entre la copia verificada y el workspace. El JAR empaquetado no incluye bibliotecas JJWT y el JAR original conserva su hash; en aquella ejecución el proceso Java original de 8080 conservó su identidad. Los archivos cambiados en esta tarea son `pom.xml`, `IamJwtInteroperabilityTest.java`, esta guía, `docs/contracts.md`, `docs/local-auth.md`, `.env.example` y `README.md`. No se alteró `SecurityConfiguration.java` ni otra lógica de producción.

**Actualización de la alineación (24 de noviembre de 2026).** `IamJwtInteroperabilityTest` pasa a 32 comprobaciones. Sus tokens reproducen la emisión del PR #3 de IAM (HS256, `iss=foodly-iam`, `aud` con las seis audiencias, `sub` UUID, `role`, `iat` y `exp` de 60 min) y ya no fijan issuer, audiencia ni rol por propiedades: usan los predeterminados de `application.properties`, así que también prueban que coinciden con IAM. Casos nuevos: un token de `HUARIQUE_OWNER` hace `POST /business-api/huariques` → 201 con `ownerId` igual al `sub`, y uno de `GASTRONOMIC_EXPLORER` → 403 sin guardar nada ni escribir en el outbox. Al cambiar el predeterminado de `OWNER` a `HUARIQUE_OWNER`, `BusinessControllersTest` se actualizó a `ROLE_HUARIQUE_OWNER`. La suite de esta rama se ejecutó **sin MongoDB**: 168 exitosas, 0 fallidas y 8 omitidas (`MongoTransactionsTest`); el último `clean verify` completo, con MongoDB, fue el de 174 de arriba y no se repitió.

Ejecución reproducible desde una copia aislada; la ruta del ejecutable es un ejemplo que debe ajustarse:

```powershell
$env:FOODLY_MONGOD = 'C:/tools/mongodb/bin/mongod.exe'
.\mvnw.cmd clean verify
```

## Pendientes para acreditar la integración real

1. Fusionar el PR #3 de IAM (`feature/jwt-issuer-audience`) en su `develop`. Volver a fijar el SHA revisado de la sección de evidencia, que describe `develop` antes de ese PR.
2. Provisionar privadamente la misma clave y configuración en un entorno de prueba controlado de ambos servicios. No reconfigurar ni reiniciar el 8080 existente como parte de esta preparación.
3. Obtener un token auténtico mediante `POST /iam/auth/login` con una cuenta de prueba existente y autorizada. Mantener contraseña y bearer en memoria, sin registrarlos ni crear usuarios innecesariamente.
4. Verificar con ese token GET autenticado y `/business-api/huariques/me`: un dueño autorizado obtiene 200 o 404 si aún no tiene huarique; el explorador recibe 403 en `/me`. Falta de credenciales o firma/claims inválidos: 401. La propiedad de recursos ajenos sigue siendo 403.
5. Ejecutar los casos de escritura/propiedad en fixtures aislados expresamente autorizados. Las fixtures locales de esta revisión no acreditan ejecución remota ni modificación de datos reales.

No se llamó a un IAM desplegado, no se obtuvieron tokens reales y no se verificó revocación inmediata por cambio de estado del usuario. Business valida el JWT localmente hasta su expiración/tolerancia; cualquier política de revocación coordinada requiere un contrato adicional. La integración real continúa pendiente de la fusión del productor y de la prueba anterior.
