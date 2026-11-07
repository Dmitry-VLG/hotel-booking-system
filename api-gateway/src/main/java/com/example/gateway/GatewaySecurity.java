package com.example.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurity {
    @Bean
    ReactiveJwtDecoder gatewayDecoder(@Value("${app.public-key}") Resource resource) throws IOException {
        RSAPublicKey key;
        try (var input = resource.getInputStream()) { key = RsaKeyConverters.x509().convert(input); }
        var decoder = NimbusReactiveJwtDecoder.withPublicKey(key).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains("hotel-api")
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("booking-user"), audience));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http, ReactiveJwtDecoder decoder, ObjectMapper mapper) {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers("/actuator/health", "/api/auth/register", "/api/auth/login").permitAll()
                        .pathMatchers("/internal/**", "/api/rooms/*/confirm-availability",
                                "/api/rooms/*/commit", "/api/rooms/*/release").denyAll()
                        .pathMatchers("/api/users/**", "/api/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/**").hasRole("USER")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtDecoder(decoder)
                        .jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(converter)))
                        .authenticationEntryPoint((exchange, ex) -> error(exchange, mapper, 401)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, ex) -> error(exchange, mapper, 401))
                        .accessDeniedHandler((exchange, ex) -> error(exchange, mapper, 403)))
                .build();
    }

    public static Mono<Void> error(ServerWebExchange exchange, ObjectMapper mapper, int status) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status);
        body.put("code", status == 401 ? "UNAUTHORIZED" : status == 403 ? "FORBIDDEN" : "GATEWAY_ERROR");
        body.put("message", status == 401 ? "Нужен действительный токен" : status == 403 ? "Недостаточно прав" : "Ошибка маршрутизации");
        body.put("path", exchange.getRequest().getPath().value());
        body.put("traceId", exchange.getAttribute("traceId"));
        body.put("bookingId", null);
        body.put("fieldErrors", Map.of());
        try { return response.writeWith(Mono.just(response.bufferFactory().wrap(mapper.writeValueAsBytes(body)))); }
        catch (Exception ex) { return Mono.error(ex); }
    }
}
