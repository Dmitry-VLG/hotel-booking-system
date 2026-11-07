package com.example.booking;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class BookingDtos {
    private BookingDtos() {}

    public record Credentials(
            @NotBlank @Size(max = 64) String username, @NotBlank String password) {}

    public record UserCreate(
            @NotBlank @Size(max = 64) String username,
            @NotBlank String password,
            @NotNull @Pattern(regexp = "USER|ADMIN") String role) {}

    public record UserPatch(
            @Size(min = 1, max = 64) String username,
            String password,
            @Pattern(regexp = "USER|ADMIN") String role) {}

    public record UserView(Long id, String username, String role) {}

    public record Token(String accessToken, String tokenType, long expiresIn) {}

    public record NewBooking(
            boolean autoSelect,
            Long roomId,
            Long hotelId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate) {}

    public record BookingView(
            UUID id,
            Long roomId,
            Long hotelId,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String compensationStatus,
            String sagaPhase,
            Instant createdAt) {}

    public record StoredResponse(int status, String body) {}
}
