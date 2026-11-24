package com.foodly.business.domain.service;
import com.foodly.business.domain.model.*;
import com.foodly.business.domain.repository.IHuariqueRepository;
public class BusinessDomainService {
    private final IHuariqueRepository huariques;
    public BusinessDomainService(IHuariqueRepository huariques) { this.huariques = huariques; }
    public void validateHuarique(Huarique value) {
        huariques.findByOwnerId(value.ownerId()).filter(h -> !h.id().equals(value.id())).ifPresent(h -> {
            throw new DomainException(DomainException.Kind.CONFLICT, "Owner already has a huarique");
        });
    }
    public void validateMenu(Menu value) {
        huariques.findById(value.huariqueId()).orElseThrow(() -> DomainException.missing("Huarique not found"));
    }
    public void authorize(Huarique value, String actorId) {
        if (!value.ownerId().equals(actorId)) throw new DomainException(DomainException.Kind.FORBIDDEN, "Only the owner may manage this huarique");
    }
}
