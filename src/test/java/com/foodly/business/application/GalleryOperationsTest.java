package com.foodly.business.application;

import com.foodly.business.application.port.*;
import com.foodly.business.domain.model.*;
import com.foodly.business.domain.repository.*;
import com.foodly.business.domain.service.BusinessDomainService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.stream.IntStream;
import static com.foodly.business.Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Application/domain behavior with repository/storage mocks; not evidence of a real Cloudinary account. */
class GalleryOperationsTest {
    IHuariqueRepository hrepo;
    IMenuRepository mrepo;
    EventOutbox outbox;
    ImageStorage storage;
    ImageApplicationService images;

    @BeforeEach void setup() {
        hrepo=mock(IHuariqueRepository.class); mrepo=mock(IMenuRepository.class);
        outbox=mock(EventOutbox.class); storage=mock(ImageStorage.class);
        var domain=new BusinessDomainService(hrepo);
        var hs=new HuariqueApplicationService(hrepo,domain,outbox);
        var ms=new MenuApplicationService(mrepo,hs,domain,outbox);
        images=new ImageApplicationService(storage,hs,ms);
        when(hrepo.findById(ID)).thenReturn(Optional.of(huarique()));
        when(mrepo.findByHuariqueId(ID)).thenReturn(Optional.of(menu()));
        when(hrepo.save(any())).thenAnswer(i->i.getArgument(0));
        when(mrepo.save(any())).thenAnswer(i->i.getArgument(0));
    }
    @Test void associatesUploadWithOwnedHuarique() {
        var image=image(); when(storage.upload(any(),any())).thenReturn(image);
        assertThat(images.upload(ID,"owner",null,null,new byte[]{1},"image/png")).isEqualTo(image);
        var saved=ArgumentCaptor.forClass(Huarique.class); verify(hrepo).save(saved.capture());
        assertThat(saved.getValue().images()).containsExactly(image);
        verify(outbox).append(argThat(e->e.type().equals("PerfilDelHuariqueActualizado")));
    }
    @Test void associatesUploadWithDish() {
        var image=image(); when(storage.upload(any(),any())).thenReturn(image);
        images.upload(ID,"owner",DISH,null,new byte[]{1},"image/png");
        var saved=ArgumentCaptor.forClass(Menu.class); verify(mrepo).save(saved.capture());
        assertThat(saved.getValue().dish(DISH).images()).containsExactly(image);
        verify(outbox).append(argThat(e->e.type().equals("MenuActualizado")));
    }
    @Test void differentOwnerCannotUploadOrReachProvider() {
        assertThatThrownBy(()->images.upload(ID,"other",null,null,new byte[]{1},"image/png"))
                .isInstanceOf(DomainException.class).satisfies(e->assertThat(((DomainException)e).kind()).isEqualTo(DomainException.Kind.FORBIDDEN));
        verifyNoInteractions(storage,outbox); verify(hrepo,never()).save(any());
    }
    @Test void differentOwnerCannotDeleteOrQueueCleanup() {
        assertThatThrownBy(()->images.delete(ID,"other",DISH,UUID.randomUUID())).isInstanceOf(DomainException.class);
        verifyNoInteractions(storage,outbox); verify(mrepo,never()).save(any());
    }
    @Test void deletingHuariqueReferenceQueuesCleanupWithoutCallingCloudinary() {
        var image=image(); when(hrepo.findById(ID)).thenReturn(Optional.of(huarique().withImages(List.of(image))));
        images.delete(ID,"owner",null,image.id());
        verify(hrepo).save(argThat(h->h.images().isEmpty()));
        verify(outbox).append(argThat(e->e.type().equals("internal.ImageDeletionRequested")&&e.data().equals(image)));
        verifyNoInteractions(storage);
    }
    @Test void deletingDishReferenceQueuesCleanupWithoutCallingCloudinary() {
        var image=image(); when(mrepo.findByHuariqueId(ID)).thenReturn(Optional.of(menu().updateDish(DISH,d->d.withImages(List.of(image)))));
        images.delete(ID,"owner",DISH,image.id());
        verify(mrepo).save(argThat(m->m.dish(DISH).images().isEmpty()));
        verify(outbox).append(argThat(e->e.type().equals("internal.ImageDeletionRequested")&&e.data().equals(image)));
        verifyNoInteractions(storage);
    }
    @Test void queriesReferencesWithoutContactingProvider() {
        var image=image(); when(hrepo.findById(ID)).thenReturn(Optional.of(huarique().withImages(List.of(image))));
        when(mrepo.findByHuariqueId(ID)).thenReturn(Optional.of(menu().updateDish(DISH,d->d.withImages(List.of(image)))));
        assertThat(images.list(ID,null)).containsExactly(image);
        assertThat(images.get(ID,null,image.id())).isEqualTo(image);
        assertThat(images.get(ID,DISH,image.id())).isEqualTo(image);
        verifyNoInteractions(storage,outbox);
    }
    @Test void fullHuariqueGalleryIsRejectedBeforeProviderCall() {
        var gallery=IntStream.range(0,20).mapToObj(i->new ImageReference(UUID.randomUUID(),"foodly/business/test-"+i,"https://example.com/"+i+".png")).toList();
        when(hrepo.findById(ID)).thenReturn(Optional.of(huarique().withImages(gallery)));
        assertThatThrownBy(()->images.upload(ID,"owner",null,null,new byte[]{1},"image/png")).hasMessage("Gallery is full");
        verifyNoInteractions(storage,outbox);
    }
    @Test void fullDishGalleryIsRejectedBeforeProviderCall() {
        var gallery=IntStream.range(0,10).mapToObj(i->new ImageReference(UUID.randomUUID(),"foodly/business/test-"+i,"https://example.com/"+i+".png")).toList();
        when(mrepo.findByHuariqueId(ID)).thenReturn(Optional.of(menu().updateDish(DISH,d->d.withImages(gallery))));
        assertThatThrownBy(()->images.upload(ID,"owner",DISH,null,new byte[]{1},"image/png")).hasMessage("Gallery is full");
        verifyNoInteractions(storage,outbox);
    }
    @Test void replacementRetainsNewReferenceAndQueuesOnlyOldAssetCleanup() {
        var old=new ImageReference(UUID.randomUUID(),"foodly/business/old","https://example.com/old.png");
        var next=image(); when(hrepo.findById(ID)).thenReturn(Optional.of(huarique().withImages(List.of(old))));
        when(storage.upload(any(),any())).thenReturn(next);
        images.upload(ID,"owner",null,old.id(),new byte[]{1},"image/png");
        verify(hrepo).save(argThat(h->h.images().equals(List.of(next))));
        verify(outbox).append(argThat(e->e.type().equals("internal.ImageDeletionRequested")&&e.data().equals(old)));
        verify(storage,never()).delete(any());
    }
    @Test void nonexistentDishIsRejectedBeforeProviderCall() {
        assertThatThrownBy(()->images.upload(ID,"owner",UUID.randomUUID(),null,new byte[]{1},"image/png")).hasMessage("Dish not found");
        verifyNoInteractions(storage,outbox);
    }
}
