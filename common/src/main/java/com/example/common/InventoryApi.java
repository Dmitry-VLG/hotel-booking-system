package com.example.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class InventoryApi {
    private InventoryApi() {}
    public record Command(@NotNull UUID bookingId, @NotNull UUID reservationId,
                          @NotNull LocalDate startDate, @NotNull LocalDate endDate,
                          @NotBlank String requestId, String reason) {}
    public record Reservation(UUID reservationId, UUID bookingId, Long roomId,
                              LocalDate startDate, LocalDate endDate, String state,
                              Instant expiresAt) {}
    public record Candidate(Long id, Long hotelId, String number, long timesBooked) {}

    public static Command command(UUID bookingId, UUID reservationId, LocalDate start,
                                  LocalDate end, String operation, String reason) {
        return new Command(bookingId, reservationId, start, end,
                reservationId + ":" + operation, reason);
    }
}
