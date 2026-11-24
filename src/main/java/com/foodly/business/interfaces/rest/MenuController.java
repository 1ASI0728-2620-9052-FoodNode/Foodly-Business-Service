package com.foodly.business.interfaces.rest;
import com.foodly.business.application.MenuApplicationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.foodly.business.interfaces.rest.Responses.MenuView;
@RestController
@RequestMapping("/business-api/huariques/{id}/menu")
public class MenuController {
    private final MenuApplicationService service;
    public MenuController(MenuApplicationService service) { this.service = service; }
    @GetMapping public MenuView get(@PathVariable UUID id) { return MenuView.from(service.get(id)); }
    @PutMapping public MenuView replace(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.MenuBody body) {
        return MenuView.from(service.replace(id, jwt.getSubject(), body.toDomain(), body.version()));
    }
    @PatchMapping("/dishes/{dishId}/price") public MenuView price(@PathVariable UUID id, @PathVariable UUID dishId, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Price body) {
        return MenuView.from(service.changeDish(id, jwt.getSubject(), dishId, body.version(), d -> d.updatePrice(body.price())));
    }
    @PatchMapping("/dishes/{dishId}/availability") public MenuView availability(@PathVariable UUID id, @PathVariable UUID dishId, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Availability body) {
        return MenuView.from(service.changeDish(id, jwt.getSubject(), dishId, body.version(), d -> d.changeAvailability(body.available())));
    }
}
