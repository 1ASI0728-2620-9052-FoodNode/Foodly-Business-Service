package com.foodly.business.application;
import com.foodly.business.domain.model.*;
import java.util.List;
public record ProfileData(String name, String address, CuisineType cuisineType, PriceRange priceRange,
                          GeoCoordinates location, List<String> tags, List<OpeningHours> openingHours) { }
