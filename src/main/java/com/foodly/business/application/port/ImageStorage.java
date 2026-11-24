package com.foodly.business.application.port;
import com.foodly.business.domain.model.ImageReference;
public interface ImageStorage {
    ImageReference upload(byte[] data, String contentType);
    void delete(String publicId);
}
