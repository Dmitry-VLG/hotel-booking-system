package com.example.gateway;

import com.example.common.ApiError;
import com.example.common.JwtPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurity {
    @Bean
    ReactiveJwtDecoder gatewayDecoder(@Value("${app.public-key}") Resource resource)
            throws IOException {
        var decoder = NimbusReactiveJwtDecoder.withPublicKey(JwtPolicy.publicKey(resource)).build();
        decoder.setJwtValidator(
                JwtPolicy.validator(JwtPolicy.USER_ISSUER, JwtPolicy.USER_AUDIENCE));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain security(
            ServerHttpSecurity http, ReactiveJwtDecoder decoder, ObjectMapper mapper) {
        var converter = JwtPolicy.userConverter();
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(
                        auth ->
                                auth.pathMatchers(
                                                "/actuator/health",
                                                "/api/auth/register",
                                                "/api/auth/login")
                                        .permitAll()
                                        .pathMatchers(
                                                "/internal/**",
                                                "/api/rooms/*/confirm-availability",
                                                "/api/rooms/*/commit",
                                                "/api/rooms/*/release")
                                        .denyAll()
                                        .pathMatchers("/api/users/**", "/api/admin/**")
                                        .hasRole("ADMIN")
                                        .pathMatchers("/api/**")
                                        .hasRole("USER")
                                        .anyExchange()
                                        .denyAll())
                .oauth2ResourceServer(
                        oauth ->
                                oauth.jwt(
                                                jwt ->
                                                        jwt.jwtDecoder(decoder)
                                                                .jwtAuthenticationConverter(
                                                                        new ReactiveJwtAuthenticationConverterAdapter(
                                                                                converter)))
                                        .authenticationEntryPoint(
                                                (exchange, ex) -> error(exchange, mapper, 401)))
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (exchange, ex) -> error(exchange, mapper, 401))
                                        .accessDeniedHandler(
                                                (exchange, ex) -> error(exchange, mapper, 403)))
                .build();
    }

    public static Mono<Void> error(ServerWebExchange exchange, ObjectMapper mapper, int status) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var body =
                new ApiError(
                        Instant.now(),
                        status,
                        status == 401
                                ? "UNAUTHORIZED"
                                : status == 403 ? "FORBIDDEN" : "GATEWAY_ERROR",
                        status == 401
                                ? "Нужен действительный токен"
                                : status == 403 ? "Недостаточно прав" : "Ошибка маршрутизации",
                        exchange.getRequest().getPath().value(),
                        exchange.getAttribute("traceId"),
                        null,
                        Map.of());
        try {
            return response.writeWith(
                    Mono.just(response.bufferFactory().wrap(mapper.writeValueAsBytes(body))));
        } catch (Exception ex) {
            return Mono.error(ex);
        }
    }
}
