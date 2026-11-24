package com.foodly.business.infrastructure.persistence;
import com.foodly.business.domain.model.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;

@Document("huariques")
public record HuariqueDocument(@Id UUID id, @Indexed(unique = true) String ownerId, String name, String address,
        CuisineType cuisineType, PriceRange priceRange, GeoCoordinates location, OpenStatus openStatus,
        List<String> tags, List<OpeningHours> openingHours, List<ImageReference> images, @Version Long version) {
    static HuariqueDocument from(Huarique h) {
        return new HuariqueDocument(h.id(), h.ownerId(), h.name(), h.address(), h.cuisineType(), h.priceRange(), h.location(), h.openStatus(), h.tags(), h.openingHours(), h.images(), h.version());
    }
    Huarique toDomain() { return new Huarique(id, ownerId, name, address, cuisineType, priceRange, location, openStatus, tags, openingHours, images, version); }
}
