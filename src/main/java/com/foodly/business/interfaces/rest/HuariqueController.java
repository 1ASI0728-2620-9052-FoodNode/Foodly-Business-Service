package com.foodly.business.interfaces.rest;
import com.foodly.business.application.HuariqueApplicationService;
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
public class HuariqueController {
    private final HuariqueApplicationService service;
    public HuariqueController(HuariqueApplicationService service) { this.service = service; }
    @PostMapping
    public ResponseEntity<HuariqueView> register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Profile body) {
        var h = service.register(jwt.getSubject(), body.toData());
        return ResponseEntity.created(URI.create("/business-api/huariques/" + h.id())).body(HuariqueView.from(h));
    }
    @GetMapping("/{id}") public HuariqueView get(@PathVariable UUID id) { return HuariqueView.from(service.get(id)); }
    @GetMapping("/me") public HuariqueView mine(@AuthenticationPrincipal Jwt jwt) { return HuariqueView.from(service.mine(jwt.getSubject())); }
    @GetMapping public List<HuariqueView> list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return service.list(page, size).stream().map(HuariqueView::from).toList();
    }
    @PutMapping("/{id}") public HuariqueView update(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Profile body) {
        return HuariqueView.from(service.update(id, jwt.getSubject(), body.toData(), body.requiredVersion()));
    }
    @PatchMapping("/{id}/open-status") public HuariqueView status(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Status body) {
        return HuariqueView.from(service.changeStatus(id, jwt.getSubject(), body.openStatus(), body.version()));
    }
}
