package com.example.booking;

import com.example.common.JwtSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class BookingSecurity {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(
            @Value("${app.private-key}") Resource privateKey,
            @Value("${app.public-key}") Resource publicKey) {
        return JwtSupport.encoder(privateKey, publicKey);
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${app.public-key}") Resource key) {
        return JwtSupport.decoder(key, false);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder, ObjectMapper mapper)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/api/auth/register",
                                                "/api/auth/login",
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
