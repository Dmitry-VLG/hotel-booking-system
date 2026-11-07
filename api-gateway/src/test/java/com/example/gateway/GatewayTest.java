package com.example.gateway;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"eureka.client.enabled=false", "app.public-key=classpath:unused.pem"})
@AutoConfigureWebTestClient
class GatewayTest {
    static final com.sun.net.httpserver.HttpServer backend = startBackend();
    static final java.util.concurrent.atomic.AtomicReference<String> forwardedBody =
            new java.util.concurrent.atomic.AtomicReference<>();

    static com.sun.net.httpserver.HttpServer startBackend() {
        try {
            var server =
                    com.sun.net.httpserver.HttpServer.create(
                            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/api/auth/login",
                    exchange -> {
                        forwardedBody.set(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8));
                        byte[] body =
                                "{\"accessToken\":\"routed-login\",\"tokenType\":\"Bearer\"}"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body);
                        }
                    });
            server.start();
            return server;
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @org.springframework.test.context.DynamicPropertySource
    static void backendAddress(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add(
                "spring.cloud.discovery.client.simple.instances.booking-service[0].uri",
                () -> "http://127.0.0.1:" + backend.getAddress().getPort());
    }

    @org.junit.jupiter.api.AfterAll
    static void closeBackend() {
        backend.stop(0);
    }

    @Autowired WebTestClient client;
    @MockitoBean ReactiveJwtDecoder decoder;

    @Test
    void privateRouteRequiresToken() {
        client.get()
                .uri("/api/hotels")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .exists("X-Trace-Id");
    }

    @Test
    void ordinaryUserCannotAccessAdministration() {
        client.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .get()
                .uri("/api/users")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void internalCommandsAreBlockedEvenForAdmin() {
        var admin =
                client.mutateWith(
                        mockJwt()
                                .authorities(
                                        new SimpleGrantedAuthority("ROLE_USER"),
                                        new SimpleGrantedAuthority("ROLE_ADMIN")));
        admin.post().uri("/api/rooms/1/release").exchange().expectStatus().isForbidden();
        admin.post()
                .uri("/api/rooms/1/confirm-availability")
                .exchange()
                .expectStatus()
                .isForbidden();
        admin.post().uri("/api/rooms/1/commit").exchange().expectStatus().isForbidden();
        admin.get()
                .uri("/internal/reservations/00000000-0000-0000-0000-000000000001")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void loginIsPublicAndReturnsExactResponseFromBookingBackend() {
        client.post()
                .uri("/api/auth/login")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"alice\",\"password\":\"Test-Password-123\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.accessToken")
                .isEqualTo("routed-login");
        org.assertj.core.api.Assertions.assertThat(forwardedBody.get()).contains("alice");
    }
}
