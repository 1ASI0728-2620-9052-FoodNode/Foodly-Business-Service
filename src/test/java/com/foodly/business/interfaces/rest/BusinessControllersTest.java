package com.foodly.business.interfaces.rest;

import com.foodly.business.application.*;
import com.foodly.business.domain.model.*;
import com.foodly.business.infrastructure.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static com.foodly.business.Fixtures.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({HuariqueController.class,MenuController.class,ImageController.class})
@Import(SecurityConfiguration.class)
class BusinessControllersTest {
    @Autowired MockMvc mvc;
    @MockitoBean HuariqueApplicationService huariques;
    @MockitoBean MenuApplicationService menus;
    @MockitoBean ImageApplicationService images;
    @MockitoBean JwtDecoder decoder;
    RequestPostProcessor owner() { return jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_OWNER")); }
    RequestPostProcessor explorer() { return jwt().jwt(j->j.subject("explorer")).authorities(new SimpleGrantedAuthority("ROLE_EXPLORER")); }
    @Test void registers201WithLocation() throws Exception {
        when(huariques.register(eq("owner"),any())).thenReturn(huarique());
        mvc.perform(post("/business-api/huariques").with(owner()).contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isCreated()).andExpect(header().string("Location","/business-api/huariques/"+ID)).andExpect(jsonPath("$.id").value(ID.toString())).andExpect(jsonPath("$.version").value(0));
    }
    @Test void get200() throws Exception { when(huariques.get(ID)).thenReturn(huarique()); mvc.perform(get("/business-api/huariques/"+ID).with(explorer())).andExpect(status().isOk()).andExpect(jsonPath("$.cuisineType").value("CRIOLLA")); }
    @Test void missingResource404() throws Exception { when(huariques.get(ID)).thenThrow(DomainException.missing("Huarique not found")); mvc.perform(get("/business-api/huariques/"+ID).with(explorer())).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404)); }
    @Test void missingToken401() throws Exception { mvc.perform(get("/business-api/huariques/"+ID)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate","Bearer")).andExpect(jsonPath("$.status").value(401)); verifyNoInteractions(huariques); }
    @Test void invalidToken401() throws Exception { when(decoder.decode("invalid")).thenThrow(new BadJwtException("bad signature")); mvc.perform(get("/business-api/huariques/"+ID).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized()); }
    @Test void explorerCannotRegister403() throws Exception { mvc.perform(post("/business-api/huariques").with(explorer()).contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON)).andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403)); verifyNoInteractions(huariques); }
    @Test void missingFields400() throws Exception { mvc.perform(post("/business-api/huariques").with(owner()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.name").exists()).andExpect(jsonPath("$.fields.location").exists()); verifyNoInteractions(huariques); }
    @Test void rejectsInvalidCoordinates400() throws Exception { mvc.perform(post("/business-api/huariques").with(owner()).contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON.replace("-12","91"))).andExpect(status().isBadRequest()); verifyNoInteractions(huariques); }
    @Test void rejectsMissingLatitude400() throws Exception { mvc.perform(post("/business-api/huariques").with(owner()).contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON.replace("\"latitude\":-12,",""))).andExpect(status().isBadRequest()); }
    @Test void rejectsUnknownFields400() throws Exception { mvc.perform(post("/business-api/huariques").with(owner()).contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON.replace("\"name\":","\"ownerId\":\"forged\",\"name\":"))).andExpect(status().isBadRequest()); }
    @Test void malformedJson400() throws Exception { mvc.perform(post("/business-api/huariques").with(owner()).contentType(MediaType.APPLICATION_JSON).content("{" )).andExpect(status().isBadRequest()); }
    @Test void malformedId400() throws Exception { mvc.perform(get("/business-api/huariques/invalid").with(explorer())).andExpect(status().isBadRequest()); }
    @Test void duplicate409() throws Exception { when(huariques.register(any(),any())).thenThrow(new DomainException(DomainException.Kind.CONFLICT,"Owner already has a huarique")); mvc.perform(post("/business-api/huariques").with(owner()).contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON)).andExpect(status().isConflict()); }
    @Test void otherOwner403() throws Exception { when(huariques.update(eq(ID),eq("owner"),any(),eq(0L))).thenThrow(new DomainException(DomainException.Kind.FORBIDDEN,"Only owner")); mvc.perform(put("/business-api/huariques/"+ID).with(owner()).contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON.replace("\"name\":","\"version\":0,\"name\":"))).andExpect(status().isForbidden()); }
    @Test void updateRequiresVersion400() throws Exception { mvc.perform(put("/business-api/huariques/"+ID).with(owner()).contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON)).andExpect(status().isBadRequest()); verifyNoInteractions(huariques); }
    @Test void menuPut200() throws Exception { when(menus.replace(eq(ID),eq("owner"),any(),isNull())).thenReturn(menu()); mvc.perform(put("/business-api/huariques/"+ID+"/menu").with(owner()).contentType(MediaType.APPLICATION_JSON).content("{\"categories\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.categories[0].dishes[0].available").value(true)); }
    @Test void nestedMenuPrice400() throws Exception { String body="""
        {"categories":[{"name":"Fondos","dishes":[{"name":"Arroz","description":"","price":-1,"available":true}]}]}
        """; mvc.perform(put("/business-api/huariques/"+ID+"/menu").with(owner()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['categories[0].dishes[0].price']").exists()); verifyNoInteractions(menus); }
    @Test void menuQuery200() throws Exception { when(menus.get(ID)).thenReturn(menu()); mvc.perform(get("/business-api/huariques/"+ID+"/menu").with(explorer())).andExpect(status().isOk()); }
    @Test void negativePricePatch400() throws Exception { mvc.perform(patch("/business-api/huariques/"+ID+"/menu/dishes/"+DISH+"/price").with(owner()).contentType(MediaType.APPLICATION_JSON).content("{\"price\":-2,\"version\":0}")).andExpect(status().isBadRequest()); verifyNoInteractions(menus); }
    @Test void availabilityPatch200() throws Exception { when(menus.changeDish(eq(ID),eq("owner"),eq(DISH),eq(0L),any())).thenReturn(menu()); mvc.perform(patch("/business-api/huariques/"+ID+"/menu/dishes/"+DISH+"/availability").with(owner()).contentType(MediaType.APPLICATION_JSON).content("{\"available\":false,\"version\":0}")).andExpect(status().isOk()); }
    @Test void statusPatch200() throws Exception { when(huariques.changeStatus(ID,"owner",OpenStatus.OPEN,0)).thenReturn(huarique().changeOpenStatus(OpenStatus.OPEN)); mvc.perform(patch("/business-api/huariques/"+ID+"/open-status").with(owner()).contentType(MediaType.APPLICATION_JSON).content("{\"openStatus\":\"OPEN\",\"version\":0}")).andExpect(status().isOk()).andExpect(jsonPath("$.openStatus").value("OPEN")); }
    @Test void imageUpload201() throws Exception { var image=image(); when(images.upload(eq(ID),eq("owner"),isNull(),isNull(),any(),eq("image/png"))).thenReturn(image); mvc.perform(multipart("/business-api/huariques/"+ID+"/images").file(new MockMultipartFile("file","image.png","image/png",new byte[]{1})).with(owner())).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(image.id().toString())).andExpect(jsonPath("$.publicId").doesNotExist()); }
    @Test void imageDeletion204() throws Exception { mvc.perform(delete("/business-api/huariques/"+ID+"/images/"+DISH).with(owner())).andExpect(status().isNoContent()); verify(images).delete(ID,"owner",null,DISH); }
    @Test void imageMetadata200() throws Exception { var image=image(); when(images.get(ID,DISH,image.id())).thenReturn(image); mvc.perform(get("/business-api/huariques/"+ID+"/menu/dishes/"+DISH+"/images/"+image.id()).with(explorer())).andExpect(status().isOk()).andExpect(jsonPath("$.url").value(image.url())); }
    @Test void explorerCannotUploadImage403() throws Exception { mvc.perform(multipart("/business-api/huariques/"+ID+"/images").file(new MockMultipartFile("file","image.png","image/png",new byte[]{1})).with(explorer())).andExpect(status().isForbidden()); verifyNoInteractions(images); }
    @Test void imageDeletionRequiresToken401() throws Exception { mvc.perform(delete("/business-api/huariques/"+ID+"/images/"+DISH)).andExpect(status().isUnauthorized()); verifyNoInteractions(images); }
    @Test void imageUploadRequiresFilePart400() throws Exception { mvc.perform(multipart("/business-api/huariques/"+ID+"/images").with(owner())).andExpect(status().isBadRequest()); verifyNoInteractions(images); }
    @Test void imageReplacement200() throws Exception { var image=image(); when(images.upload(eq(ID),eq("owner"),isNull(),eq(DISH),any(),eq("image/png"))).thenReturn(image); mvc.perform(multipart("/business-api/huariques/"+ID+"/images/"+DISH).file(new MockMultipartFile("file","image.png","image/png",new byte[]{1})).with(r->{r.setMethod("PUT");return r;}).with(owner())).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(image.id().toString())); }
    @Test void imageListReturnsOnlyPublicReferences200() throws Exception { var image=image(); when(images.list(ID,null)).thenReturn(java.util.List.of(image)); mvc.perform(get("/business-api/huariques/"+ID+"/images").with(explorer())).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(image.id().toString())).andExpect(jsonPath("$[0].publicId").doesNotExist()); }
    @Test void externalImageFailure503IsSanitized() throws Exception { when(images.upload(eq(ID),eq("owner"),isNull(),isNull(),any(),any())).thenThrow(new com.foodly.business.application.port.IntegrationException("sensitive provider details")); mvc.perform(multipart("/business-api/huariques/"+ID+"/images").file(new MockMultipartFile("file","image.png","image/png",new byte[]{1})).with(owner())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("A required dependency is unavailable")); }
    @Test void imageOwnerMismatchReturns403() throws Exception { doThrow(new DomainException(DomainException.Kind.FORBIDDEN,"Only owner")).when(images).delete(ID,"owner",null,DISH); mvc.perform(delete("/business-api/huariques/"+ID+"/images/"+DISH).with(owner())).andExpect(status().isForbidden()); }
    @Test void imageUploadRejectsJson415() throws Exception { mvc.perform(post("/business-api/huariques/"+ID+"/images").with(owner()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnsupportedMediaType()); verifyNoInteractions(images); }
    @Test void dishImageUpload201() throws Exception { var image=image(); when(images.upload(eq(ID),eq("owner"),eq(DISH),isNull(),any(),eq("image/png"))).thenReturn(image); mvc.perform(multipart("/business-api/huariques/"+ID+"/menu/dishes/"+DISH+"/images").file(new MockMultipartFile("file","image.png","image/png",new byte[]{1})).with(owner())).andExpect(status().isCreated()).andExpect(header().string("Location","/business-api/huariques/"+ID+"/menu/dishes/"+DISH+"/images/"+image.id())); }
    @Test void dependencyFailure503HidesDetails() throws Exception { when(huariques.get(ID)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret connection details")); mvc.perform(get("/business-api/huariques/"+ID).with(explorer())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("A required dependency is unavailable")); }
}
