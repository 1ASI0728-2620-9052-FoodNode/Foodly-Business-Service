package com.foodly.business.domain.repository;
import com.foodly.business.domain.model.Huarique;
import java.util.*;
public interface IHuariqueRepository {
    Huarique save(Huarique huarique);
    Optional<Huarique> findById(UUID id);
    Optional<Huarique> findByOwnerId(String ownerId);
    List<Huarique> findAll(int page, int size);
}
