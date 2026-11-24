package com.foodly.business.domain;

import com.foodly.business.domain.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ImageReferenceTest {
    @ParameterizedTest
    @ValueSource(strings={"http://example.com/image.png", "https:opaque-image", "/relative/image.png", "https://user:password@example.com/image.png"})
    void rejectsInvalidDeliveryUrl(String url) {
        assertThatThrownBy(()->new ImageReference(UUID.randomUUID(), "foodly/business/test", url)).isInstanceOf(DomainException.class);
    }
}
