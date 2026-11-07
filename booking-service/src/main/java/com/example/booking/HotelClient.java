package com.example.booking;

import com.example.booking.BookingTransactions.Snapshot;
import com.example.common.ApiError;
import com.example.common.InventoryApi;
import com.example.common.PageResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
public class HotelClient implements HotelInventory {
    private final RestClient client;
    private final TokenService tokens;
    private final Clock clock;
    private final Retry retry;
    private final CircuitBreaker circuit;

    public HotelClient(RestClient.Builder builder, TokenService tokens, Clock clock) {
        this.client = builder.baseUrl("http://hotel-service").build();
        this.tokens = tokens;
        this.clock = clock;
        this.retry =
                Retry.of(
                        "hotel",
                        RetryConfig.custom()
                                .maxAttempts(3)
                                .intervalFunction(
                                        attempt ->
                                                200L * (1L << (attempt - 1))
                                                        + ThreadLocalRandom.current().nextLong(40))
                                .retryOnException(
                                        ex -> ex instanceof InventoryFailure f && f.isTemporary())
                                .build());
        this.circuit =
                CircuitBreaker.of(
                        "hotel",
                        CircuitBreakerConfig.custom()
                                .slidingWindowSize(10)
                                .minimumNumberOfCalls(5)
                                .failureRateThreshold(50)
                                .waitDurationInOpenState(Duration.ofSeconds(5))
                                .permittedNumberOfCallsInHalfOpenState(2)
                                .recordException(
                                        ex -> ex instanceof InventoryFailure f && f.isTemporary())
                                .ignoreException(
                                        ex -> ex instanceof InventoryFailure f && !f.isTemporary())
                                .build());
    }

    private <T> T execute(Instant deadline, boolean compensation, Supplier<T> action) {
        var uncertain = new AtomicBoolean();
        Supplier<T> attempt =
                () -> {
                    if (!deadline.isAfter(clock.instant()))
                        throw new InventoryFailure(
                                504, "BOOKING_TIMEOUT", "Истёк общий дедлайн", false);
                    try {
                        return action.get();
                    } catch (RestClientResponseException ex) {
                        int status = ex.getStatusCode().value();
                        boolean temporary = status >= 500;
                        int publicStatus =
                                (status == 400 || status == 404 || status == 409)
                                        ? status
                                        : status == 504 ? 504 : 503;
                        boolean rejected = domainRejection(ex, status);
                        if (temporary) uncertain.set(true);
                        throw new InventoryFailure(
                                publicStatus,
                                "HOTEL_REJECTED",
                                "Hotel Service вернул HTTP " + status,
                                temporary,
                                rejected && !uncertain.get());
                    } catch (ResourceAccessException ex) {
                        uncertain.set(true);
                        throw new InventoryFailure(
                                504,
                                "HOTEL_TIMEOUT",
                                "Нет своевременного ответа Hotel Service",
                                true);
                    } catch (IllegalStateException ex) {
                        throw new InventoryFailure(
                                503,
                                "HOTEL_UNAVAILABLE",
                                "Hotel Service не обнаружен или недоступен",
                                true);
                    }
                };
        Supplier<T> protectedCall =
                compensation ? attempt : CircuitBreaker.decorateSupplier(circuit, attempt);
        try {
            return Retry.decorateSupplier(retry, protectedCall).get();
        } catch (CallNotPermittedException ex) {
            throw new InventoryFailure(
                    503, "HOTEL_CIRCUIT_OPEN", "Hotel Service временно недоступен", false);
        }
    }

    private boolean domainRejection(RestClientResponseException exception, int status) {
        try {
            var error = exception.getResponseBodyAs(ApiError.class);
            return error != null
                    && error.status() == status
                    && switch (status) {
                        case 404 -> "NOT_FOUND".equals(error.code());
                        case 409 -> "CONFLICT".equals(error.code());
                        case 400 -> "INVALID_REQUEST".equals(error.code());
                        default -> false;
                    };
        } catch (RuntimeException ignored) {
            // An unstructured proxy response cannot prove that hold never happened.
            return false;
        }
    }

    public List<InventoryApi.Candidate> recommend(Snapshot b, Set<Long> excluded) {
        var page =
                execute(
                        b.deadlineAt(),
                        false,
                        () ->
                                client.post()
                                        .uri("/internal/rooms/recommend")
                                        .body(
                                                new InventoryApi.CandidateQuery(
                                                        b.hotelId(),
                                                        b.startDate(),
                                                        b.endDate(),
                                                        List.copyOf(excluded)))
                                        .headers(
                                                h -> {
                                                    h.setBearerAuth(tokens.internal());
                                                    h.set("X-Trace-Id", trace());
                                                })
                                        .retrieve()
                                        .body(
                                                new ParameterizedTypeReference<
                                                        PageResult<InventoryApi.Candidate>>() {}));
        if (page == null)
            throw new InventoryFailure(503, "EMPTY_HOTEL_RESPONSE", "Пустой ответ Hotel", false);
        return page.content();
    }

    public InventoryApi.Reservation hold(Snapshot b) {
        return command(b, "hold", "confirm-availability", false);
    }

    public InventoryApi.Reservation commit(Snapshot b) {
        return command(b, "commit", "commit", false);
    }

    public InventoryApi.Reservation release(Snapshot b) {
        return command(b, "release", "release", true);
    }

    private InventoryApi.Reservation command(
            Snapshot b, String operation, String endpoint, boolean compensation) {
        var input =
                InventoryApi.command(
                        b.id(),
                        b.reservationId(),
                        b.startDate(),
                        b.endDate(),
                        operation,
                        b.releaseReason());
        var result =
                execute(
                        compensation ? Instant.MAX : b.deadlineAt(),
                        compensation,
                        () -> {
                            log.info(
                                    "event=hotel_command bookingId={} reservationId={} requestId={}",
                                    b.id(),
                                    b.reservationId(),
                                    input.requestId());
                            return client.post()
                                    .uri("/api/rooms/{id}/" + endpoint, b.roomId())
                                    .headers(
                                            h -> {
                                                h.setBearerAuth(tokens.internal());
                                                h.set("X-Trace-Id", trace());
                                            })
                                    .body(input)
                                    .retrieve()
                                    .body(InventoryApi.Reservation.class);
                        });
        if (result == null)
            throw new InventoryFailure(503, "EMPTY_HOTEL_RESPONSE", "Пустой ответ Hotel", false);
        boolean expectedState =
                switch (operation) {
                    case "hold" ->
                            "HELD".equals(result.state()) || "COMMITTED".equals(result.state());
                    case "commit" -> "COMMITTED".equals(result.state());
                    case "release" -> "RELEASED".equals(result.state());
                    default -> false;
                };
        if (!expectedState
                || !Objects.equals(result.bookingId(), b.id())
                || !Objects.equals(result.reservationId(), b.reservationId())
                || !Objects.equals(result.roomId(), b.roomId())
                || !Objects.equals(result.startDate(), b.startDate())
                || !Objects.equals(result.endDate(), b.endDate())) {
            throw new InventoryFailure(
                    503, "HOTEL_PROTOCOL_ERROR", "Несогласованный ответ Hotel", false);
        }
        return result;
    }

    public InventoryApi.Reservation lookup(Snapshot b) {
        return execute(
                b.deadlineAt(),
                false,
                () ->
                        client.get()
                                .uri("/internal/reservations/{id}", b.reservationId())
                                .headers(
                                        h -> {
                                            h.setBearerAuth(tokens.internal());
                                            h.set("X-Trace-Id", trace());
                                        })
                                .retrieve()
                                .body(InventoryApi.Reservation.class));
    }

    private String trace() {
        String value = MDC.get("traceId");
        return value == null ? UUID.randomUUID().toString() : value;
    }
}
