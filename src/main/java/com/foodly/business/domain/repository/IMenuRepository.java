package com.foodly.business.domain.repository;
import com.foodly.business.domain.model.Menu;
import java.util.*;
public interface IMenuRepository {
    Menu save(Menu menu);
    Optional<Menu> findByHuariqueId(UUID huariqueId);
}
