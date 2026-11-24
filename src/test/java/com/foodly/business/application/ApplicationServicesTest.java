package com.foodly.business.application;
import com.foodly.business.application.port.*;
import com.foodly.business.domain.model.*;
import com.foodly.business.domain.repository.*;
import com.foodly.business.domain.service.BusinessDomainService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static com.foodly.business.Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationServicesTest {
    IHuariqueRepository hrepo; IMenuRepository mrepo; EventOutbox outbox; HuariqueApplicationService hs; MenuApplicationService ms;
    @BeforeEach void setup() {
        hrepo=mock(IHuariqueRepository.class); mrepo=mock(IMenuRepository.class); outbox=mock(EventOutbox.class);
        var domain=new BusinessDomainService(hrepo); hs=new HuariqueApplicationService(hrepo,domain,outbox); ms=new MenuApplicationService(mrepo,hs,domain,outbox);
    }
    void existing() { when(hrepo.findById(ID)).thenReturn(Optional.of(huarique())); when(mrepo.findByHuariqueId(ID)).thenReturn(Optional.of(menu())); }
    @Test void registersAndEnqueuesEvent() {
        when(hrepo.save(any())).thenAnswer(i -> i.getArgument(0));
        var saved=hs.register("owner",profile()); assertThat(saved.ownerId()).isEqualTo("owner"); assertThat(saved.openStatus()).isEqualTo(OpenStatus.CLOSED);
        var event=ArgumentCaptor.forClass(EventOutbox.Event.class); verify(outbox).append(event.capture()); assertThat(event.getValue().type()).isEqualTo("HuariqueRegistrado"); assertThat(event.getValue().data()).isEqualTo(saved);
    }
    @Test void duplicateOwnerDoesNotWrite() { when(hrepo.findByOwnerId("owner")).thenReturn(Optional.of(huarique())); assertThatThrownBy(() -> hs.register("owner",profile())).isInstanceOf(DomainException.class); verify(hrepo,never()).save(any()); verifyNoInteractions(outbox); }
    @Test void rejectsOtherOwnerBeforeSaving() { existing(); assertThatThrownBy(() -> hs.update(ID,"other",profile(),0)).isInstanceOf(DomainException.class); verify(hrepo,never()).save(any()); verifyNoInteractions(outbox); }
    @Test void rejectsStaleVersion() { existing(); assertThatThrownBy(() -> hs.update(ID,"owner",profile(),5)).isInstanceOf(DomainException.class).hasMessageContaining("Stale"); verifyNoInteractions(outbox); }
    @Test void statusChangePublishesDedicatedEvent() { existing(); when(hrepo.save(any())).thenAnswer(i->i.getArgument(0)); assertThat(hs.changeStatus(ID,"owner",OpenStatus.OPEN,0).openStatus()).isEqualTo(OpenStatus.OPEN); verify(outbox).append(argThat(e -> e.type().equals("EstadoAbiertoCerradoCambiado"))); }
    @Test void unchangedStatusDoesNotPublish() { existing(); hs.changeStatus(ID,"owner",OpenStatus.CLOSED,0); verifyNoInteractions(outbox); }
    @Test void createsMenuAndPublishesCreation() { when(hrepo.findById(ID)).thenReturn(Optional.of(huarique())); when(mrepo.save(any())).thenAnswer(i->i.getArgument(0)); ms.replace(ID,"owner",menu().categories(),null); verify(outbox).append(argThat(e -> e.type().equals("MenuCreado"))); }
    @Test void menuCannotReferenceMissingHuarique() { assertThatThrownBy(() -> ms.replace(ID,"owner",List.of(),null)).isInstanceOf(DomainException.class); verifyNoInteractions(mrepo,outbox); }
    @Test void existingMenuRequiresVersion() { existing(); assertThatThrownBy(() -> ms.replace(ID,"owner",List.of(),null)).isInstanceOf(DomainException.class).hasMessageContaining("version"); verify(mrepo,never()).save(any()); }
    @Test void editsDishAvailabilityAndPublishes() { existing(); when(mrepo.save(any())).thenAnswer(i->i.getArgument(0)); assertThat(ms.changeDish(ID,"owner",DISH,0,d->d.changeAvailability(false)).dish(DISH).available()).isFalse(); verify(outbox).append(argThat(e->e.type().equals("MenuActualizado"))); }
    @Test void replacingMenuPreservesDishImagesAndIgnoresForgedImages() {
        existing(); var image=image(); var old=menu().updateDish(DISH,d->d.withImages(List.of(image)));
        when(mrepo.findByHuariqueId(ID)).thenReturn(Optional.of(old)); when(mrepo.save(any())).thenAnswer(i->i.getArgument(0));
        assertThat(ms.replace(ID,"owner",menu().categories(),0L).dish(DISH).images()).containsExactly(image);
    }
    @Test void removingDishQueuesImageDeletion() {
        existing(); var old=menu().updateDish(DISH,d->d.withImages(List.of(image()))); when(mrepo.findByHuariqueId(ID)).thenReturn(Optional.of(old)); when(mrepo.save(any())).thenAnswer(i->i.getArgument(0));
        ms.replace(ID,"owner",List.of(),0L); verify(outbox).append(argThat(e -> e.type().equals("internal.ImageDeletionRequested")));
    }
    @Test void persistenceFailureDoesNotEnqueueSuccess() { when(hrepo.save(any())).thenThrow(new IllegalStateException("database")); assertThatThrownBy(() -> hs.register("owner",profile())).isInstanceOf(IllegalStateException.class); verifyNoInteractions(outbox); }
    @Test void readMissingMenuReturnsNotFound() { when(hrepo.findById(ID)).thenReturn(Optional.of(huarique())); assertThatThrownBy(() -> ms.get(ID)).isInstanceOf(DomainException.class).hasMessage("Menu not found"); }
    @Test void invalidPaginationRejected() { assertThatThrownBy(() -> hs.list(-1,10)).isInstanceOf(DomainException.class); assertThatThrownBy(() -> hs.list(0,101)).isInstanceOf(DomainException.class); }
}
