package com.example.hotel;

import com.example.common.JwtSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class HotelSecurity {
    @Bean("hotelUserDecoder")
    JwtDecoder userDecoder(@Value("${app.public-key}") Resource key) {
        return JwtSupport.decoder(key, false);
    }

    @Bean("hotelInternalDecoder")
    JwtDecoder internalDecoder(@Value("${app.public-key}") Resource key) {
        return JwtSupport.decoder(key, true);
    }

    @Bean
    @Order(1)
    SecurityFilterChain internal(
            HttpSecurity http,
            @Qualifier("hotelInternalDecoder") JwtDecoder decoder,
            ObjectMapper mapper)
            throws Exception {
        return http.securityMatcher(
                        "/internal/**",
                        "/api/rooms/*/confirm-availability",
                        "/api/rooms/*/commit",
                        "/api/rooms/*/release")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("SCOPE_inventory.write"))
                .oauth2ResourceServer(
                        o ->
                                o.jwt(j -> j.decoder(decoder))
                                        .authenticationEntryPoint(
                                                (q, r, e) ->
                                                        JwtSupport.securityError(
                                                                mapper, q, r, 401)))
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (q, r, x) ->
                                                        JwtSupport.securityError(mapper, q, r, 401))
                                        .accessDeniedHandler(
                                                (q, r, x) ->
                                                        JwtSupport.securityError(
                                                                mapper, q, r, 403)))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain users(
            HttpSecurity http,
            @Qualifier("hotelUserDecoder") JwtDecoder decoder,
            ObjectMapper mapper)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/actuator/health",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html",
                                                "/v3/api-docs/**")
                                        .permitAll()
                                        .anyRequest()
                                        .hasRole("USER"))
                .oauth2ResourceServer(
                        o ->
                                o.jwt(
                                                j ->
                                                        j.decoder(decoder)
                                                                .jwtAuthenticationConverter(
                                                                        JwtSupport.userConverter()))
                                        .authenticationEntryPoint(
                                                (q, r, e) ->
                                                        JwtSupport.securityError(
                                                                mapper, q, r, 401)))
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (q, r, x) ->
                                                        JwtSupport.securityError(mapper, q, r, 401))
                                        .accessDeniedHandler(
                                                (q, r, x) ->
                                                        JwtSupport.securityError(
                                                                mapper, q, r, 403)))
                .build();
    }
}
