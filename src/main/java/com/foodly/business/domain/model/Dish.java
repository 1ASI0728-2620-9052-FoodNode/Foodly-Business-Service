package com.foodly.business.domain.model;
import java.math.BigDecimal;
import java.util.*;
public record Dish(UUID id, String name, String description, BigDecimal price, boolean available, List<ImageReference> images) {
    public Dish {
        Rules.required(id, "dish id"); name = Rules.text(name, "dish name", 150);
        if (description == null || description.length() > 2000) throw DomainException.invalid("Invalid description");
        PriceRange.validateAmount(price); images = Rules.list(images, 10, "dish images");
        Rules.unique(images.stream().map(ImageReference::id).toList(), "image ids");
    }
    public Dish updatePrice(BigDecimal value) { return new Dish(id, name, description, value, available, images); }
    public Dish changeAvailability(boolean value) { return new Dish(id, name, description, price, value, images); }
    public Dish withImages(List<ImageReference> values) { return new Dish(id, name, description, price, available, values); }
}
