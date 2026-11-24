package com.foodly.business.application;

import com.foodly.business.application.port.ImageStorage;
import com.foodly.business.domain.model.*;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ImageApplicationService {
    private final ImageStorage storage;
    private final HuariqueApplicationService huariques;
    private final MenuApplicationService menus;
    public ImageApplicationService(ImageStorage storage, HuariqueApplicationService huariques, MenuApplicationService menus) {
        this.storage = storage; this.huariques = huariques; this.menus = menus;
    }
    public ImageReference upload(UUID id, String actor, UUID dishId, UUID replacedId, byte[] bytes, String contentType) {
        var h = huariques.owned(id, actor);
        var images = dishId == null ? h.images() : menus.get(id).dish(dishId).images();
        Gallery.change(images, replacedId, null); // reject nonexistent replacement before contacting Cloudinary
        if (replacedId == null && images.size() >= (dishId == null ? 20 : 10)) throw DomainException.invalid("Gallery is full");
        var image = storage.upload(bytes, contentType);
        try {
            if (dishId == null) huariques.changeImage(id, actor, replacedId, image);
            else menus.changeImage(id, actor, dishId, replacedId, image);
            return image;
        } catch (RuntimeException failure) {
            try { storage.delete(image.publicId()); }
            catch (RuntimeException cleanupFailure) {
                LoggerFactory.getLogger(getClass()).error("Cloudinary compensation failed for asset {}. Manual cleanup required", image.publicId());
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }
    public void delete(UUID id, String actor, UUID dishId, UUID imageId) {
        if (dishId == null) huariques.changeImage(id, actor, imageId, null);
        else menus.changeImage(id, actor, dishId, imageId, null);
    }
    public List<ImageReference> list(UUID id, UUID dishId) {
        return dishId == null ? huariques.get(id).images() : menus.get(id).dish(dishId).images();
    }
    public ImageReference get(UUID id, UUID dishId, UUID imageId) {
        return list(id, dishId).stream().filter(i -> i.id().equals(imageId)).findFirst()
                .orElseThrow(() -> DomainException.missing("Image not found"));
    }
}
