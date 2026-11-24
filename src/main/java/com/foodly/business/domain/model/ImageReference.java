package com.foodly.business.domain.model;
import java.net.URI;
import java.util.UUID;
public record ImageReference(UUID id, String publicId, String url) {
    public ImageReference {
        Rules.required(id, "image id"); publicId = Rules.text(publicId, "publicId", 255);
        url = Rules.text(url, "url", 2048);
        try {
            var uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null)
                throw DomainException.invalid("Image URL must be an absolute HTTPS URL without credentials");
        }
        catch (IllegalArgumentException e) { throw DomainException.invalid("Invalid image URL"); }
    }
}
