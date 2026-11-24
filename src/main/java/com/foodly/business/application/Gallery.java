package com.foodly.business.application;
import com.foodly.business.domain.model.*;
import java.util.*;
final class Gallery {
    private Gallery() { }
    static List<ImageReference> change(List<ImageReference> images, UUID replacedId, ImageReference added) {
        var result = new ArrayList<>(images);
        if (replacedId != null && !result.removeIf(i -> i.id().equals(replacedId))) throw DomainException.missing("Image not found");
        if (added != null) result.add(added);
        return result;
    }
}
