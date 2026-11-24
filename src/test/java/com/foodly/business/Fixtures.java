package com.foodly.business;
import com.foodly.business.application.ProfileData;
import com.foodly.business.domain.model.*;
import java.math.BigDecimal;
import java.util.*;
public final class Fixtures {
    private Fixtures() { }
    public static final UUID ID = UUID.fromString("d297baf2-2495-4c38-9beb-56d18508ab39");
    public static final UUID DISH = UUID.fromString("3c7a6491-2e22-4eb7-b627-bdfb21172c48");
    public static ProfileData profile() { return new ProfileData("El Huarique", "Lima 123", CuisineType.CRIOLLA, new PriceRange(BigDecimal.TEN, new BigDecimal("50")), new GeoCoordinates(-12,-77), List.of("casero"), List.of()); }
    public static Huarique huarique() {
        var d = profile(); return new Huarique(ID,"owner",d.name(),d.address(),d.cuisineType(),d.priceRange(),d.location(),OpenStatus.CLOSED,d.tags(),d.openingHours(),List.of(),0L);
    }
    public static Menu menu() { return new Menu(ID, ID, List.of(new MenuCategory(ID,"Fondos",List.of(new Dish(DISH,"Arroz","Con pollo",BigDecimal.TEN,true,List.of())))),0L); }
    public static ImageReference image() { return new ImageReference(UUID.randomUUID(),"foodly/business/test","https://res.cloudinary.com/example/image/upload/test.png"); }
    public static final String PROFILE_JSON = """
        {"name":"El Huarique","address":"Lima 123","cuisineType":"CRIOLLA",
         "priceRange":{"minimum":10,"maximum":50},"location":{"latitude":-12,"longitude":-77},"tags":["casero"],"openingHours":[]}
        """;
}
