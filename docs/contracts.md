# Contratos de integración y alcance

## Trazabilidad

RF-04: perfil, cocina, etiquetas, precios, ubicación y horarios. RF-05: galerías de huariques/platos, subidas Cloudinary y eliminación diferida. RF-06: reemplazo de carta y cambios puntuales de precio/disponibilidad. RF-07: consultas autenticadas de perfil, galerías y carta.

Se conservan las relaciones y tipos de los diagramas 5.3. `OpeningHours`, `ImageReference`, etiquetas y versiones amplían el modelo mínimo para RF-04/RF-05 y concurrencia. `business_outbox` es la colección adicional DD-08. DD-02/DD-03 se concretan en MongoDB y REST/RabbitMQ. DD-06 se implementa en el servicio con JWT/propietario; Kong es configuración externa.

## IAM: contrato preparado, pendiente de coordinar

La [comparación actualizada y propuesta JWT v1](iam-business-jwt.md) fija el SHA revisado, detalla los cambios por servicio y registra 32 pruebas de compatibilidad JJWT 0.13.0 → Nimbus. Los valores propuestos de issuer/audience todavía requieren acuerdo e implementación en IAM; no se verificó un login remoto.

Business valida JWT; no emite tokens ni administra credenciales/cuentas. El informe indica validación independiente de firma/expiración mediante clave compartida, sin especificar algoritmo. La revisión inicial de IAM `develop` en `850c6d1f45398f3dc5592920d84520146f880c3e` confirmó firma explícita HS256 y clave Base64 externa de al menos 32 bytes. El emisor se revalidó el 24 de noviembre de 2026 en `df6ac4581579b98eee67ab4dc55e78771d0f77f5`, manteniendo ese comportamiento. Sus tokens de `develop` aún no incluyen `iss` ni `aud`; el PR `feature/jwt-issuer-audience` de IAM los añade y **alinea el contrato** con la validación estricta de Business (se vuelve compatible al fusionarse y desplegarse). El [análisis y configuración local](local-auth.md) enlaza la evidencia exacta.

Claims obligatorios: `sub` (ID opaco de hasta 128 caracteres), `iss`, `aud`, `exp`. Se valida `nbf` cuando existe. Se usa la tolerancia predeterminada de Spring Security (60 s). Roles: `role` o `roles`, configurable con `IAM_OWNER_ROLE`; el código IAM revisado usa `HUARIQUE_OWNER` y `GASTRONOMIC_EXPLORER`, y Business usa ahora ese `HUARIQUE_OWNER` como predeterminado. Issuer (`foodly-iam`) y audience (`foodly-business`) son configurables y sus predeterminados coinciden con IAM. No se exige UUID a `sub`, para desacoplar su persistencia. La validación de esos claims no se desactiva para aceptar el formato anterior de IAM.

Si IAM usa otro algoritmo/claims, adaptar la configuración de seguridad tras acordar el contrato. Conservar validación criptográfica y propiedad; no reemplazarlas con `X-Owner-Id`. Se prueban localmente tokens firmados, expirados y de firma/issuer/audience incorrectos. La interoperabilidad con IAM externo está pendiente.

## Eventos Business v1

**Decisión del equipo (24 de noviembre de 2026):** los eventos conservan sus nombres en español porque coinciden con el Event Storming y el Ubiquitous Language. Business **no publica alias en inglés**: Radar y Community se adaptan a estos nombres. Esta sección describe lo que el código publica hoy (`HuariqueApplicationService`, `MenuApplicationService`, `MongoEventOutbox`, `OutboxPublisher`, `BusinessEventPublisher`); los esquemas se validaron contra el JSON que serializa el código.

### Transporte

| Elemento | Valor |
|---|---|
| Exchange | `foodly.business`, topic, durable (configurable con `BUSINESS_EVENTS_EXCHANGE`) |
| Routing key | `business.<Tipo>`, por ejemplo `business.HuariqueRegistrado` |
| `messageId` AMQP | El `eventId` del sobre. **Es la clave de idempotencia** y se conserva idéntico en cada reintento |
| `type` AMQP | `<Tipo>` |
| Cabecera `schemaVersion` | `1` (también va en el cuerpo) |
| Cuerpo | JSON UTF-8, `content-type: application/json`, mensaje persistente |
| Garantía | Al menos una vez (publisher confirms + `mandatory`). Puede haber duplicados y reordenamientos |
| Declaración | Business declara **solo el exchange**. Las colas, las DLQ y los bindings los declara cada consumidor |

| Tipo | Routing key | `data` |
|---|---|---|
| `HuariqueRegistrado` | `business.HuariqueRegistrado` | Huarique completo |
| `PerfilDelHuariqueActualizado` | `business.PerfilDelHuariqueActualizado` | Huarique completo |
| `EstadoAbiertoCerradoCambiado` | `business.EstadoAbiertoCerradoCambiado` | Huarique completo |
| `MenuCreado` | `business.MenuCreado` | Menú completo |
| `MenuActualizado` | `business.MenuActualizado` | Menú completo |

### Sobre del mensaje

| Campo | Tipo | Descripción |
|---|---|---|
| `eventId` | UUID | Identificador único del evento. Igual al `messageId` AMQP; estable en reintentos |
| `type` | string | Uno de los cinco tipos de la tabla anterior |
| `schemaVersion` | entero | `1` |
| `huariqueId` | UUID | Huarique al que pertenece el evento. En eventos de menú, el huarique dueño de la carta |
| `aggregateVersion` | entero ≥ 0 | Versión del agregado **después** de guardar. Es igual a `data.version`. Se usa para descartar eventos viejos |
| `occurredAt` | date-time ISO-8601 UTC | Momento en que Business registró el evento |
| `data` | objeto | **Snapshot completo** del agregado tras el cambio (no un delta) |

### Idempotencia y descarte de eventos viejos

1. **Duplicados:** guardar los `messageId` (`eventId`) ya procesados. Si llega uno repetido, confirmar (ack) sin aplicar nada.
2. **Orden:** guardar la última `aggregateVersion` por agregado y descartar (con ack) todo evento con `aggregateVersion` menor o igual. Hay **dos secuencias independientes** por huarique: la del Huarique (`HuariqueRegistrado`, `PerfilDelHuariqueActualizado`, `EstadoAbiertoCerradoCambiado`; clave sugerida `huarique:<huariqueId>`) y la de su carta (`MenuCreado`, `MenuActualizado`; clave `menu:<huariqueId>`). No se comparan entre sí.
3. **Versiones salteadas:** no asumir que las versiones son consecutivas. Como `data` es un snapshot completo, basta aplicar el de mayor versión; no hace falta haber visto los intermedios.
4. **Aplicación idempotente:** hacer upsert del snapshot; aplicar dos veces el mismo evento no debe cambiar el resultado.
5. **Tolerancia:** los consumidores deben **ignorar campos desconocidos**. Business puede añadir campos opcionales sin cambiar `schemaVersion`. Regla propuesta: un cambio incompatible sube `schemaVersion` y se anuncia a los consumidores antes de publicarlo.

### Cuándo se emite cada evento

| Tipo | Se emite cuando | Notas |
|---|---|---|
| `HuariqueRegistrado` | `POST /business-api/huariques` | Siempre `openStatus = CLOSED` y `aggregateVersion = 0` |
| `PerfilDelHuariqueActualizado` | `PUT /business-api/huariques/{id}` (perfil, **incluida la ubicación**) y cualquier cambio de la galería del huarique (subir, reemplazar o eliminar imagen) | No indica qué campo cambió: comparar con el estado propio |
| `EstadoAbiertoCerradoCambiado` | `PATCH /business-api/huariques/{id}/open-status` | Solo si el estado realmente cambia; pedir el mismo estado no emite evento |
| `MenuCreado` | `PUT .../menu` cuando el huarique aún no tiene carta | Distingue la primera carta |
| `MenuActualizado` | `PUT .../menu` con carta existente, cambio de precio o de disponibilidad de un plato, y cambios de imagen de un plato | |

Los registros `internal.ImageDeletionRequested` del outbox son un detalle interno (borrado en Cloudinary): **no se publican** en el exchange y no forman parte del contrato.

### Esquemas JSON

Los esquemas usan JSON Schema 2020-12. Las identificaciones `urn:foodly:business:v1:*` son nombres lógicos, no URL que se deba descargar. JSON Schema no puede expresar estas igualdades, que Business garantiza: `data.id` (huarique) o `data.huariqueId` (carta) es igual a `huariqueId`, y `data.version` es igual a `aggregateVersion`. Los precios son números JSON con hasta dos decimales.

<details>
<summary>Definiciones comunes (<code>urn:foodly:business:v1:common</code>)</summary>

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:foodly:business:v1:common",
  "title": "Definiciones comunes de Business v1",
  "$defs": {
    "Uuid": {"type": "string", "format": "uuid"},
    "Image": {
      "type": "object",
      "required": ["id", "publicId", "url"],
      "properties": {
        "id": {"$ref": "#/$defs/Uuid"},
        "publicId": {"type": "string", "minLength": 1, "maxLength": 255},
        "url": {"type": "string", "format": "uri", "pattern": "^https://", "maxLength": 2048}
      }
    },
    "GeoCoordinates": {
      "type": "object",
      "required": ["latitude", "longitude"],
      "properties": {
        "latitude": {"type": "number", "minimum": -90, "maximum": 90},
        "longitude": {"type": "number", "minimum": -180, "maximum": 180}
      }
    },
    "OpenStatus": {"enum": ["OPEN", "CLOSED"]},
    "OpeningHours": {
      "type": "object",
      "required": ["day", "opensAt", "closesAt"],
      "properties": {
        "day": {"enum": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"]},
        "opensAt": {"type": "string", "pattern": "^\\d{2}:\\d{2}:\\d{2}$"},
        "closesAt": {"type": "string", "pattern": "^\\d{2}:\\d{2}:\\d{2}$"}
      }
    },
    "Huarique": {
      "type": "object",
      "required": [
        "id",
        "ownerId",
        "name",
        "address",
        "cuisineType",
        "priceRange",
        "location",
        "openStatus",
        "tags",
        "openingHours",
        "images",
        "version"
      ],
      "properties": {
        "id": {"$ref": "#/$defs/Uuid"},
        "ownerId": {"type": "string", "minLength": 1, "maxLength": 128},
        "name": {"type": "string", "minLength": 1, "maxLength": 150},
        "address": {"type": "string", "minLength": 1, "maxLength": 300},
        "cuisineType": {"enum": ["CRIOLLA", "MARINA", "CHIFA", "OTHER"]},
        "priceRange": {
          "type": "object",
          "required": ["minimum", "maximum"],
          "properties": {
            "minimum": {"type": "number", "minimum": 0, "maximum": 99999999.99, "multipleOf": 0.01},
            "maximum": {"type": "number", "minimum": 0, "maximum": 99999999.99, "multipleOf": 0.01}
          }
        },
        "location": {"$ref": "#/$defs/GeoCoordinates"},
        "openStatus": {"$ref": "#/$defs/OpenStatus"},
        "tags": {
          "type": "array",
          "maxItems": 20,
          "uniqueItems": true,
          "items": {"type": "string", "minLength": 1, "maxLength": 40}
        },
        "openingHours": {"type": "array", "maxItems": 28, "items": {"$ref": "#/$defs/OpeningHours"}},
        "images": {"type": "array", "maxItems": 20, "items": {"$ref": "#/$defs/Image"}},
        "version": {"type": "integer", "minimum": 0}
      }
    },
    "Dish": {
      "type": "object",
      "required": ["id", "name", "description", "price", "available", "images"],
      "properties": {
        "id": {"$ref": "#/$defs/Uuid"},
        "name": {"type": "string", "minLength": 1, "maxLength": 150},
        "description": {"type": "string", "maxLength": 2000},
        "price": {"type": "number", "minimum": 0, "maximum": 99999999.99, "multipleOf": 0.01},
        "available": {"type": "boolean"},
        "images": {"type": "array", "maxItems": 10, "items": {"$ref": "#/$defs/Image"}}
      }
    },
    "MenuCategory": {
      "type": "object",
      "required": ["id", "name", "dishes"],
      "properties": {
        "id": {"$ref": "#/$defs/Uuid"},
        "name": {"type": "string", "minLength": 1, "maxLength": 100},
        "dishes": {"type": "array", "maxItems": 100, "items": {"$ref": "#/$defs/Dish"}}
      }
    },
    "Menu": {
      "type": "object",
      "required": ["id", "huariqueId", "categories", "version"],
      "properties": {
        "id": {"$ref": "#/$defs/Uuid"},
        "huariqueId": {"$ref": "#/$defs/Uuid"},
        "categories": {"type": "array", "maxItems": 30, "items": {"$ref": "#/$defs/MenuCategory"}},
        "version": {"type": "integer", "minimum": 0}
      }
    }
  }
}
```

</details>

#### HuariqueRegistrado

Un dueño registró su huarique. Nace `CLOSED`. Routing key: `business.HuariqueRegistrado`. Exchange: `foodly.business`.

<details>
<summary>Esquema JSON</summary>

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:foodly:business:v1:HuariqueRegistrado",
  "title": "HuariqueRegistrado",
  "description": "Un dueño registró su huarique.",
  "type": "object",
  "required": ["eventId", "type", "schemaVersion", "huariqueId", "aggregateVersion", "occurredAt", "data"],
  "properties": {
    "eventId": {"$ref": "urn:foodly:business:v1:common#/$defs/Uuid"},
    "type": {"const": "HuariqueRegistrado"},
    "schemaVersion": {"const": 1},
    "huariqueId": {"$ref": "urn:foodly:business:v1:common#/$defs/Uuid"},
    "aggregateVersion": {"type": "integer", "minimum": 0},
    "occurredAt": {"type": "string", "format": "date-time"},
    "data": {"$ref": "urn:foodly:business:v1:common#/$defs/Huarique"}
  }
}
```

</details>

Ejemplo:

```json
{
  "eventId": "7c1b9e0a-6d3f-4f4e-9a52-1d8e0b7c3f10",
  "type": "HuariqueRegistrado",
  "schemaVersion": 1,
  "huariqueId": "d297baf2-2495-4c38-9beb-56d18508ab39",
  "aggregateVersion": 0,
  "occurredAt": "2026-11-24T20:00:00.123Z",
  "data": {
    "id": "d297baf2-2495-4c38-9beb-56d18508ab39",
    "ownerId": "a519c2ae-9ac5-40ac-9149-470bf3bb9e96",
    "name": "El Huarique de Doña Rosa",
    "address": "Av. Larco 123, Miraflores",
    "cuisineType": "CRIOLLA",
    "priceRange": {"minimum": 15.00, "maximum": 45.50},
    "location": {"latitude": -12.1219, "longitude": -77.0297},
    "openStatus": "CLOSED",
    "tags": ["casero", "menu del dia"],
    "openingHours": [{"day": "MONDAY", "opensAt": "08:00:00", "closesAt": "17:30:00"}],
    "images": [],
    "version": 0
  }
}
```

#### PerfilDelHuariqueActualizado

Cambió el perfil (datos, ubicación, etiquetas, horarios) o la galería del huarique. Routing key: `business.PerfilDelHuariqueActualizado`. Exchange: `foodly.business`.

<details>
<summary>Esquema JSON</summary>

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:foodly:business:v1:PerfilDelHuariqueActualizado",
  "title": "PerfilDelHuariqueActualizado",
  "description": "Cambió el perfil o la galería del huarique.",
  "type": "object",
  "required": ["eventId", "type", "schemaVersion", "huariqueId", "aggregateVersion", "occurredAt", "data"],
  "properties": {
    "eventId": {"$ref": "urn:foodly:business:v1:common#/$defs/Uuid"},
    "type": {"const": "PerfilDelHuariqueActualizado"},
    "schemaVersion": {"const": 1},
    "huariqueId": {"$ref": "urn:foodly:business:v1:common#/$defs/Uuid"},
    "aggregateVersion": {"type": "integer", "minimum": 0},
    "occurredAt": {"type": "string", "format": "date-time"},
    "data": {"$ref": "urn:foodly:business:v1:common#/$defs/Huarique"}
  }
}
```

</details>

Ejemplo:

```json
{
  "eventId": "2f6d8a14-93b7-4c1e-8d05-6a1b4e9c7d32",
  "type": "PerfilDelHuariqueActualizado",
  "schemaVersion": 1,
  "huariqueId": "d297baf2-2495-4c38-9beb-56d18508ab39",
  "aggregateVersion": 1,
  "occurredAt": "2026-11-24T20:05:10.456Z",
  "data": {
    "id": "d297baf2-2495-4c38-9beb-56d18508ab39",
    "ownerId": "a519c2ae-9ac5-40ac-9149-470bf3bb9e96",
    "name": "El Huarique de Rosa",
    "address": "Av. Larco 123, Miraflores",
    "cuisineType": "CRIOLLA",
    "priceRange": {"minimum": 15.00, "maximum": 45.50},
    "location": {"latitude": -12.1219, "longitude": -77.0297},
    "openStatus": "CLOSED",
    "tags": ["casero", "menu del dia"],
    "openingHours": [{"day": "MONDAY", "opensAt": "08:00:00", "closesAt": "17:30:00"}],
    "images": [
      {
        "id": "0b1f4a52-7a31-4d52-9a0b-5c2d7a9b1e11",
        "publicId": "foodly/business/0b1f4a52",
        "url": "https://res.cloudinary.com/example/image/upload/v1/foodly/business/0b1f4a52.jpg"
      }
    ],
    "version": 1
  }
}
```

#### EstadoAbiertoCerradoCambiado

El huarique pasó de `OPEN` a `CLOSED` o al revés. Routing key: `business.EstadoAbiertoCerradoCambiado`. Exchange: `foodly.business`.

<details>
<summary>Esquema JSON</summary>

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:foodly:business:v1:EstadoAbiertoCerradoCambiado",
  "title": "EstadoAbiertoCerradoCambiado",
  "description": "El huarique pasó de OPEN a CLOSED o al revés.",
  "type": "object",
  "required": ["eventId", "type", "schemaVersion", "huariqueId", "aggregateVersion", "occurredAt", "data"],
  "properties": {
    "eventId": {"$ref": "urn:foodly:business:v1:common#/$defs/Uuid"},
    "type": {"const": "EstadoAbiertoCerradoCambiado"},
    "schemaVersion": {"const": 1},
    "huariqueId": {"$ref": "urn:foodly:business:v1:common#/$defs/Uuid"},
    "aggregateVersion": {"type": "integer", "minimum": 0},
    "occurredAt": {"type": "string", "format": "date-time"},
    "data": {"$ref": "urn:foodly:business:v1:common#/$defs/Huarique"}
  }
}
```

</details>

Ejemplo:

```json
{
  "eventId": "c4a9e1b7-5d28-4f63-b0e4-8a7d2c9f1e56",
  "type": "EstadoAbiertoCerradoCambiado",
  "schemaVersion": 1,
  "huariqueId": "d297baf2-2495-4c38-9beb-56d18508ab39",
  "aggregateVersion": 2,
  "occurredAt": "2026-11-25T13:00:00Z",
  "data": {
    "id": "d297baf2-2495-4c38-9beb-56d18508ab39",
    "ownerId": "a519c2ae-9ac5-40ac-9149-470bf3bb9e96",
    "name": "El Huarique de Rosa",
    "address": "Av. Larco 123, Miraflores",
    "cuisineType": "CRIOLLA",
    "priceRange": {"minimum": 15.00, "maximum": 45.50},
    "location": {"latitude": -12.1219, "longitude": -77.0297},
    "openStatus": "OPEN",
    "tags": ["casero", "menu del dia"],
    "openingHours": [{"day": "MONDAY", "opensAt": "08:00:00", "closesAt": "17:30:00"}],
    "images": [
      {
        "id": "0b1f4a52-7a31-4d52-9a0b-5c2d7a9b1e11",
        "publicId": "foodly/business/0b1f4a52",
        "url": "https://res.cloudinary.com/example/image/upload/v1/foodly/business/0b1f4a52.jpg"
      }
    ],
    "version": 2
  }
}
```

#### MenuCreado

Se guardó la primera carta del huarique. Routing key: `business.MenuCreado`. Exchange: `foodly.business`.

<details>
<summary>Esquema JSON</summary>

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:foodly:business:v1:MenuCreado",
  "title": "MenuCreado",
  "description": "Se guardó la primera carta del huarique.",
  "type": "object",
  "required": ["eventId", "type", "schemaVersion", "huariqueId", "aggregateVersion", "occurredAt", "data"],
  "properties": {
    "eventId": {"$ref": "urn:foodly:business:v1:common#/$defs/Uuid"},
    "type": {"const": "MenuCreado"},
    "schemaVersion": {"const": 1},
    "huariqueId": {"$ref": "urn:foodly:business:v1:common#/$defs/Uuid"},
    "aggregateVersion": {"type": "integer", "minimum": 0},
    "occurredAt": {"type": "string", "format": "date-time"},
    "data": {"$ref": "urn:foodly:business:v1:common#/$defs/Menu"}
  }
}
```

</details>

Ejemplo:

```json
{
  "eventId": "8b3e7f20-1a95-4d6c-a2f8-0c5d9e4b7a61",
  "type": "MenuCreado",
  "schemaVersion": 1,
  "huariqueId": "d297baf2-2495-4c38-9beb-56d18508ab39",
  "aggregateVersion": 0,
  "occurredAt": "2026-11-24T20:10:00Z",
  "data": {
    "id": "5e0c7d36-2a8b-4a07-8f1f-4b7d1f6a9c22",
    "huariqueId": "d297baf2-2495-4c38-9beb-56d18508ab39",
    "categories": [
      {
        "id": "9a3e5b70-1c44-4d0e-b5a7-2f6c8d1e3a44",
        "name": "Fondos",
        "dishes": [
          {
            "id": "3c7a6491-2e22-4eb7-b627-bdfb21172c48",
            "name": "Arroz con pollo",
            "description": "Con salsa huancaina",
            "price": 18.50,
            "available": true,
            "images": [
              {
                "id": "0b1f4a52-7a31-4d52-9a0b-5c2d7a9b1e11",
                "publicId": "foodly/business/0b1f4a52",
                "url": "https://res.cloudinary.com/example/image/upload/v1/foodly/business/0b1f4a52.jpg"
              }
            ]
          }
        ]
      }
    ],
    "version": 0
  }
}
```

#### MenuActualizado

Cambió la carta, un precio, una disponibilidad o la imagen de un plato. Routing key: `business.MenuActualizado`. Exchange: `foodly.business`.

<details>
<summary>Esquema JSON</summary>

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:foodly:business:v1:MenuActualizado",
  "title": "MenuActualizado",
  "description": "Cambió la carta, un precio, una disponibilidad o la imagen de un plato.",
  "type": "object",
  "required": ["eventId", "type", "schemaVersion", "huariqueId", "aggregateVersion", "occurredAt", "data"],
  "properties": {
    "eventId": {"$ref": "urn:foodly:business:v1:common#/$defs/Uuid"},
    "type": {"const": "MenuActualizado"},
    "schemaVersion": {"const": 1},
    "huariqueId": {"$ref": "urn:foodly:business:v1:common#/$defs/Uuid"},
    "aggregateVersion": {"type": "integer", "minimum": 0},
    "occurredAt": {"type": "string", "format": "date-time"},
    "data": {"$ref": "urn:foodly:business:v1:common#/$defs/Menu"}
  }
}
```

</details>

Ejemplo:

```json
{
  "eventId": "e1d5c8a3-7b04-4a92-9f16-3c8e0b2d5a78",
  "type": "MenuActualizado",
  "schemaVersion": 1,
  "huariqueId": "d297baf2-2495-4c38-9beb-56d18508ab39",
  "aggregateVersion": 1,
  "occurredAt": "2026-11-24T21:30:45.789Z",
  "data": {
    "id": "5e0c7d36-2a8b-4a07-8f1f-4b7d1f6a9c22",
    "huariqueId": "d297baf2-2495-4c38-9beb-56d18508ab39",
    "categories": [
      {
        "id": "9a3e5b70-1c44-4d0e-b5a7-2f6c8d1e3a44",
        "name": "Fondos",
        "dishes": [
          {
            "id": "3c7a6491-2e22-4eb7-b627-bdfb21172c48",
            "name": "Arroz con pollo",
            "description": "Con salsa huancaina",
            "price": 20.00,
            "available": true,
            "images": [
              {
                "id": "0b1f4a52-7a31-4d52-9a0b-5c2d7a9b1e11",
                "publicId": "foodly/business/0b1f4a52",
                "url": "https://res.cloudinary.com/example/image/upload/v1/foodly/business/0b1f4a52.jpg"
              }
            ]
          }
        ]
      }
    ],
    "version": 1
  }
}
```

### Qué necesita Radar (verificado)

Radar necesita latitud, longitud y estado de apertura. Los tres eventos de huarique los incluyen siempre, porque `data` es el huarique completo y los campos son obligatorios en el esquema:

| Dato para Radar | Campo en el evento | Valores |
|---|---|---|
| Latitud | `data.location.latitude` | número entre -90 y 90 |
| Longitud | `data.location.longitude` | número entre -180 y 180 |
| Estado de apertura | `data.openStatus` | `OPEN` o `CLOSED` |
| Identidad del huarique | `huariqueId` (= `data.id`) | UUID |
| Versión | `aggregateVersion` (= `data.version`) | entero |

Adaptación que debe hacer Radar (hoy espera otro contrato, ver `Foodly-Radar-Service/README.md`):

| Radar espera hoy | Contrato Business v1 |
|---|---|
| Exchange `foodly.business.events` | `foodly.business` |
| Routing keys `huarique.registered`, `huarique.updated`, `huarique.location-updated`, `huarique.opening-status-changed` | `business.HuariqueRegistrado`, `business.PerfilDelHuariqueActualizado`, `business.EstadoAbiertoCerradoCambiado` |
| `eventType` | `type` |
| `version` | `aggregateVersion` |
| `payload` con `latitude`, `longitude`, `status` | `data.location.latitude`, `data.location.longitude`, `data.openStatus` |
| `status` `ACTIVE` / `INACTIVE` | Derivarlo de `openStatus`: `OPEN` es activo y `CLOSED` es inactivo |
| `LocationUpdated` como evento propio | No existe: el cambio de ubicación llega en `PerfilDelHuariqueActualizado`. Radar compara con la ubicación guardada |
| `HuariqueRegistered` / `HuariqueUpdated` / `OpeningStatusChanged` | `HuariqueRegistrado` / `PerfilDelHuariqueActualizado` / `EstadoAbiertoCerradoCambiado` |

Un huarique recién registrado llega `CLOSED`, así que Radar lo proyecta inactivo hasta recibir `EstadoAbiertoCerradoCambiado`.

### Consumidores

Cada consumidor declara su cola, su DLQ y sus bindings. Todas las colas son **durables** y se enlazan al exchange `foodly.business`. Las DLQ cuelgan del exchange de mensajes muertos `foodly.dlx` (direct, durable) con `x-dead-letter-exchange=foodly.dlx` y `x-dead-letter-routing-key=<nombre de la DLQ>`; la DLQ se enlaza a `foodly.dlx` con esa misma routing key.

| Consumidor | Cola (durable) | Routing keys a enlazar | DLQ | Ack |
|---|---|---|---|---|
| Radar | `radar.business-events` | `business.HuariqueRegistrado`, `business.PerfilDelHuariqueActualizado`, `business.EstadoAbiertoCerradoCambiado` | `radar.business-events.dlq` | Manual, después de actualizar Redis |
| Community | `community.business-events` (nombre propuesto) | `business.HuariqueRegistrado`, `business.PerfilDelHuariqueActualizado` | `community.business-events.dlq` (propuesto) | Manual (recomendado) |

- Los nombres de las colas y DLQ de Radar son los que ya declara su README. Los de Community son propuestos y deben confirmarse con ese equipo.
- Ningún consumidor enlaza `business.Menu*` ni `business.#`.
- Un evento que falla varias veces o no se puede leer debe rechazarse sin reencolar (`requeue=false`) para que pase a la DLQ. Radar ya lo hace tras 3 intentos.
- Antes de que Business publique, la cola y sus bindings deben existir; si no, ver el aviso siguiente.

**Aviso: eventos sin cola enlazada.** Business publica con `mandatory=true`. Si ninguna cola está enlazada a la routing key, el broker devuelve el mensaje, Business lo trata como fallo y lo deja **pendiente en `business_outbox`**, reintentando con retroceso exponencial de 2 a 256 segundos y un aviso en el log por intento. Se entrega solo cuando alguien enlace una cola. Consecuencias con los consumidores de la tabla:

- Mientras Radar y Community no hayan declarado sus colas, **todos** los eventos de huarique quedan pendientes (se entregan después, cuando existan las colas).
- Como ningún consumidor enlaza `business.MenuCreado` ni `business.MenuActualizado`, esos eventos **quedarán pendientes indefinidamente** y se reintentarán sin fin. **Decisión pendiente del equipo:** enlazar una cola para ellos (por ejemplo la de un consumidor futuro de carta o una cola de auditoría) o cambiar la política de Business para eventos sin consumidor. Esta rama no cambia la lógica de publicación.

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
