package com.example.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

public final class JwtSupport {
    public static final String USER_ISSUER = JwtPolicy.USER_ISSUER;
    public static final String USER_AUDIENCE = JwtPolicy.USER_AUDIENCE;
    public static final String INTERNAL_ISSUER = JwtPolicy.INTERNAL_ISSUER;
    public static final String INTERNAL_AUDIENCE = JwtPolicy.INTERNAL_AUDIENCE;

    private JwtSupport() {}

    private static byte[] read(Resource resource) {
        try {
            String text =
                    resource.getContentAsString(StandardCharsets.US_ASCII)
                            .replaceAll("-----BEGIN [A-Z ]+-----", "")
                            .replaceAll("-----END [A-Z ]+-----", "")
                            .replaceAll("\\s", "");
            return Base64.getDecoder().decode(text);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot read PEM key: " + resource, ex);
        }
    }

    public static RSAPublicKey publicKey(Resource resource) {
        return JwtPolicy.publicKey(resource);
    }

    public static JwtEncoder encoder(Resource privateFile, Resource publicFile) {
        try {
            var privateKey =
                    (RSAPrivateKey)
                            KeyFactory.getInstance("RSA")
                                    .generatePrivate(new PKCS8EncodedKeySpec(read(privateFile)));
            var key =
                    new RSAKey.Builder(publicKey(publicFile))
                            .privateKey(privateKey)
                            .keyID("local-key")
                            .build();
            return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid signing keys", ex);
        }
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return JwtPolicy.validator(issuer, audience);
    }

    public static JwtDecoder decoder(Resource publicFile, boolean internal) {
        var decoder = NimbusJwtDecoder.withPublicKey(publicKey(publicFile)).build();
        decoder.setJwtValidator(
                validator(
                        internal ? INTERNAL_ISSUER : USER_ISSUER,
                        internal ? INTERNAL_AUDIENCE : USER_AUDIENCE));
        return decoder;
    }

    public static JwtAuthenticationConverter userConverter() {
        return JwtPolicy.userConverter();
    }

    public static void securityError(
            ObjectMapper mapper,
            HttpServletRequest request,
            HttpServletResponse response,
            int status)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(
                response.getOutputStream(),
                ApiError.of(
                        status,
                        status == 401 ? "UNAUTHORIZED" : "FORBIDDEN",
                        status == 401 ? "Нужен действительный токен" : "Недостаточно прав",
                        request.getRequestURI()));
    }
}
