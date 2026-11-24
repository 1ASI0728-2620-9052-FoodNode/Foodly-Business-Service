package com.foodly.business.infrastructure.security;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class JwtContractTest {
    final byte[] key = new byte[32]; // test-only ephemeral key; never used by runtime configuration
    String token(byte[] signingKey, String issuer, String audience, Instant expiry) throws Exception {
        var claims=new JWTClaimsSet.Builder().subject("owner").issuer(issuer).issueTime(Date.from(Instant.now())).claim("role","OWNER");
        if (audience != null) claims.audience(audience);
        if (expiry != null) claims.expirationTime(Date.from(expiry));
        var token=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims.build()); token.sign(new MACSigner(signingKey)); return token.serialize();
    }
    org.springframework.security.oauth2.jwt.JwtDecoder decoder() { return new SecurityConfiguration().jwtDecoder(Base64.getEncoder().encodeToString(key),"foodly-iam","business"); }
    @Test void acceptsValidIamToken() throws Exception { assertThat(decoder().decode(token(key,"foodly-iam","business",Instant.now().plusSeconds(300))).getSubject()).isEqualTo("owner"); }
    @Test void rejectsExpired() throws Exception { String t=token(key,"foodly-iam","business",Instant.now().minusSeconds(120)); assertThatThrownBy(()->decoder().decode(t)).isInstanceOf(JwtException.class); }
    @Test void rejectsWrongSignature() throws Exception { var other=new byte[32]; Arrays.fill(other,(byte)3); String t=token(other,"foodly-iam","business",Instant.now().plusSeconds(300)); assertThatThrownBy(()->decoder().decode(t)).isInstanceOf(JwtException.class); }
    @Test void rejectsWrongIssuer() throws Exception { String t=token(key,"wrong","business",Instant.now().plusSeconds(300)); assertThatThrownBy(()->decoder().decode(t)).isInstanceOf(JwtException.class); }
    @Test void rejectsWrongAudience() throws Exception { String t=token(key,"foodly-iam","other",Instant.now().plusSeconds(300)); assertThatThrownBy(()->decoder().decode(t)).isInstanceOf(JwtException.class); }
    @Test void rejectsMissingAudience() throws Exception { String t=token(key,"foodly-iam",null,Instant.now().plusSeconds(300)); assertThatThrownBy(()->decoder().decode(t)).isInstanceOf(JwtException.class); }
    @Test void requiresExpiry() throws Exception { String t=token(key,"foodly-iam","business",null); assertThatThrownBy(()->decoder().decode(t)).isInstanceOf(JwtException.class); }
    @Test void rejectsUnsignedToken() {
        String t=new PlainJWT(new JWTClaimsSet.Builder().subject("owner").issuer("foodly-iam").audience("business")
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build()).serialize();
        assertThatThrownBy(()->decoder().decode(t)).isInstanceOf(JwtException.class);
    }
    @Test void rejectsDifferentAlgorithmEvenWithSameKey() throws Exception {
        var longerKey=new byte[48];
        var decoder=new SecurityConfiguration().jwtDecoder(Base64.getEncoder().encodeToString(longerKey),"foodly-iam","business");
        var claims=new JWTClaimsSet.Builder().subject("owner").issuer("foodly-iam").audience("business")
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.HS384),claims); jwt.sign(new MACSigner(longerKey));
        assertThatThrownBy(()->decoder.decode(jwt.serialize())).isInstanceOf(JwtException.class);
    }
    @Test void rejectsCurrentIamClaimShapeWithoutIssuerAndAudience() throws Exception {
        // Mirrors the claims in IAM develop 850c6d1; this is a fixture, not a token from a running IAM service.
        var claims=new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).claim("role","HUARIQUE_OWNER")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(3600))).build();
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims); jwt.sign(new MACSigner(key));
        assertThatThrownBy(()->decoder().decode(jwt.serialize())).isInstanceOf(JwtException.class);
    }
}
