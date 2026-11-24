package com.foodly.business.domain.model;
import java.util.*;
import java.util.function.UnaryOperator;
public record Menu(UUID id, UUID huariqueId, List<MenuCategory> categories, Long version) {
    public Menu {
        Rules.required(id, "menu id"); Rules.required(huariqueId, "huariqueId");
        categories = Rules.list(categories, 30, "categories");
        Rules.unique(categories.stream().map(MenuCategory::id).toList(), "category ids");
        Rules.unique(categories.stream().flatMap(c -> c.dishes().stream()).map(Dish::id).toList(), "dish ids");
    }
    public Menu withCategories(List<MenuCategory> values) { return new Menu(id, huariqueId, values, version); }
    public Dish dish(UUID dishId) {
        return categories.stream().flatMap(c -> c.dishes().stream()).filter(d -> d.id().equals(dishId)).findFirst()
                .orElseThrow(() -> DomainException.missing("Dish not found"));
    }
    public Menu updateDish(UUID dishId, UnaryOperator<Dish> change) {
        dish(dishId);
        return withCategories(categories.stream().map(c -> c.withDishes(c.dishes().stream()
                .map(d -> d.id().equals(dishId) ? change.apply(d) : d).toList())).toList());
    }
}
