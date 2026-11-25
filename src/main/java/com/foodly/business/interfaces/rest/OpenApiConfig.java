package com.foodly.business.interfaces.rest;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentación OpenAPI de Business (Swagger UI en {@code /swagger-ui.html}, JSON en {@code /v3/api-docs}).
 *
 * <p>Declara el esquema de seguridad {@code bearerAuth} (JWT de IAM) para el botón Authorize y las respuestas de error
 * reutilizables ({@code #/components/responses/...}) que los controladores referencian con {@code ApiDocs}.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI businessOpenApi(@Value("${business.api.version:1.0.0}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("Foodly Business Service")
                        .version(version)
                        .description("API de huariques, cartas e imágenes de Foodly. Todas las rutas /business-api exigen "
                                + "un JWT emitido por IAM (HS256, iss foodly-iam y aud con foodly-business). Las consultas "
                                + "las puede hacer cualquier usuario autenticado; las escrituras exigen el rol de dueño "
                                + "(por defecto HUARIQUE_OWNER) y que el recurso pertenezca al usuario del token. "
                                + "Las escrituras sobre un recurso existente llevan su `version` actual: si está obsoleta "
                                + "responden 409 y hay que volver a consultar."))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Token JWT de IAM. Se obtiene con POST /iam/auth/login (campo `token`). "
                                + "Péguelo aquí sin la palabra Bearer.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /** Esquema {@code ApiError} y respuestas de error comunes, para no repetirlas en cada operación. */
    @Bean
    OpenApiCustomizer errorResponses() {
        return openApi -> {
            Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
            openApi.setComponents(components);
            ModelConverters.getInstance().read(ApiError.class).forEach(components::addSchemas);
            components.addResponses("BadRequest", error("Datos inválidos: campos obligatorios, rangos, formato o id mal formado."));
            components.addResponses("Unauthorized", error("Falta el token, no es válido o venció. Responde con la cabecera WWW-Authenticate: Bearer."));
            components.addResponses("Forbidden", error("El usuario no tiene el rol de dueño o el recurso es de otro dueño."));
            components.addResponses("NotFound", error("El huarique, la carta o la imagen no existen."));
            components.addResponses("Conflict", error("Conflicto: versión obsoleta (otro cambio se guardó antes; vuelva a consultar), "
                    + "el dueño ya tiene un huarique o la carta ya existe."));
        };
    }

    private static ApiResponse error(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError"))));
    }
}
