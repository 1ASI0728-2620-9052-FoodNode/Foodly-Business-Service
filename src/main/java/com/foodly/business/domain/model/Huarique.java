package com.foodly.business.domain.model;
import java.util.*;

public record Huarique(UUID id, String ownerId, String name, String address, CuisineType cuisineType,
                       PriceRange priceRange, GeoCoordinates location, OpenStatus openStatus,
                       List<String> tags, List<OpeningHours> openingHours, List<ImageReference> images, Long version) {
    public Huarique {
        Rules.required(id, "id"); ownerId = Rules.text(ownerId, "ownerId", 128);
        name = Rules.text(name, "name", 150); address = Rules.text(address, "address", 300);
        Rules.required(cuisineType, "cuisineType"); Rules.required(priceRange, "priceRange");
        Rules.required(location, "location"); Rules.required(openStatus, "openStatus");
        tags = Rules.list(tags, 20, "tags").stream().map(t -> Rules.text(t, "tag", 40)).toList();
        Rules.unique(tags, "tags"); openingHours = Rules.list(openingHours, 28, "openingHours");
        for (int i = 0; i < openingHours.size(); i++) for (int j = i + 1; j < openingHours.size(); j++) {
            var a = openingHours.get(i); var b = openingHours.get(j);
            if (a.day() == b.day() && a.opensAt().isBefore(b.closesAt()) && b.opensAt().isBefore(a.closesAt()))
                throw DomainException.invalid("Overlapping opening hours");
        }
        images = Rules.list(images, 20, "images"); Rules.unique(images.stream().map(ImageReference::id).toList(), "image ids");
    }
    public Huarique updateInformation(String name, String address, CuisineType cuisine, PriceRange price,
                                     GeoCoordinates coordinates, List<String> tags, List<OpeningHours> hours) {
        return new Huarique(id, ownerId, name, address, cuisine, price, coordinates, openStatus, tags, hours, images, version);
    }
    public Huarique changeOpenStatus(OpenStatus status) {
        return new Huarique(id, ownerId, name, address, cuisineType, priceRange, location, status, tags, openingHours, images, version);
    }
    public Huarique withImages(List<ImageReference> values) {
        return new Huarique(id, ownerId, name, address, cuisineType, priceRange, location, openStatus, tags, openingHours, values, version);
    }
}
