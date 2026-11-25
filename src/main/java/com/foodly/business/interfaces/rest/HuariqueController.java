package com.foodly.business.interfaces.rest;
import com.foodly.business.application.HuariqueApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;
import static com.foodly.business.interfaces.rest.Responses.HuariqueView;

@RestController
@RequestMapping("/business-api/huariques")
@Tag(name = "Huariques", description = "Registro, consulta y edición de huariques. Las consultas las puede hacer cualquier "
        + "usuario autenticado; las escrituras exigen el rol de dueño y que el huarique sea del usuario del token.")
public class HuariqueController {
    private final HuariqueApplicationService service;
    public HuariqueController(HuariqueApplicationService service) { this.service = service; }

    @PostMapping
    @Operation(summary = "Registrar el huarique del dueño autenticado",
            description = "El dueño sale del token (`sub`), nunca del cuerpo. Un dueño solo puede tener un huarique. "
                    + "El huarique nace cerrado (`CLOSED`) y con `version` 0. Publica el evento `HuariqueRegistrado`.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Huarique creado. La cabecera `Location` apunta a su URL."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "403", ref = ApiDocs.FORBIDDEN),
            @ApiResponse(responseCode = "409", ref = ApiDocs.CONFLICT)})
    public ResponseEntity<HuariqueView> register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Profile body) {
        var h = service.register(jwt.getSubject(), body.toData());
        return ResponseEntity.created(URI.create("/business-api/huariques/" + h.id())).body(HuariqueView.from(h));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar un huarique por id", description = "Cualquier usuario autenticado puede consultarlo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "El huarique."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "404", ref = ApiDocs.NOT_FOUND)})
    public HuariqueView get(@PathVariable UUID id) { return HuariqueView.from(service.get(id)); }

    @GetMapping("/me")
    @Operation(summary = "Consultar el huarique del dueño autenticado",
            description = "Exige el rol de dueño. Responde 404 si el dueño todavía no registró su huarique.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "El huarique del dueño."),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "403", ref = ApiDocs.FORBIDDEN),
            @ApiResponse(responseCode = "404", ref = ApiDocs.NOT_FOUND)})
    public HuariqueView mine(@AuthenticationPrincipal Jwt jwt) { return HuariqueView.from(service.mine(jwt.getSubject())); }

    @GetMapping
    @Operation(summary = "Listar huariques (paginado)",
            description = "`size` entre 1 y 100 (por defecto 20) y `page` desde 0. Las páginas no son una instantánea "
                    + "consistente si hay cambios mientras se recorren.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de huariques."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED)})
    public List<HuariqueView> list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return service.list(page, size).stream().map(HuariqueView::from).toList();
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar el perfil del huarique",
            description = "Reemplaza nombre, dirección, cocina, precios, ubicación, etiquetas y horarios. Exige el `version` "
                    + "vigente. Publica `PerfilDelHuariqueActualizado` con el huarique completo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Huarique actualizado, con su nueva `version`."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "403", ref = ApiDocs.FORBIDDEN),
            @ApiResponse(responseCode = "404", ref = ApiDocs.NOT_FOUND),
            @ApiResponse(responseCode = "409", ref = ApiDocs.CONFLICT)})
    public HuariqueView update(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Profile body) {
        return HuariqueView.from(service.update(id, jwt.getSubject(), body.toData(), body.requiredVersion()));
    }

    @PatchMapping("/{id}/open-status")
    @Operation(summary = "Abrir o cerrar el huarique",
            description = "Cambia `openStatus` entre `OPEN` y `CLOSED`. Si el estado pedido es el actual no se publica ningún "
                    + "evento; si cambia, se publica `EstadoAbiertoCerradoCambiado`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Huarique con su estado actual."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "403", ref = ApiDocs.FORBIDDEN),
            @ApiResponse(responseCode = "404", ref = ApiDocs.NOT_FOUND),
            @ApiResponse(responseCode = "409", ref = ApiDocs.CONFLICT)})
    public HuariqueView status(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Status body) {
        return HuariqueView.from(service.changeStatus(id, jwt.getSubject(), body.openStatus(), body.version()));
    }
}
