package com.example.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.example.booking.BookingDtos.*;
import com.example.common.InventoryApi;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        properties = {
            "eureka.client.enabled=false",
            "app.demo.enabled=false",
            "app.recovery.enabled=true"
        })
@Import(BookingTest.ClockConfig.class)
class BookingRecoveryTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.public-key", () -> BookingTest.KEYS[1].toUri().toString());
        registry.add("app.private-key", () -> BookingTest.KEYS[0].toUri().toString());
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:recovery-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000");
    }

    @Autowired BookingTransactions transactions;
    @Autowired BookingRepository bookings;
    @Autowired UserService users;
    @Autowired BookingTest.MutableClock clock;
    @MockitoBean HotelInventory inventory;

    @MockitoBean(name = "hotelRestClientBuilder")
    RestClient.Builder builder;

    @Test
    void schedulerCancelsExpiredPendingAndRetriesUntilReleaseSucceeds() {
        clock.reset();
        var unavailable = new AtomicBoolean(true);
        doAnswer(
                        call -> {
                            if (unavailable.get())
                                throw new InventoryFailure(
                                        503, "HOTEL_UNAVAILABLE", "Unavailable", true);
                            BookingTransactions.Snapshot booking = call.getArgument(0);
                            return new InventoryApi.Reservation(
                                    booking.reservationId(),
                                    booking.id(),
                                    booking.roomId(),
                                    booking.startDate(),
                                    booking.endDate(),
                                    "RELEASED",
                                    null);
                        })
                .when(inventory)
                .release(any());
        Long user =
                users.create(
                                new UserCreate(
                                        "recovery-" + UUID.randomUUID(),
                                        "Test-Password-123",
                                        "USER"))
                        .id();
        var input =
                new NewBooking(
                        false, 1L, null, LocalDate.of(2030, 1, 10), LocalDate.of(2030, 1, 12));
        UUID id =
                transactions
                        .open(
                                user,
                                UUID.randomUUID().toString(),
                                input,
                                BookingTransactions.fingerprint(input))
                        .id();
        transactions.choose(id, 1L);
        clock.advance(16);
        await().atMost(Duration.ofSeconds(12))
                .untilAsserted(
                        () -> {
                            assertThat(transactions.owned(id, user).status())
                                    .isEqualTo("CANCELLED");
                            assertThat(transactions.owned(id, user).compensationStatus())
                                    .isEqualTo("REQUIRED");
                            assertThat(bookings.findById(id).orElseThrow().getRetryCount())
                                    .isGreaterThan(0);
                        });
        unavailable.set(false);
        clock.advance(65);
        await().atMost(Duration.ofSeconds(12))
                .untilAsserted(
                        () -> {
                            assertThat(transactions.owned(id, user).compensationStatus())
                                    .isEqualTo("DONE");
                            assertThat(bookings.findById(id).orElseThrow().getNextRetryAt())
                                    .isNull();
                        });
    }
}
