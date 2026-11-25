package com.foodly.business.interfaces.rest;
import com.foodly.business.application.MenuApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.foodly.business.interfaces.rest.Responses.MenuView;
@RestController
@RequestMapping("/business-api/huariques/{id}/menu")
@Tag(name = "Carta", description = "Carta (menú) de un huarique: categorías y platos. Cada huarique tiene una sola carta, "
        + "con su propia `version`. Las escrituras exigen el rol de dueño y que el huarique sea del usuario del token.")
public class MenuController {
    private final MenuApplicationService service;
    public MenuController(MenuApplicationService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "Consultar la carta del huarique",
            description = "Cualquier usuario autenticado puede consultarla. Responde 404 si el huarique no existe o todavía no tiene carta.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La carta completa."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "404", ref = ApiDocs.NOT_FOUND)})
    public MenuView get(@PathVariable UUID id) { return MenuView.from(service.get(id)); }

    @PutMapping
    @Operation(summary = "Crear o reemplazar la carta completa",
            description = "Sin carta previa se omite `version` y se publica `MenuCreado`; con carta previa `version` es obligatoria "
                    + "y se publica `MenuActualizado`. Las imágenes de los platos no se envían aquí: se gestionan con los "
                    + "endpoints de imágenes.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La carta guardada, con su nueva `version`."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "403", ref = ApiDocs.FORBIDDEN),
            @ApiResponse(responseCode = "404", ref = ApiDocs.NOT_FOUND),
            @ApiResponse(responseCode = "409", ref = ApiDocs.CONFLICT)})
    public MenuView replace(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.MenuBody body) {
        return MenuView.from(service.replace(id, jwt.getSubject(), body.toDomain(), body.version()));
    }

    @PatchMapping("/dishes/{dishId}/price")
    @Operation(summary = "Cambiar el precio de un plato", description = "Exige la `version` vigente de la carta. Publica `MenuActualizado`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La carta con el precio nuevo."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "403", ref = ApiDocs.FORBIDDEN),
            @ApiResponse(responseCode = "404", ref = ApiDocs.NOT_FOUND),
            @ApiResponse(responseCode = "409", ref = ApiDocs.CONFLICT)})
    public MenuView price(@PathVariable UUID id, @PathVariable UUID dishId, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Price body) {
        return MenuView.from(service.changeDish(id, jwt.getSubject(), dishId, body.version(), d -> d.updatePrice(body.price())));
    }

    @PatchMapping("/dishes/{dishId}/availability")
    @Operation(summary = "Marcar un plato como disponible o agotado", description = "Exige la `version` vigente de la carta. Publica `MenuActualizado`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La carta con la disponibilidad nueva."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "403", ref = ApiDocs.FORBIDDEN),
            @ApiResponse(responseCode = "404", ref = ApiDocs.NOT_FOUND),
            @ApiResponse(responseCode = "409", ref = ApiDocs.CONFLICT)})
    public MenuView availability(@PathVariable UUID id, @PathVariable UUID dishId, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Availability body) {
        return MenuView.from(service.changeDish(id, jwt.getSubject(), dishId, body.version(), d -> d.changeAvailability(body.available())));
    }
}
