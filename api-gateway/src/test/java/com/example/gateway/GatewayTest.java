package com.example.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK,
        properties={"eureka.client.enabled=false", "app.public-key=classpath:unused.pem"})
@AutoConfigureWebTestClient
class GatewayTest {
    @Autowired WebTestClient client;
    @MockitoBean ReactiveJwtDecoder decoder;
    @Test
    void privateRouteRequiresToken() {
        client.get().uri("/api/hotels").exchange().expectStatus().isUnauthorized().expectHeader().exists("X-Trace-Id");
    }
    @Test
    void ordinaryUserCannotAccessAdministration() {
        client.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .get().uri("/api/users").exchange().expectStatus().isForbidden();
    }
    @Test
    void internalCommandsAreBlockedEvenForAdmin() {
        var admin=client.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_USER"),new SimpleGrantedAuthority("ROLE_ADMIN")));
        admin.post().uri("/api/rooms/1/release").exchange().expectStatus().isForbidden();
        admin.post().uri("/api/rooms/1/confirm-availability").exchange().expectStatus().isForbidden();
        admin.post().uri("/api/rooms/1/commit").exchange().expectStatus().isForbidden();
        admin.get().uri("/internal/reservations/00000000-0000-0000-0000-000000000001").exchange().expectStatus().isForbidden();
    }
    @Test
    void loginIsPublicAndReachesRoutingInsteadOfReturning401() {
        client.post().uri("/api/auth/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"alice\",\"password\":\"Test-Password-123\"}")
                .exchange().expectStatus().is5xxServerError();
    }
}
