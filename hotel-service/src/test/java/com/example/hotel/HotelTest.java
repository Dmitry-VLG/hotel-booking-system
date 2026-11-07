package com.example.hotel;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.common.ApiException;
import com.example.common.InventoryApi;
import com.example.common.JwtSupport;
import com.example.hotel.HotelDtos.HotelInput;
import com.example.hotel.HotelDtos.RoomInput;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "eureka.client.enabled=false",
            "app.demo.enabled=false",
            "app.recovery.enabled=false"
        })
@AutoConfigureMockMvc
@Import(HotelTest.ClockConfig.class)
class HotelTest {
    static final Path[] KEYS = keys();

    static Path[] keys() {
        return com.example.testing.TestKeys.create();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.public-key", () -> KEYS[1].toUri().toString());
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:hotel-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000");
    }

    static class MutableClock extends com.example.testing.AdjustableClock {}

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired CatalogService catalog;
    @Autowired InventoryService inventory;
    @Autowired RoomRepository rooms;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    Long roomId;
    Long hotelId;
    final LocalDate start = LocalDate.of(2030, 1, 10);
    final LocalDate end = LocalDate.of(2030, 1, 12);

    @BeforeEach
    void setup() {
        clock.reset();
        hotelId = catalog.createHotel(new HotelInput("Test", "Address")).id();
        roomId = catalog.createRoom(new RoomInput(hotelId, "101", true)).id();
    }

    InventoryApi.Command command(UUID booking, UUID reservation, String operation, String reason) {
        return InventoryApi.command(booking, reservation, start, end, operation, reason);
    }

    @Test
    void concurrentOverlappingHoldsHaveOneWinner() throws Exception {
        int count = 20;
        var pool = Executors.newFixedThreadPool(count);
        var startSignal = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    startSignal.await();
                                    try {
                                        inventory.hold(
                                                roomId,
                                                command(
                                                        UUID.randomUUID(),
                                                        UUID.randomUUID(),
                                                        "hold",
                                                        null));
                                        return true;
                                    } catch (ApiException conflict) {
                                        assertThat(conflict.status().value()).isEqualTo(409);
                                        return false;
                                    }
                                }));
            startSignal.countDown();
            int winners = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void repeatedCommitAndCompensationChangeCounterOnce() {
        var booking = UUID.randomUUID();
        var reservation = UUID.randomUUID();
        var hold = command(booking, reservation, "hold", null);
        inventory.hold(roomId, hold);
        inventory.hold(roomId, hold);
        var commit = command(booking, reservation, "commit", null);
        inventory.commit(roomId, commit);
        inventory.commit(roomId, commit);
        assertThat(rooms.findById(roomId).orElseThrow().getTimesBooked()).isEqualTo(1);
        var release = command(booking, reservation, "release", "COMPENSATION");
        inventory.release(roomId, release);
        inventory.release(roomId, release);
        assertThat(rooms.findById(roomId).orElseThrow().getTimesBooked()).isZero();
    }

    @Test
    void releaseBeforeHoldPreventsLateReservation() {
        var booking = UUID.randomUUID();
        var reservation = UUID.randomUUID();
        inventory.release(roomId, command(booking, reservation, "release", "COMPENSATION"));
        assertThatThrownBy(
                        () -> inventory.hold(roomId, command(booking, reservation, "hold", null)))
                .isInstanceOf(ApiException.class);
        assertThat(inventory.get(reservation).state()).isEqualTo("RELEASED");
        inventory.hold(roomId, command(UUID.randomUUID(), UUID.randomUUID(), "hold", null));
        inventory.release(roomId, command(booking, reservation, "release", "COMPENSATION"));
        assertThat(catalog.free(hotelId, start, end, true, 0, 20).content()).isEmpty();
    }

    @Test
    void committedReservationSurvivesHoldTtl() {
        var booking = UUID.randomUUID();
        var reservation = UUID.randomUUID();
        inventory.hold(roomId, command(booking, reservation, "hold", null));
        inventory.commit(roomId, command(booking, reservation, "commit", null));
        clock.advance(61);
        inventory.expire(reservation);
        assertThat(inventory.get(reservation).state()).isEqualTo("COMMITTED");
        assertThat(catalog.free(hotelId, start, end, true, 0, 20).content()).isEmpty();
    }

    @Test
    void heldReservationExpiresAndCannotBeCommitted() {
        var booking = UUID.randomUUID();
        var reservation = UUID.randomUUID();
        inventory.hold(roomId, command(booking, reservation, "hold", null));
        clock.advance(61);
        inventory.expire(reservation);
        assertThat(inventory.get(reservation).state()).isEqualTo("EXPIRED");
        assertThatThrownBy(
                        () ->
                                inventory.commit(
                                        roomId, command(booking, reservation, "commit", null)))
                .isInstanceOf(ApiException.class);
        assertThat(catalog.free(hotelId, start, end, true, 0, 20).content()).hasSize(1);
    }

    @Test
    void adjacentDatesAreAllowedAndUserCancellationKeepsHistoryCounter() {
        var booking = UUID.randomUUID();
        var reservation = UUID.randomUUID();
        inventory.hold(roomId, command(booking, reservation, "hold", null));
        inventory.commit(roomId, command(booking, reservation, "commit", null));
        var another =
                InventoryApi.command(
                        UUID.randomUUID(), UUID.randomUUID(), end, end.plusDays(2), "hold", null);
        inventory.hold(roomId, another);
        inventory.release(roomId, command(booking, reservation, "release", "USER_CANCELLATION"));
        assertThat(rooms.findById(roomId).orElseThrow().getTimesBooked()).isEqualTo(1);
    }

    @Test
    void recommendationSortsByCounterThenIdAndSkipsUnavailableRooms() {
        Long next = catalog.createRoom(new RoomInput(hotelId, "102", true)).id();
        catalog.createRoom(new RoomInput(hotelId, "103", false));
        var booking = UUID.randomUUID();
        var reservation = UUID.randomUUID();
        inventory.hold(roomId, command(booking, reservation, "hold", null));
        inventory.commit(roomId, command(booking, reservation, "commit", null));
        inventory.release(roomId, command(booking, reservation, "release", "USER_CANCELLATION"));
        var result = catalog.free(hotelId, start, end, true, 0, 20).content();
        assertThat(result).extracting(InventoryApi.Candidate::id).containsExactly(next, roomId);
    }

    @Test
    void occupancyClipsReservationToRequestedPeriod() {
        var booking = UUID.randomUUID();
        var reservation = UUID.randomUUID();
        inventory.hold(roomId, command(booking, reservation, "hold", null));
        inventory.commit(roomId, command(booking, reservation, "commit", null));
        var value = catalog.occupancy(hotelId, start.plusDays(1), end.plusDays(1)).getFirst();
        assertThat(value.occupiedNights()).isEqualTo(1);
        assertThat(value.capacityNights()).isEqualTo(2);
        assertThat(value.occupancyPercent()).isEqualTo(50.0);
    }

    @Test
    void securityRejectsAnonymousUserAdminMisuseAndExpiredJwt() throws Exception {
        mvc.perform(get("/api/hotels")).andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/hotels")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                                .contentType("application/json")
                                .content("{\"name\":\"X\",\"address\":\"Y\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/rooms/1/release")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        var now = Instant.now();
        var claims =
                JwtClaimsSet.builder()
                        .issuer("booking-user")
                        .subject("1")
                        .audience(List.of("hotel-api"))
                        .issuedAt(now.minusSeconds(7200))
                        .expiresAt(now.minusSeconds(120))
                        .claim("roles", List.of("USER"))
                        .build();
        var encoder =
                JwtSupport.encoder(
                        new FileSystemResource(KEYS[0]), new FileSystemResource(KEYS[1]));
        var token =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(SignatureAlgorithm.RS256)
                                                .keyId("local-key")
                                                .build(),
                                        claims))
                        .getTokenValue();
        mvc.perform(get("/api/hotels").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        var wrongIssuer =
                JwtClaimsSet.builder()
                        .issuer("untrusted-issuer")
                        .subject("1")
                        .audience(List.of("hotel-api"))
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(3600))
                        .claim("roles", List.of("USER"))
                        .build();
        String wrongToken =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(SignatureAlgorithm.RS256)
                                                .keyId("local-key")
                                                .build(),
                                        wrongIssuer))
                        .getTokenValue();
        mvc.perform(get("/api/hotels").header("Authorization", "Bearer " + wrongToken))
                .andExpect(status().isUnauthorized());
        var foreignKeys = keys();
        var foreignEncoder =
                JwtSupport.encoder(
                        new FileSystemResource(foreignKeys[0]),
                        new FileSystemResource(foreignKeys[1]));
        var validClaims =
                JwtClaimsSet.builder()
                        .issuer("booking-user")
                        .subject("1")
                        .audience(List.of("hotel-api"))
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(3600))
                        .claim("roles", List.of("USER"))
                        .build();
        String forged =
                foreignEncoder
                        .encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(SignatureAlgorithm.RS256)
                                                .keyId("local-key")
                                                .build(),
                                        validClaims))
                        .getTokenValue();
        mvc.perform(get("/api/hotels").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void frameworkErrorsKeepStatusSchemaAndAllowHeader() throws Exception {
        var user = jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
        var admin =
                jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_USER"),
                                new SimpleGrantedAuthority("ROLE_ADMIN"));
        mvc.perform(get("/api/hotels/no/such/path").with(user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        mvc.perform(put("/api/hotels").with(user))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/hotels").with(admin).contentType("text/plain").content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(post("/api/hotels").with(admin).contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void candidatesContinueBeyondFirstHundredAfterConcurrentOccupancy() {
        for (int i = 0; i < 104; i++)
            catalog.createRoom(new RoomInput(hotelId, "extra-" + i, true));
        var first =
                catalog.candidates(new InventoryApi.CandidateQuery(hotelId, start, end, List.of()));
        assertThat(first.content()).hasSize(100);
        var ids = first.content().stream().map(InventoryApi.Candidate::id).toList();
        for (Long id : ids)
            inventory.hold(id, command(UUID.randomUUID(), UUID.randomUUID(), "hold", null));
        var remaining =
                catalog.candidates(new InventoryApi.CandidateQuery(hotelId, start, end, ids));
        assertThat(remaining.content()).hasSize(5);
        assertThat(remaining.content())
                .extracting(InventoryApi.Candidate::id)
                .doesNotContainAnyElementsOf(ids);
    }

    @Test
    void occupancyDoesNotMixHotelsAndAddsIntervalsOnce() {
        Long otherHotel = catalog.createHotel(new HotelInput("Other", "Other address")).id();
        Long otherRoom = catalog.createRoom(new RoomInput(otherHotel, "A", true)).id();
        for (Long id : List.of(roomId, otherRoom)) {
            UUID booking = UUID.randomUUID(), reservation = UUID.randomUUID();
            inventory.hold(id, command(booking, reservation, "hold", null));
            inventory.commit(id, command(booking, reservation, "commit", null));
        }
        var selected = catalog.occupancy(hotelId, start.minusDays(1), end.plusDays(1));
        assertThat(selected).hasSize(1);
        assertThat(selected.getFirst().roomId()).isEqualTo(roomId);
        assertThat(selected.getFirst().occupiedNights()).isEqualTo(2);
        assertThat(selected.getFirst().occupancyPercent()).isEqualTo(50.0);
        assertThat(catalog.occupancy(otherHotel, start, end).getFirst().occupiedNights())
                .isEqualTo(2);
    }

    @Test
    void repeatedRecommendationsDistributeConfirmedBookingsEvenly() {
        catalog.createRoom(new RoomInput(hotelId, "102", true));
        catalog.createRoom(new RoomInput(hotelId, "103", true));
        for (int i = 0; i < 12; i++) {
            Long id = catalog.free(hotelId, start, end, true, 0, 20).content().getFirst().id();
            UUID booking = UUID.randomUUID(), reservation = UUID.randomUUID();
            inventory.hold(id, command(booking, reservation, "hold", null));
            inventory.commit(id, command(booking, reservation, "commit", null));
            inventory.release(id, command(booking, reservation, "release", "USER_CANCELLATION"));
        }
        assertThat(catalog.free(hotelId, start, end, true, 0, 20).content())
                .extracting(InventoryApi.Candidate::timesBooked)
                .containsExactly(4L, 4L, 4L);
    }
}
