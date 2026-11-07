package com.example.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.booking.BookingDtos.*;
import com.example.booking.BookingTransactions.Snapshot;
import com.example.common.InventoryApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        properties = {
            "eureka.client.enabled=false",
            "app.demo.enabled=false",
            "app.recovery.enabled=false"
        })
@AutoConfigureMockMvc
@Import(BookingTest.ClockConfig.class)
class BookingTest {
    static final Path[] KEYS = keys();

    static Path[] keys() {
        return com.example.testing.TestKeys.create();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("app.public-key", () -> KEYS[1].toUri().toString());
        r.add("app.private-key", () -> KEYS[0].toUri().toString());
        r.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:booking-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000");
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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserService users;
    @Autowired BookingTransactions transactions;
    @Autowired BookingCoordinator coordinator;
    @Autowired BookingRepository bookings;
    @Autowired MutableClock clock;
    @MockitoBean HotelInventory hotel;

    @MockitoBean(name = "hotelRestClientBuilder")
    RestClient.Builder hotelRestClientBuilder;

    Long alice;
    Long bob;
    final LocalDate start = LocalDate.of(2030, 1, 10);
    final LocalDate end = LocalDate.of(2030, 1, 12);

    InventoryApi.Reservation result(Snapshot b, String state) {
        return new InventoryApi.Reservation(
                b.reservationId(),
                b.id(),
                b.roomId(),
                b.startDate(),
                b.endDate(),
                state,
                clock.instant().plusSeconds(60));
    }

    @BeforeEach
    void setup() {
        clock.reset();
        alice =
                users.create(
                                new UserCreate(
                                        "alice-" + UUID.randomUUID(), "Test-Password-123", "USER"))
                        .id();
        bob =
                users.create(
                                new UserCreate(
                                        "bob-" + UUID.randomUUID(), "Test-Password-123", "USER"))
                        .id();
        doAnswer(i -> result(i.getArgument(0), "HELD")).when(hotel).hold(any());
        doAnswer(i -> result(i.getArgument(0), "COMMITTED")).when(hotel).commit(any());
        doAnswer(i -> result(i.getArgument(0), "RELEASED")).when(hotel).release(any());
    }

    RequestPostProcessor auth(Long user) {
        return jwt().jwt(j -> j.subject(user.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    MvcResult create(String key, NewBooking input) throws Exception {
        return mvc.perform(
                        post("/api/bookings")
                                .with(auth(alice))
                                .header("Idempotency-Key", key)
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(input)))
                .andReturn();
    }

    NewBooking manual(Long room) {
        return new NewBooking(false, room, null, start, end);
    }

    UUID id(MvcResult result) throws Exception {
        return UUID.fromString(
                mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void createsOwnBookingAndCancelsItWithoutExposingItToAnotherUser() throws Exception {
        var result = create(UUID.randomUUID().toString(), manual(1L));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        UUID id = id(result);
        mvc.perform(get("/api/bookings/{id}", id).with(auth(bob))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/bookings/{id}", id).with(auth(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/bookings/{id}", id).with(auth(alice)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/bookings/{id}", id).with(auth(alice)))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.compensationStatus").value("DONE"));
    }

    @Test
    void sameKeyReplaysResponseAndChangedBodyConflicts() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = create(key, manual(1L));
        var second = create(key, manual(1L));
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(create(key, manual(2L)).getResponse().getStatus()).isEqualTo(409);
        assertThat(transactions.history(alice, null, 0, 20).totalElements()).isEqualTo(1);
        verify(hotel, times(1)).hold(any());
        verify(hotel, times(1)).commit(any());
    }

    @Test
    void simultaneousSameKeyReturnsPendingWithoutRunningSecondSaga() throws Exception {
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        doAnswer(
                        i -> {
                            entered.countDown();
                            if (!proceed.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("Test gate timed out");
                            return result(i.getArgument(0), "HELD");
                        })
                .when(hotel)
                .hold(any());
        String key = UUID.randomUUID().toString();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> create(key, manual(1L)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(create(key, manual(1L)).getResponse().getStatus()).isEqualTo(202);
            proceed.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(transactions.history(alice, null, 0, 20).totalElements()).isEqualTo(1);
            verify(hotel, times(1)).hold(any());
        } finally {
            proceed.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void automaticSelectionIgnoresRoomIdAndTriesNextAfterDefiniteConflict() throws Exception {
        doReturn(
                        List.of(
                                new InventoryApi.Candidate(1L, 1L, "101", 0),
                                new InventoryApi.Candidate(2L, 1L, "102", 0)))
                .when(hotel)
                .recommend(any(), any());
        doAnswer(
                        i -> {
                            Snapshot b = i.getArgument(0);
                            if (b.roomId() == 1L)
                                throw new InventoryFailure(409, "ROOM_BUSY", "Занят", false, true);
                            return result(b, "HELD");
                        })
                .when(hotel)
                .hold(any());
        var response =
                create(UUID.randomUUID().toString(), new NewBooking(true, -999L, 1L, start, end));
        assertThat(response.getResponse().getStatus()).isEqualTo(201);
        assertThat(
                        mapper.readTree(response.getResponse().getContentAsString())
                                .get("roomId")
                                .asLong())
                .isEqualTo(2L);
        verify(hotel, times(2)).hold(any());
    }

    @Test
    void timeoutCancelsAndFailedReleaseRemainsVisibleUntilRetry() throws Exception {
        doThrow(new InventoryFailure(504, "HOTEL_TIMEOUT", "Тайм-аут", true))
                .when(hotel)
                .hold(any());
        doThrow(new InventoryFailure(503, "HOTEL_DOWN", "Недоступен", true))
                .when(hotel)
                .release(any());
        var response = create(UUID.randomUUID().toString(), manual(1L));
        assertThat(response.getResponse().getStatus()).isEqualTo(504);
        UUID id =
                UUID.fromString(
                        mapper.readTree(response.getResponse().getContentAsString())
                                .get("bookingId")
                                .asText());
        assertThat(transactions.owned(id, alice).status()).isEqualTo("CANCELLED");
        assertThat(transactions.owned(id, alice).compensationStatus()).isEqualTo("REQUIRED");
        doAnswer(i -> result(i.getArgument(0), "RELEASED")).when(hotel).release(any());
        coordinator.compensate(id);
        assertThat(transactions.owned(id, alice).compensationStatus()).isEqualTo("DONE");
    }

    @Test
    void lostCommitResponseIsCompensatedWithSameReservation() throws Exception {
        doThrow(new InventoryFailure(504, "LOST_RESPONSE", "Ответ потерян", true))
                .when(hotel)
                .commit(any());
        var response = create(UUID.randomUUID().toString(), manual(1L));
        assertThat(response.getResponse().getStatus()).isEqualTo(504);
        UUID id =
                UUID.fromString(
                        mapper.readTree(response.getResponse().getContentAsString())
                                .get("bookingId")
                                .asText());
        var snapshot = transactions.snapshot(id);
        assertThat(snapshot.compensationStatus()).isEqualTo(BookingState.Compensation.DONE);
        verify(hotel)
                .release(
                        argThat(
                                b ->
                                        b.id().equals(id)
                                                && b.reservationId()
                                                        .equals(snapshot.reservationId())
                                                && "COMPENSATION".equals(b.releaseReason())));
    }

    @Test
    void deadlineCancelsAbandonedPendingOperation() {
        var input = manual(1L);
        var opened =
                transactions.open(
                        alice,
                        UUID.randomUUID().toString(),
                        input,
                        BookingTransactions.fingerprint(input));
        transactions.choose(opened.id(), 1L);
        clock.advance(16);
        transactions.timeout(opened.id());
        coordinator.compensate(opened.id());
        assertThat(transactions.owned(opened.id(), alice).status()).isEqualTo("CANCELLED");
        assertThat(transactions.owned(opened.id(), alice).compensationStatus()).isEqualTo("DONE");
    }

    @Test
    void cancellationWinsAgainstLateCommitResponse() throws Exception {
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var chosen = new AtomicReference<Snapshot>();
        doAnswer(
                        i -> {
                            Snapshot b = i.getArgument(0);
                            chosen.set(b);
                            entered.countDown();
                            if (!proceed.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("Test gate timed out");
                            return result(b, "COMMITTED");
                        })
                .when(hotel)
                .commit(any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var response = pool.submit(() -> create(UUID.randomUUID().toString(), manual(1L)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            UUID id = chosen.get().id();
            mvc.perform(delete("/api/bookings/{id}", id).with(auth(alice)))
                    .andExpect(status().isNoContent());
            proceed.countDown();
            response.get(5, TimeUnit.SECONDS);
            assertThat(transactions.owned(id, alice).status()).isEqualTo("CANCELLED");
            assertThat(transactions.owned(id, alice).compensationStatus()).isEqualTo("DONE");
        } finally {
            proceed.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void validatesDatesMissingKeyAndAdministrativeRights() throws Exception {
        assertThat(
                        create(
                                        UUID.randomUUID().toString(),
                                        new NewBooking(false, 1L, null, end, start))
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        mvc.perform(
                        post("/api/bookings")
                                .with(auth(alice))
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(manual(1L))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/users").with(auth(alice))).andExpect(status().isForbidden());
        mvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
    }

    @Test
    void registrationCannotChooseAdminRoleAndTokenLifetimeIsOneHour() throws Exception {
        var response =
                mvc.perform(
                                post("/api/auth/register")
                                        .contentType("application/json")
                                        .content(
                                                "{\"username\":\"new-"
                                                        + UUID.randomUUID()
                                                        + "\",\"password\":\"Test-Password-123\",\"role\":\"ADMIN\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String token =
                mapper.readTree(response.getResponse().getContentAsString())
                        .get("accessToken")
                        .asText();
        var claims = SignedJWT.parse(token).getJWTClaimsSet();
        assertThat(claims.getStringListClaim("roles")).containsExactly("USER");
        assertThat(
                        claims.getExpirationTime().toInstant().getEpochSecond()
                                - claims.getIssueTime().toInstant().getEpochSecond())
                .isEqualTo(3600);
    }

    @Test
    void definiteMissingRoomNeedsNoCompensation() throws Exception {
        doThrow(new InventoryFailure(404, "HOTEL_REJECTED", "Missing", false, true))
                .when(hotel)
                .hold(any());
        var response = create(UUID.randomUUID().toString(), manual(999999999L));
        assertThat(response.getResponse().getStatus()).isEqualTo(404);
        UUID id =
                UUID.fromString(
                        mapper.readTree(response.getResponse().getContentAsString())
                                .get("bookingId")
                                .asText());
        var state = transactions.owned(id, alice);
        assertThat(state.status()).isEqualTo("CANCELLED");
        assertThat(state.compensationStatus()).isEqualTo("NONE");
        coordinator.compensate(id);
        verify(hotel, never()).release(any());
        assertThat(bookings.findById(id).orElseThrow().getNextRetryAt()).isNull();
        assertThat(
                        bookings.compensationDue(
                                clock.instant(),
                                org.springframework.data.domain.PageRequest.of(0, 100)))
                .doesNotContain(id);
    }

    @Test
    void unverified404AfterUnknownHoldDoesNotDiscardCompensation() throws Exception {
        doThrow(new InventoryFailure(404, "HOTEL_REJECTED", "Unverified response", false))
                .when(hotel)
                .hold(any());
        doThrow(new InventoryFailure(404, "HOTEL_REJECTED", "Missing release route", false))
                .when(hotel)
                .release(any());
        var response = create(UUID.randomUUID().toString(), manual(1L));
        UUID id =
                UUID.fromString(
                        mapper.readTree(response.getResponse().getContentAsString())
                                .get("bookingId")
                                .asText());
        assertThat(transactions.owned(id, alice).compensationStatus()).isEqualTo("REQUIRED");
        verify(hotel).release(any());
    }

    @Test
    void automaticSelectionRefreshesAfterHundredDefiniteConflicts() throws Exception {
        var first =
                java.util.stream.LongStream.rangeClosed(1, 100)
                        .mapToObj(id -> new InventoryApi.Candidate(id, 1L, "R" + id, 0))
                        .toList();
        doAnswer(
                        call -> {
                            java.util.Set<Long> excluded = call.getArgument(1);
                            return excluded.isEmpty()
                                    ? first
                                    : List.of(new InventoryApi.Candidate(101L, 1L, "101", 0));
                        })
                .when(hotel)
                .recommend(any(), any());
        doAnswer(
                        call -> {
                            Snapshot attempt = call.getArgument(0);
                            if (attempt.roomId() <= 100)
                                throw new InventoryFailure(
                                        409, "HOTEL_REJECTED", "Occupied", false, true);
                            return result(attempt, "HELD");
                        })
                .when(hotel)
                .hold(any());
        var response =
                create(UUID.randomUUID().toString(), new NewBooking(true, null, 1L, start, end));
        assertThat(response.getResponse().getStatus()).isEqualTo(201);
        assertThat(
                        mapper.readTree(response.getResponse().getContentAsString())
                                .get("roomId")
                                .asLong())
                .isEqualTo(101L);
        verify(hotel, times(2)).recommend(any(), any());
        verify(hotel, times(101)).hold(any());
    }

    @Test
    void definiteRejectionAfterConcurrentCancellationClearsRetry() {
        var input = manual(1L);
        UUID id =
                transactions
                        .open(
                                alice,
                                UUID.randomUUID().toString(),
                                input,
                                BookingTransactions.fingerprint(input))
                        .id();
        var attempt = transactions.choose(id, 1L);
        transactions.cancelOwned(id, alice);
        transactions.holdRejected(id, attempt.reservationId());
        assertThat(transactions.owned(id, alice).status()).isEqualTo("CANCELLED");
        assertThat(transactions.owned(id, alice).sagaPhase()).isEqualTo("CANCELLED");
        assertThat(transactions.owned(id, alice).compensationStatus()).isEqualTo("DONE");
        assertThat(bookings.findById(id).orElseThrow().getNextRetryAt()).isNull();
    }
}
