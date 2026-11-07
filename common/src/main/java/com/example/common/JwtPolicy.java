package com.example.common;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;

public final class JwtPolicy {
    public static final String USER_ISSUER = "booking-user";
    public static final String USER_AUDIENCE = "hotel-api";
    public static final String INTERNAL_ISSUER = "booking-internal";
    public static final String INTERNAL_AUDIENCE = "hotel-inventory";

    private JwtPolicy() {}

    public static RSAPublicKey publicKey(Resource resource) {
        try (var input = resource.getInputStream()) {
            return RsaKeyConverters.x509().convert(input);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid public RSA key: " + resource, ex);
        }
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> check =
                jwt ->
                        jwt.getAudience() != null && jwt.getAudience().contains(audience)
                                ? OAuth2TokenValidatorResult.success()
                                : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error("invalid_token", "Wrong audience", null));
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), check);
    }

    public static JwtAuthenticationConverter userConverter() {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }
}
