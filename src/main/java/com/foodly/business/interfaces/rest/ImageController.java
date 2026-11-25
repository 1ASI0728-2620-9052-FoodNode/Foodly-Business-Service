package com.foodly.business.interfaces.rest;
import com.foodly.business.application.ImageApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import static com.foodly.business.interfaces.rest.Responses.Image;
@RestController
@RequestMapping("/business-api/huariques/{id}")
@Tag(name = "Imágenes", description = "Galerías de imágenes del huarique (`/images`) y de cada plato (`/menu/dishes/{dishId}/images`). "
        + "Las dos rutas hacen lo mismo; con `dishId` se trabaja sobre la galería del plato. Hasta 20 imágenes por huarique y 10 "
        + "por plato. Las escrituras exigen el rol de dueño y que el huarique sea del usuario del token.")
public class ImageController {
    private final ImageApplicationService service;
    public ImageController(ImageApplicationService service) { this.service = service; }

    @GetMapping({"/images", "/menu/dishes/{dishId}/images"})
    @Operation(summary = "Listar las imágenes de un huarique o de un plato",
            description = "Con la ruta `/menu/dishes/{dishId}/images` lista la galería del plato; con `/images`, la del huarique.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Las imágenes de la galería."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "404", ref = ApiDocs.NOT_FOUND)})
    public List<Image> list(@PathVariable UUID id, @PathVariable(required=false) UUID dishId) {
        return service.list(id, dishId).stream().map(Image::from).toList();
    }

    @GetMapping({"/images/{imageId}", "/menu/dishes/{dishId}/images/{imageId}"})
    @Operation(summary = "Consultar una imagen", description = "Devuelve su `id` y su `url` pública.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La imagen."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "404", ref = ApiDocs.NOT_FOUND)})
    public Image get(@PathVariable UUID id, @PathVariable(required=false) UUID dishId, @PathVariable UUID imageId) {
        return Image.from(service.get(id, dishId, imageId));
    }

    @PostMapping(value={"/images", "/menu/dishes/{dishId}/images"}, consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Subir una imagen",
            description = "`multipart/form-data` con el archivo en el campo `file` (máximo 5 MB). Se sube a Cloudinary y el huarique "
                    + "(o la carta) publica su evento de actualización.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Imagen creada. La cabecera `Location` apunta a su URL."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "403", ref = ApiDocs.FORBIDDEN),
            @ApiResponse(responseCode = "404", ref = ApiDocs.NOT_FOUND),
            @ApiResponse(responseCode = "409", ref = ApiDocs.CONFLICT),
            @ApiResponse(responseCode = "413", description = "La imagen supera el límite de subida (5 MB).")})
    public ResponseEntity<Image> add(@PathVariable UUID id, @PathVariable(required=false) UUID dishId, @AuthenticationPrincipal Jwt jwt, @RequestPart MultipartFile file) throws IOException {
        var image = service.upload(id, jwt.getSubject(), dishId, null, file.getBytes(), file.getContentType());
        var path = "/business-api/huariques/" + id + (dishId == null ? "" : "/menu/dishes/" + dishId) + "/images/" + image.id();
        return ResponseEntity.created(URI.create(path)).body(Image.from(image));
    }

    @PutMapping(value={"/images/{imageId}", "/menu/dishes/{dishId}/images/{imageId}"}, consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Reemplazar una imagen",
            description = "`multipart/form-data` con el archivo nuevo en el campo `file` (máximo 5 MB). La imagen anterior se elimina de Cloudinary en diferido.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La imagen nueva."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "403", ref = ApiDocs.FORBIDDEN),
            @ApiResponse(responseCode = "404", ref = ApiDocs.NOT_FOUND),
            @ApiResponse(responseCode = "409", ref = ApiDocs.CONFLICT),
            @ApiResponse(responseCode = "413", description = "La imagen supera el límite de subida (5 MB).")})
    public Image replace(@PathVariable UUID id, @PathVariable(required=false) UUID dishId, @PathVariable UUID imageId, @AuthenticationPrincipal Jwt jwt, @RequestPart MultipartFile file) throws IOException {
        return Image.from(service.upload(id, jwt.getSubject(), dishId, imageId, file.getBytes(), file.getContentType()));
    }

    @DeleteMapping({"/images/{imageId}", "/menu/dishes/{dishId}/images/{imageId}"})
    @Operation(summary = "Eliminar una imagen", description = "La imagen se quita de la galería de inmediato y se elimina de Cloudinary en diferido.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Imagen eliminada."),
            @ApiResponse(responseCode = "400", ref = ApiDocs.BAD_REQUEST),
            @ApiResponse(responseCode = "401", ref = ApiDocs.UNAUTHORIZED),
            @ApiResponse(responseCode = "403", ref = ApiDocs.FORBIDDEN),
            @ApiResponse(responseCode = "404", ref = ApiDocs.NOT_FOUND),
            @ApiResponse(responseCode = "409", ref = ApiDocs.CONFLICT)})
    public ResponseEntity<Void> delete(@PathVariable UUID id, @PathVariable(required=false) UUID dishId, @PathVariable UUID imageId, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, jwt.getSubject(), dishId, imageId); return ResponseEntity.noContent().build();
    }
}
