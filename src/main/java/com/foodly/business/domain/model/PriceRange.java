package com.foodly.business.domain.model;
import java.math.BigDecimal;
public record PriceRange(BigDecimal minimum, BigDecimal maximum) {
    public PriceRange {
        validateAmount(minimum); validateAmount(maximum);
        if (minimum.compareTo(maximum) > 0) throw DomainException.invalid("Minimum price exceeds maximum price");
    }
    public static void validateAmount(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("99999999.99")) > 0 || value.stripTrailingZeros().scale() > 2)
            throw DomainException.invalid("Price must be non-negative with at most two decimals");
    }
}
