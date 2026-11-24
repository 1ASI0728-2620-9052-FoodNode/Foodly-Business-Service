package com.foodly.business.domain.model;
public record GeoCoordinates(double latitude, double longitude) {
    public GeoCoordinates {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
            throw DomainException.invalid("Coordinates outside valid range");
    }
}
