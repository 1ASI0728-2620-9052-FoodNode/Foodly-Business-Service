package com.foodly.business.application;
import com.foodly.business.application.port.*;
import org.junit.jupiter.api.Test;
import static com.foodly.business.Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageApplicationServiceTest {
    @Test void compensatesUploadWhenMetadataTransactionFails() {
        var storage=mock(ImageStorage.class); var hs=mock(HuariqueApplicationService.class); var ms=mock(MenuApplicationService.class); var image=image();
        when(hs.owned(ID,"owner")).thenReturn(huarique()); when(storage.upload(any(),any())).thenReturn(image);
        when(hs.changeImage(ID,"owner",null,image)).thenThrow(new IllegalStateException("commit failed"));
        assertThatThrownBy(() -> new ImageApplicationService(storage,hs,ms).upload(ID,"owner",null,null,new byte[]{1},"image/png")).isInstanceOf(IllegalStateException.class);
        verify(storage).delete(image.publicId());
    }
    @Test void rejectsMissingImageBeforeUploading() {
        var storage=mock(ImageStorage.class); var hs=mock(HuariqueApplicationService.class); when(hs.owned(ID,"owner")).thenReturn(huarique());
        assertThatThrownBy(() -> new ImageApplicationService(storage,hs,mock(MenuApplicationService.class)).upload(ID,"owner",null,java.util.UUID.randomUUID(),new byte[]{1},"image/png")).hasMessage("Image not found");
        verifyNoInteractions(storage);
    }
}
