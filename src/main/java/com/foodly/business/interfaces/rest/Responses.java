package com.foodly.business.interfaces.rest;
import com.foodly.business.domain.model.*;
import java.math.BigDecimal;
import java.util.*;
public final class Responses {
    private Responses() { }
    public record Image(UUID id, String url) { public static Image from(ImageReference i) { return new Image(i.id(), i.url()); } }
    public record HuariqueView(UUID id, String ownerId, String name, String address, CuisineType cuisineType, PriceRange priceRange,
            GeoCoordinates location, OpenStatus openStatus, List<String> tags, List<OpeningHours> openingHours, List<Image> images, Long version) {
        public static HuariqueView from(Huarique h) { return new HuariqueView(h.id(), h.ownerId(), h.name(), h.address(), h.cuisineType(), h.priceRange(), h.location(), h.openStatus(), h.tags(), h.openingHours(), h.images().stream().map(Image::from).toList(), h.version()); }
    }
    public record DishView(UUID id, String name, String description, BigDecimal price, boolean available, List<Image> images) {
        static DishView from(Dish d) { return new DishView(d.id(), d.name(), d.description(), d.price(), d.available(), d.images().stream().map(Image::from).toList()); }
    }
    public record CategoryView(UUID id, String name, List<DishView> dishes) { }
    public record MenuView(UUID id, UUID huariqueId, List<CategoryView> categories, Long version) {
        public static MenuView from(Menu m) { return new MenuView(m.id(), m.huariqueId(), m.categories().stream().map(c -> new CategoryView(c.id(), c.name(), c.dishes().stream().map(DishView::from).toList())).toList(), m.version()); }
    }
}
