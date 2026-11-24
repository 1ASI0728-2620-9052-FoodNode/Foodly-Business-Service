package com.foodly.business.interfaces.rest;
import com.foodly.business.application.ImageApplicationService;
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
public class ImageController {
    private final ImageApplicationService service;
    public ImageController(ImageApplicationService service) { this.service = service; }
    @GetMapping({"/images", "/menu/dishes/{dishId}/images"})
    public List<Image> list(@PathVariable UUID id, @PathVariable(required=false) UUID dishId) {
        return service.list(id, dishId).stream().map(Image::from).toList();
    }
    @GetMapping({"/images/{imageId}", "/menu/dishes/{dishId}/images/{imageId}"})
    public Image get(@PathVariable UUID id, @PathVariable(required=false) UUID dishId, @PathVariable UUID imageId) {
        return Image.from(service.get(id, dishId, imageId));
    }
    @PostMapping(value={"/images", "/menu/dishes/{dishId}/images"}, consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Image> add(@PathVariable UUID id, @PathVariable(required=false) UUID dishId, @AuthenticationPrincipal Jwt jwt, @RequestPart MultipartFile file) throws IOException {
        var image = service.upload(id, jwt.getSubject(), dishId, null, file.getBytes(), file.getContentType());
        var path = "/business-api/huariques/" + id + (dishId == null ? "" : "/menu/dishes/" + dishId) + "/images/" + image.id();
        return ResponseEntity.created(URI.create(path)).body(Image.from(image));
    }
    @PutMapping(value={"/images/{imageId}", "/menu/dishes/{dishId}/images/{imageId}"}, consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Image replace(@PathVariable UUID id, @PathVariable(required=false) UUID dishId, @PathVariable UUID imageId, @AuthenticationPrincipal Jwt jwt, @RequestPart MultipartFile file) throws IOException {
        return Image.from(service.upload(id, jwt.getSubject(), dishId, imageId, file.getBytes(), file.getContentType()));
    }
    @DeleteMapping({"/images/{imageId}", "/menu/dishes/{dishId}/images/{imageId}"})
    public ResponseEntity<Void> delete(@PathVariable UUID id, @PathVariable(required=false) UUID dishId, @PathVariable UUID imageId, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, jwt.getSubject(), dishId, imageId); return ResponseEntity.noContent().build();
    }
}
