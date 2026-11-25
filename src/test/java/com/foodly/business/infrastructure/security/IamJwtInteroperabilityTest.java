package com.foodly.business.infrastructure.security;

import com.foodly.business.application.HuariqueApplicationService;
import com.foodly.business.application.port.EventOutbox;
import com.foodly.business.domain.model.Huarique;
import com.foodly.business.domain.repository.IHuariqueRepository;
import com.foodly.business.domain.service.BusinessDomainService;
import com.foodly.business.interfaces.rest.HuariqueController;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import static com.foodly.business.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * JJWT 0.13.0 producer fixtures -> real Nimbus decoder/security chain/application owner policy.
 * Tokens reproduce what Foodly-IAM-Service emits after feature/jwt-issuer-audience (PR #3): HS256, iss foodly-iam,
 * aud with every Foodly audience, sub = user UUID, role, iat and a 60 minute exp. IAM is evidence, not a deployed producer.
 * Issuer, audience and owner role are NOT set here: they come from the application.properties defaults, so this
 * test also proves those defaults match IAM. Repositories/outbox are mocks: no remote IAM login or real persistence.
 */
@WebMvcTest(controllers=HuariqueController.class)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import({SecurityConfiguration.class, HuariqueApplicationService.class, IamJwtInteroperabilityTest.Policy.class})
class IamJwtInteroperabilityTest {
    private static final String ISSUER="foodly-iam", AUDIENCE="foodly-business";
    /** Default foodly.jwt.audiences of Foodly-IAM-Service. */
    private static final List<String> IAM_AUDIENCES=List.of("foodly-iam","foodly-business","foodly-radar",
            "foodly-messaging","foodly-reservation","foodly-community");
    private static final String OWNER="a519c2ae-9ac5-40ac-9149-470bf3bb9e96";
    private static final String OTHER="f9347aa3-9c75-46d1-ae62-ed76f0f14933";
    private static final byte[] KEY=randomKey(64); // ephemeral; also permits a same-key HS384 rejection test
    @Autowired MockMvc mvc;
    @MockitoBean IHuariqueRepository repository;
    @MockitoBean EventOutbox outbox;

    @TestConfiguration(proxyBeanMethods=false)
    static class Policy {
        @Bean BusinessDomainService businessDomainService(IHuariqueRepository repository) {
            return new BusinessDomainService(repository);
        }
    }
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry properties) {
        properties.add("business.iam.secret-base64",()->Base64.getEncoder().encodeToString(KEY));
    }
    @BeforeEach void fixtures() {
        when(repository.findById(ID)).thenReturn(Optional.of(ownedHuarique()));
        when(repository.save(any())).thenAnswer(invocation->invocation.getArgument(0));
    }

    @Test void ownerTokenIdenticalToIamRegistersHuariqueWith201() throws Exception {
        String jwt=iamToken("HUARIQUE_OWNER",OWNER);
        mvc.perform(post("/business-api/huariques").header(HttpHeaders.AUTHORIZATION,"Bearer "+jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ownerId").value(OWNER));
        verify(repository).save(argThat(h->h.ownerId().equals(OWNER)));
    }
    @Test void explorerTokenIdenticalToIamCannotRegisterHuariqueAndGets403() throws Exception {
        String jwt=iamToken("GASTRONOMIC_EXPLORER",OTHER);
        mvc.perform(post("/business-api/huariques").header(HttpHeaders.AUTHORIZATION,"Bearer "+jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        verify(repository,never()).save(any()); verifyNoInteractions(outbox);
    }
    @Test void proposedIamOwnerTokenReachesOwnerRoute() throws Exception {
        when(repository.findByOwnerId(OWNER)).thenReturn(Optional.of(ownedHuarique()));
        mvc.perform(get("/business-api/huariques/me").header(HttpHeaders.AUTHORIZATION,bearer("HUARIQUE_OWNER",OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ownerId").value(OWNER));
        verify(repository).findByOwnerId(OWNER);
    }
    @Test void verifiedSubjectBecomesOwnerOnRegistration() throws Exception {
        mvc.perform(post("/business-api/huariques").header(HttpHeaders.AUTHORIZATION,bearer("HUARIQUE_OWNER",OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ownerId").value(OWNER));
        verify(repository).save(argThat(h->h.ownerId().equals(OWNER)));
    }
    @Test void ownerCanChangeOwnResourceWithSignedIamRole() throws Exception {
        mvc.perform(patch("/business-api/huariques/"+ID+"/open-status").header(HttpHeaders.AUTHORIZATION,bearer("HUARIQUE_OWNER",OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openStatus\":\"OPEN\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.openStatus").value("OPEN"));
        verify(repository).save(any());
    }
    @Test void sameRoleDoesNotGrantOwnershipOfAnotherUsersResource() throws Exception {
        mvc.perform(patch("/business-api/huariques/"+ID+"/open-status").header(HttpHeaders.AUTHORIZATION,bearer("HUARIQUE_OWNER",OTHER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openStatus\":\"OPEN\",\"version\":0}"))
                .andExpect(status().isForbidden());
        verify(repository,never()).save(any()); verifyNoInteractions(outbox);
    }
    @Test void iamExplorerCanReadButCannotUseOwnerRoute() throws Exception {
        String bearer=bearer("GASTRONOMIC_EXPLORER",OTHER);
        mvc.perform(get("/business-api/huariques/"+ID).header(HttpHeaders.AUTHORIZATION,bearer)).andExpect(status().isOk());
        clearInvocations(repository);
        mvc.perform(get("/business-api/huariques/me").header(HttpHeaders.AUTHORIZATION,bearer)).andExpect(status().isForbidden());
        verifyNoInteractions(repository,outbox);
    }
    @ParameterizedTest @ValueSource(strings={"OWNER","ROLE_HUARIQUE_OWNER","huarique_owner","UNKNOWN"})
    void roleMustMatchConfiguredIamValueExactly(String role) throws Exception {
        mvc.perform(get("/business-api/huariques/me").header(HttpHeaders.AUTHORIZATION,bearer(role,OWNER)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repository,outbox);
    }
    @ParameterizedTest @EnumSource(Invalid.class)
    void invalidSignedClaimsOrCryptographyReturn401BeforeApplication(Invalid invalid) throws Exception {
        mvc.perform(get("/business-api/huariques/"+ID).header(HttpHeaders.AUTHORIZATION,"Bearer "+token("HUARIQUE_OWNER",OWNER,invalid)))
                .andExpect(status().isUnauthorized()).andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,"Bearer"))
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(repository,outbox);
    }
    @ParameterizedTest @ValueSource(ints={32,48,64})
    void explicitIamHs256AndDecodedBase64KeyInteroperateAtSupportedKeyLengths(int length) {
        byte[] key=randomKey(length);
        String jwt=Jwts.builder().subject(OWNER).issuer(ISSUER).audience().add(AUDIENCE).and()
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(key),Jwts.SIG.HS256).compact();
        var decoder=new SecurityConfiguration().jwtDecoder(Base64.getEncoder().encodeToString(key),ISSUER,AUDIENCE);
        assertThat(decoder.decode(jwt).getSubject()).isEqualTo(OWNER);
    }
    @Test void currentConsumerDoesNotRequireIat() throws Exception {
        String jwt=Jwts.builder().subject(OWNER).issuer(ISSUER).audience().add(AUDIENCE).and()
                .expiration(Date.from(Instant.now().plusSeconds(300))).claim("role","HUARIQUE_OWNER")
                .signWith(Keys.hmacShaKeyFor(KEY),Jwts.SIG.HS256).compact();
        mvc.perform(get("/business-api/huariques/"+ID).header(HttpHeaders.AUTHORIZATION,"Bearer "+jwt)).andExpect(status().isOk());
    }
    @Test void currentConsumerDoesNotEnforceIamOneHourMaximumLifetime() throws Exception {
        String jwt=Jwts.builder().subject(OWNER).issuer(ISSUER).audience().add(AUDIENCE).and()
                .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(7200))).claim("role","HUARIQUE_OWNER")
                .signWith(Keys.hmacShaKeyFor(KEY),Jwts.SIG.HS256).compact();
        mvc.perform(get("/business-api/huariques/"+ID).header(HttpHeaders.AUTHORIZATION,"Bearer "+jwt)).andExpect(status().isOk());
    }
    @Test void currentConsumerAllowsAuthenticatedReadWithoutRoleButDeniesOwnerRoute() throws Exception {
        String bearer=bearer(null,OWNER);
        mvc.perform(get("/business-api/huariques/"+ID).header(HttpHeaders.AUTHORIZATION,bearer)).andExpect(status().isOk());
        mvc.perform(get("/business-api/huariques/me").header(HttpHeaders.AUTHORIZATION,bearer)).andExpect(status().isForbidden());
    }

    enum Invalid { CURRENT_IAM, MISSING_ISSUER, MISSING_AUDIENCE, WRONG_ISSUER, WRONG_AUDIENCE, EXPIRED,
        MISSING_EXPIRY, MISSING_SUBJECT, BLANK_SUBJECT, OVERSIZED_SUBJECT, FUTURE_NBF, WRONG_KEY,
        BASE64_TEXT_USED_AS_KEY, HS384, UNSIGNED }

    private static String bearer(String role,String subject) { return "Bearer "+token(role,subject,null); }
    private static String token(String role,String subject,Invalid invalid) {
        Instant now=Instant.now();
        var builder=Jwts.builder().subject(subject).claim("role",role).issuedAt(Date.from(now));
        if (invalid!=Invalid.CURRENT_IAM && invalid!=Invalid.MISSING_ISSUER)
            builder.issuer(invalid==Invalid.WRONG_ISSUER?"another-issuer":ISSUER);
        if (invalid!=Invalid.CURRENT_IAM && invalid!=Invalid.MISSING_AUDIENCE)
            builder.audience().add(invalid==Invalid.WRONG_AUDIENCE?List.of("foodly-iam","another-service"):IAM_AUDIENCES).and();
        if (invalid!=Invalid.MISSING_EXPIRY)
            builder.expiration(Date.from(invalid==Invalid.EXPIRED?now.minusSeconds(120):now.plusSeconds(3600)));
        if (invalid==Invalid.MISSING_SUBJECT) builder.subject(null);
        if (invalid==Invalid.BLANK_SUBJECT) builder.subject(" ");
        if (invalid==Invalid.OVERSIZED_SUBJECT) builder.subject("x".repeat(129));
        if (invalid==Invalid.FUTURE_NBF) builder.notBefore(Date.from(now.plusSeconds(120)));
        if (invalid==Invalid.UNSIGNED) return builder.compact();
        byte[] key=invalid==Invalid.WRONG_KEY?randomKey(64):KEY;
        if (invalid==Invalid.BASE64_TEXT_USED_AS_KEY) key=Base64.getEncoder().encodeToString(KEY).getBytes(StandardCharsets.UTF_8);
        return builder.signWith(Keys.hmacShaKeyFor(key),invalid==Invalid.HS384?Jwts.SIG.HS384:Jwts.SIG.HS256).compact();
    }
    /** Mirrors Foodly-IAM-Service JwtTokenIssuer.issue: iss, aud, sub, role, iat and exp = iat + 60 min, HS256. */
    private static String iamToken(String role,String subject) {
        Instant now=Instant.now();
        return Jwts.builder().issuer(ISSUER).audience().add(IAM_AUDIENCES).and().subject(subject).claim("role",role)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(60*60)))
                .signWith(Keys.hmacShaKeyFor(KEY),Jwts.SIG.HS256).compact();
    }
    private static byte[] randomKey(int length) { byte[] value=new byte[length]; new SecureRandom().nextBytes(value); return value; }
    private static Huarique ownedHuarique() {
        var h=huarique();
        return new Huarique(h.id(),OWNER,h.name(),h.address(),h.cuisineType(),h.priceRange(),h.location(),h.openStatus(),
                h.tags(),h.openingHours(),h.images(),h.version());
    }
}
