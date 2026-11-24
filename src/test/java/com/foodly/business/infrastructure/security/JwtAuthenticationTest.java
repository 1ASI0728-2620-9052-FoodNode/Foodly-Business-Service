package com.foodly.business.infrastructure.security;

import com.foodly.business.application.HuariqueApplicationService;
import com.foodly.business.interfaces.rest.HuariqueController;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Date;

import static com.foodly.business.Fixtures.ID;
import static com.foodly.business.Fixtures.huarique;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = HuariqueController.class, properties = {
        "business.iam.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "business.iam.issuer=foodly-iam",
        "business.iam.audience=business",
        "business.iam.owner-role=HUARIQUE_OWNER"
})
@Import(SecurityConfiguration.class)
class JwtAuthenticationTest {
    @Autowired MockMvc mvc;
    @MockitoBean HuariqueApplicationService huariques;

    @Test
    void signedTokenWithoutAudienceReturns401() throws Exception {
        // Exercise the real decoder and Spring Security filter chain, without jwt() or a decoder mock.
        mvc.perform(get("/business-api/huariques/" + ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(null)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.status").value(401));

        verifyNoInteractions(huariques);
    }

    @Test
    void signedTokenWithValidAudienceReachesController() throws Exception {
        when(huariques.get(ID)).thenReturn(huarique());

        mvc.perform(get("/business-api/huariques/" + ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("business")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()));

        verify(huariques).get(ID);
    }

    @Test
    void configuredIamOwnerRoleCanAccessOwnerRoute() throws Exception {
        when(huariques.mine("owner")).thenReturn(huarique());
        mvc.perform(get("/business-api/huariques/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("business")))
                .andExpect(status().isOk());
        verify(huariques).mine("owner");
    }

    @Test
    void legacyOwnerRoleCannotAccessRouteConfiguredForIamOwner() throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("owner").issuer("foodly-iam").audience("business")
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("role", "OWNER").build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(new byte[32]));
        mvc.perform(get("/business-api/huariques/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.serialize()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(huariques);
    }

    private String token(String audience) throws JOSEException {
        var claims = new JWTClaimsSet.Builder()
                .subject("owner")
                .issuer("foodly-iam")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("role", "HUARIQUE_OWNER");
        if (audience != null) claims.audience(audience);

        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(new byte[32])); // test-only key matching this isolated MVC context
        return jwt.serialize();
    }
}
