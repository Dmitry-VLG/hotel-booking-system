package com.example.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class InventoryApi {
    public static final int MAX_CANDIDATE_EXCLUSIONS = 10000;

    private InventoryApi() {}

    public record Command(
            @NotNull UUID bookingId,
            @NotNull UUID reservationId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotBlank String requestId,
            String reason) {}

    public record Reservation(
            UUID reservationId,
            UUID bookingId,
            Long roomId,
            LocalDate startDate,
            LocalDate endDate,
            String state,
            Instant expiresAt) {}

    public record CandidateQuery(
            @NotNull @Positive Long hotelId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotNull @Size(max = MAX_CANDIDATE_EXCLUSIONS)
                    List<@NotNull @Positive Long> excludedIds) {}

    public record Candidate(Long id, Long hotelId, String number, long timesBooked) {}

    public static Command command(
            UUID bookingId,
            UUID reservationId,
            LocalDate start,
            LocalDate end,
            String operation,
            String reason) {
        return new Command(
                bookingId, reservationId, start, end, reservationId + ":" + operation, reason);
    }
}
