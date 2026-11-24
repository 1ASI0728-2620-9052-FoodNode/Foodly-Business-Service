package com.foodly.business.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodly.business.interfaces.rest.ApiError;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

@Configuration
public class SecurityConfiguration {
    @Bean JwtDecoder jwtDecoder(@Value("${business.iam.secret-base64}") String secret,
            @Value("${business.iam.issuer}") String issuer, @Value("${business.iam.audience}") String audience) {
        byte[] key = Base64.getDecoder().decode(secret);
        if (key.length < 32 || issuer.isBlank() || audience.isBlank()) throw new IllegalArgumentException("IAM requires a >=256 bit Base64 key, issuer and audience");
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> contract = jwt -> jwt.getExpiresAt() != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()
                && jwt.getSubject().length() <= 128 && jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing required IAM claims", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), contract));
        return decoder;
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json,
            @Value("${business.iam.owner-role:OWNER}") String ownerRole) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var roles = new ArrayList<String>();
            Object role = jwt.getClaims().get("role"); if (role instanceof String s) roles.add(s);
            Object values = jwt.getClaims().get("roles");
            if (values instanceof Collection<?> collection) collection.stream().filter(String.class::isInstance).map(String.class::cast).forEach(roles::add);
            return roles.stream().distinct().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).map(a -> (org.springframework.security.core.GrantedAuthority)a).toList();
        });
        http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/business-api/huariques/me").hasRole(ownerRole)
                .requestMatchers(HttpMethod.GET, "/business-api/**").authenticated()
                .requestMatchers("/business-api/**").hasRole(ownerRole).anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((r,s,e) -> error(json,r,s,401,"Unauthorized","Valid IAM bearer token required"))
                .accessDeniedHandler((r,s,e) -> error(json,r,s,403,"Forbidden","Owner role required")))
            .exceptionHandling(e -> e.authenticationEntryPoint((r,s,x) -> error(json,r,s,401,"Unauthorized","Valid IAM bearer token required"))
                .accessDeniedHandler((r,s,x) -> error(json,r,s,403,"Forbidden","Insufficient permissions")));
        return http.build();
    }
    private static void error(ObjectMapper json, HttpServletRequest request, HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (status == 401) response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        json.writeValue(response.getOutputStream(), new ApiError(Instant.now(), status, error, message, request.getRequestURI(), Map.of()));
    }
}
