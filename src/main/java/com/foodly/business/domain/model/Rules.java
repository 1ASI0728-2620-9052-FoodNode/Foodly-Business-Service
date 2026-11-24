package com.foodly.business.domain.model;

import java.util.*;

public final class Rules {
    private Rules() { }
    public static String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw DomainException.invalid(field + " is required (max " + max + ")");
        return value.trim();
    }
    public static <T> T required(T value, String field) {
        if (value == null) throw DomainException.invalid(field + " is required");
        return value;
    }
    public static <T> List<T> list(List<T> value, int max, String field) {
        if (value == null || value.size() > max || value.stream().anyMatch(Objects::isNull)) throw DomainException.invalid("Invalid " + field);
        return List.copyOf(value);
    }
    public static void unique(List<?> values, String field) {
        if (new HashSet<>(values).size() != values.size()) throw DomainException.invalid("Duplicate " + field);
    }
}
