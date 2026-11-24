package com.foodly.business.application;

import com.foodly.business.application.port.EventOutbox;
import com.foodly.business.domain.model.*;
import com.foodly.business.domain.repository.IHuariqueRepository;
import com.foodly.business.domain.service.BusinessDomainService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class HuariqueApplicationService {
    private final IHuariqueRepository repository;
    private final BusinessDomainService domain;
    private final EventOutbox outbox;
    public HuariqueApplicationService(IHuariqueRepository repository, BusinessDomainService domain, EventOutbox outbox) {
        this.repository = repository; this.domain = domain; this.outbox = outbox;
    }
    @Transactional
    public Huarique register(String ownerId, ProfileData data) {
        var h = new Huarique(UUID.randomUUID(), ownerId, data.name(), data.address(), data.cuisineType(),
                data.priceRange(), data.location(), OpenStatus.CLOSED, data.tags(), data.openingHours(), List.of(), null);
        domain.validateHuarique(h);
        return persist(h, "HuariqueRegistrado");
    }
    public Huarique get(UUID id) { return repository.findById(id).orElseThrow(() -> DomainException.missing("Huarique not found")); }
    public Huarique mine(String ownerId) { return repository.findByOwnerId(ownerId).orElseThrow(() -> DomainException.missing("Huarique not found")); }
    public List<Huarique> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw DomainException.invalid("page >= 0 and size between 1 and 100 required");
        return repository.findAll(page, size);
    }
    public Huarique owned(UUID id, String actorId) { var h = get(id); domain.authorize(h, actorId); return h; }
    @Transactional
    public Huarique update(UUID id, String actorId, ProfileData data, long expectedVersion) {
        var h = owned(id, actorId); checkVersion(h.version(), expectedVersion);
        return persist(h.updateInformation(data.name(), data.address(), data.cuisineType(), data.priceRange(), data.location(), data.tags(), data.openingHours()), "PerfilDelHuariqueActualizado");
    }
    @Transactional
    public Huarique changeStatus(UUID id, String actorId, OpenStatus status, long expectedVersion) {
        var h = owned(id, actorId); checkVersion(h.version(), expectedVersion);
        return h.openStatus() == status ? h : persist(h.changeOpenStatus(status), "EstadoAbiertoCerradoCambiado");
    }
    @Transactional
    public Huarique changeImage(UUID id, String actorId, UUID replacedId, ImageReference added) {
        var h = owned(id, actorId);
        var updated = h.withImages(Gallery.change(h.images(), replacedId, added));
        var saved = persist(updated, "PerfilDelHuariqueActualizado");
        if (replacedId != null) h.images().stream().filter(i -> i.id().equals(replacedId)).forEach(i -> cleanup(id, i));
        return saved;
    }
    private Huarique persist(Huarique h, String type) {
        var saved = repository.save(h);
        outbox.append(EventOutbox.Event.of(type, saved.id(), saved.version(), saved));
        return saved;
    }
    public static void checkVersion(Long actual, long expected) {
        if (!Objects.equals(actual, expected)) throw new DomainException(DomainException.Kind.CONFLICT, "Stale version; reload the resource");
    }
    private void cleanup(UUID id, ImageReference image) { outbox.append(EventOutbox.Event.of("internal.ImageDeletionRequested", id, null, image)); }
}
