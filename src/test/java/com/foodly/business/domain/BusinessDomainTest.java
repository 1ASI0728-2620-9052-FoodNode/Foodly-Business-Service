package com.foodly.business.domain;
import com.foodly.business.domain.model.*;
import com.foodly.business.domain.repository.IHuariqueRepository;
import com.foodly.business.domain.service.BusinessDomainService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.foodly.business.Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessDomainTest {
    @ParameterizedTest @CsvSource({"-90,-180","90,180","-12,-77","0,0"})
    void acceptsCoordinates(double lat, double lon) { assertThat(new GeoCoordinates(lat,lon).latitude()).isEqualTo(lat); }
    @ParameterizedTest @CsvSource({"-91,0","91,0","0,-181","0,181","NaN,0","0,Infinity"})
    void rejectsInvalidCoordinates(double lat, double lon) { assertThatThrownBy(() -> new GeoCoordinates(lat,lon)).isInstanceOf(DomainException.class); }
    @ParameterizedTest @ValueSource(strings={"-1","0.001","100000000"})
    void rejectsInvalidPrice(String price) { assertThatThrownBy(() -> menu().dish(DISH).updatePrice(new BigDecimal(price))).isInstanceOf(DomainException.class); }
    @Test void rejectsInvertedPriceRange() { assertThatThrownBy(() -> new PriceRange(BigDecimal.TEN,BigDecimal.ZERO)).isInstanceOf(DomainException.class); }
    @Test void permitsFreeDishAndTwoDecimals() { assertThat(menu().dish(DISH).updatePrice(BigDecimal.ZERO).price()).isZero(); assertThat(menu().dish(DISH).updatePrice(new BigDecimal("2.50")).price()).isEqualByComparingTo("2.50"); }
    @Test void changesAvailabilityWithoutMutatingOriginal() { var old = menu(); var changed = old.updateDish(DISH,d -> d.changeAvailability(false)); assertThat(changed.dish(DISH).available()).isFalse(); assertThat(old.dish(DISH).available()).isTrue(); }
    @Test void rejectsDuplicateDishIdsAcrossCategories() {
        var menu = menu(); var category = menu.categories().get(0);
        assertThatThrownBy(() -> menu.withCategories(List.of(category,new MenuCategory(UUID.randomUUID(),"Otros",category.dishes())))).isInstanceOf(DomainException.class);
    }
    @Test void copiesCollections() { var list = new ArrayList<>(menu().categories()); var m = menu().withCategories(list); list.clear(); assertThat(m.categories()).hasSize(1); assertThatThrownBy(() -> m.categories().clear()).isInstanceOf(UnsupportedOperationException.class); }
    @Test void rejectsOverlappingHours() {
        var h = huarique(); var hours = List.of(new OpeningHours(DayOfWeek.MONDAY,LocalTime.of(9,0),LocalTime.of(12,0)),new OpeningHours(DayOfWeek.MONDAY,LocalTime.of(11,0),LocalTime.of(15,0)));
        assertThatThrownBy(() -> h.updateInformation(h.name(),h.address(),h.cuisineType(),h.priceRange(),h.location(),h.tags(),hours)).isInstanceOf(DomainException.class);
    }
    @Test void ownerCannotRegisterSecondHuarique() {
        var repo = mock(IHuariqueRepository.class); var other = huarique(); when(repo.findByOwnerId("owner")).thenReturn(Optional.of(other));
        var d = profile(); var candidate = new Huarique(UUID.randomUUID(),"owner",d.name(),d.address(),d.cuisineType(),d.priceRange(),d.location(),OpenStatus.CLOSED,List.of(),List.of(),List.of(),null);
        assertThatThrownBy(() -> new BusinessDomainService(repo).validateHuarique(candidate)).isInstanceOf(DomainException.class).hasMessageContaining("already");
    }
    @Test void menuNeedsExistingHuarique() { var repo=mock(IHuariqueRepository.class); assertThatThrownBy(() -> new BusinessDomainService(repo).validateMenu(menu())).isInstanceOf(DomainException.class).hasMessageContaining("not found"); }
    @Test void authorizationUsesOwnerIdentity() { var service = new BusinessDomainService(mock(IHuariqueRepository.class)); assertThatThrownBy(() -> service.authorize(huarique(),"other")).isInstanceOf(DomainException.class); service.authorize(huarique(),"owner"); }
}
