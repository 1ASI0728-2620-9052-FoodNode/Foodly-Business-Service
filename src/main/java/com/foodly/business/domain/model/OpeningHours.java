package com.foodly.business.domain.model;
import java.time.*;
public record OpeningHours(DayOfWeek day, LocalTime opensAt, LocalTime closesAt) {
    public OpeningHours {
        Rules.required(day, "day"); Rules.required(opensAt, "opensAt"); Rules.required(closesAt, "closesAt");
        if (!opensAt.isBefore(closesAt)) throw DomainException.invalid("Opening interval must finish on the same day after opening");
    }
}
