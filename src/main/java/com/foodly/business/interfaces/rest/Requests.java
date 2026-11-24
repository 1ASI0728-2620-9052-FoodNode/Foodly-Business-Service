package com.foodly.business.interfaces.rest;

import com.foodly.business.application.ProfileData;
import com.foodly.business.domain.model.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Requests {
    private Requests() { }
    public record Coordinates(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                              @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {
        GeoCoordinates toDomain() { return new GeoCoordinates(latitude, longitude); }
    }
    public record Prices(@NotNull @DecimalMin("0") @Digits(integer=8, fraction=2) BigDecimal minimum,
                         @NotNull @DecimalMin("0") @Digits(integer=8, fraction=2) BigDecimal maximum) {
        PriceRange toDomain() { return new PriceRange(minimum, maximum); }
    }
    public record Hours(@NotNull DayOfWeek day, @NotNull LocalTime opensAt, @NotNull LocalTime closesAt) {
        OpeningHours toDomain() { return new OpeningHours(day, opensAt, closesAt); }
    }
    public record Profile(@NotBlank @Size(max=150) String name, @NotBlank @Size(max=300) String address,
            @NotNull CuisineType cuisineType, @NotNull @Valid Prices priceRange, @NotNull @Valid Coordinates location,
            @NotNull @Size(max=20) List<@NotBlank @Size(max=40) String> tags,
            @NotNull @Size(max=28) List<@NotNull @Valid Hours> openingHours, @PositiveOrZero Long version) {
        ProfileData toData() { return new ProfileData(name, address, cuisineType, priceRange.toDomain(), location.toDomain(), tags, openingHours.stream().map(Hours::toDomain).toList()); }
        long requiredVersion() { if (version == null) throw DomainException.invalid("version is required"); return version; }
    }
    public record MenuBody(@NotNull @Size(max=30) List<@NotNull @Valid Category> categories, @PositiveOrZero Long version) {
        List<MenuCategory> toDomain() { return categories.stream().map(Category::toDomain).toList(); }
    }
    public record Category(UUID id, @NotBlank @Size(max=100) String name, @NotNull @Size(max=100) List<@NotNull @Valid DishBody> dishes) {
        MenuCategory toDomain() { return new MenuCategory(id == null ? UUID.randomUUID() : id, name, dishes.stream().map(DishBody::toDomain).toList()); }
    }
    public record DishBody(UUID id, @NotBlank @Size(max=150) String name, @NotNull @Size(max=2000) String description,
            @NotNull @DecimalMin("0") @Digits(integer=8, fraction=2) BigDecimal price, @NotNull Boolean available) {
        Dish toDomain() { return new Dish(id == null ? UUID.randomUUID() : id, name, description, price, available, List.of()); }
    }
    public record Status(@NotNull OpenStatus openStatus, @NotNull @PositiveOrZero Long version) { }
    public record Price(@NotNull @DecimalMin("0") @Digits(integer=8, fraction=2) BigDecimal price, @NotNull @PositiveOrZero Long version) { }
    public record Availability(@NotNull Boolean available, @NotNull @PositiveOrZero Long version) { }
}
