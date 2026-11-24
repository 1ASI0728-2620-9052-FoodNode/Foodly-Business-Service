package com.foodly.business.domain.model;
import java.util.*;
public record MenuCategory(UUID id, String name, List<Dish> dishes) {
    public MenuCategory {
        Rules.required(id, "category id"); name = Rules.text(name, "category name", 100);
        dishes = Rules.list(dishes, 100, "dishes"); Rules.unique(dishes.stream().map(Dish::id).toList(), "dish ids");
    }
    public MenuCategory withDishes(List<Dish> values) { return new MenuCategory(id, name, values); }
}
