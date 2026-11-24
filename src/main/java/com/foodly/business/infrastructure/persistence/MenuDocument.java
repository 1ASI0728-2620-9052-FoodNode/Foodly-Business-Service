package com.foodly.business.infrastructure.persistence;
import com.foodly.business.domain.model.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;
@Document("menus")
public record MenuDocument(@Id UUID id, @Indexed(unique = true) UUID huariqueId, List<MenuCategory> categories, @Version Long version) {
    static MenuDocument from(Menu m) { return new MenuDocument(m.id(), m.huariqueId(), m.categories(), m.version()); }
    Menu toDomain() { return new Menu(id, huariqueId, categories, version); }
}
