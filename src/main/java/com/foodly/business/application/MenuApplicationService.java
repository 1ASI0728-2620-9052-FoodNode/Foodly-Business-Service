package com.foodly.business.application;

import com.foodly.business.application.port.EventOutbox;
import com.foodly.business.domain.model.*;
import com.foodly.business.domain.repository.IMenuRepository;
import com.foodly.business.domain.service.BusinessDomainService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

@Service
public class MenuApplicationService {
    private final IMenuRepository repository;
    private final HuariqueApplicationService huariques;
    private final BusinessDomainService domain;
    private final EventOutbox outbox;
    public MenuApplicationService(IMenuRepository repository, HuariqueApplicationService huariques, BusinessDomainService domain, EventOutbox outbox) {
        this.repository = repository; this.huariques = huariques; this.domain = domain; this.outbox = outbox;
    }
    public Menu get(UUID id) {
        huariques.get(id);
        return repository.findByHuariqueId(id).orElseThrow(() -> DomainException.missing("Menu not found"));
    }
    @Transactional
    public Menu replace(UUID id, String actor, List<MenuCategory> categories, Long expectedVersion) {
        huariques.owned(id, actor);
        var old = repository.findByHuariqueId(id);
        if (old.isPresent()) {
            if (expectedVersion == null) throw DomainException.invalid("version required to update menu");
            HuariqueApplicationService.checkVersion(old.get().version(), expectedVersion);
        } else if (expectedVersion != null) throw new DomainException(DomainException.Kind.CONFLICT, "Menu does not exist yet; omit version");
        // Images are managed only through authenticated uploads, never through menu JSON.
        Map<UUID, Dish> previous = old.stream().flatMap(m -> m.categories().stream()).flatMap(c -> c.dishes().stream()).collect(Collectors.toMap(Dish::id, d -> d));
        var safeCategories = categories.stream().map(c -> c.withDishes(c.dishes().stream().map(d ->
                d.withImages(previous.containsKey(d.id()) ? previous.get(d.id()).images() : List.of())).toList())).toList();
        var menu = new Menu(old.map(Menu::id).orElseGet(UUID::randomUUID), id, safeCategories, old.map(Menu::version).orElse(null));
        var remaining = safeCategories.stream().flatMap(c -> c.dishes().stream()).map(Dish::id).collect(Collectors.toSet());
        previous.values().stream().filter(d -> !remaining.contains(d.id())).flatMap(d -> d.images().stream()).forEach(i -> cleanup(id, i));
        return persist(menu, old.isEmpty() ? "MenuCreado" : "MenuActualizado");
    }
    @Transactional
    public Menu changeDish(UUID id, String actor, UUID dishId, long version, UnaryOperator<Dish> change) {
        huariques.owned(id, actor); var menu = get(id);
        HuariqueApplicationService.checkVersion(menu.version(), version);
        return persist(menu.updateDish(dishId, change), "MenuActualizado");
    }
    @Transactional
    public Menu changeImage(UUID id, String actor, UUID dishId, UUID replacedId, ImageReference added) {
        huariques.owned(id, actor); var menu = get(id); var dish = menu.dish(dishId);
        var updated = menu.updateDish(dishId, d -> d.withImages(Gallery.change(d.images(), replacedId, added)));
        var saved = persist(updated, "MenuActualizado");
        if (replacedId != null) dish.images().stream().filter(i -> i.id().equals(replacedId)).forEach(i -> cleanup(id, i));
        return saved;
    }
    private Menu persist(Menu value, String type) {
        domain.validateMenu(value); var saved = repository.save(value);
        outbox.append(EventOutbox.Event.of(type, saved.huariqueId(), saved.version(), saved)); return saved;
    }
    private void cleanup(UUID id, ImageReference image) { outbox.append(EventOutbox.Event.of("internal.ImageDeletionRequested", id, null, image)); }
}
