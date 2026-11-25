package com.foodly.business.interfaces.rest;

/**
 * Referencias a las respuestas de error reutilizables que registra {@link OpenApiConfig}. Se usan en
 * {@code @ApiResponse(responseCode = "...", ref = ApiDocs.NOT_FOUND)}.
 */
final class ApiDocs {
    static final String BAD_REQUEST = "#/components/responses/BadRequest";
    static final String UNAUTHORIZED = "#/components/responses/Unauthorized";
    static final String FORBIDDEN = "#/components/responses/Forbidden";
    static final String NOT_FOUND = "#/components/responses/NotFound";
    static final String CONFLICT = "#/components/responses/Conflict";

    private ApiDocs() { }
}
