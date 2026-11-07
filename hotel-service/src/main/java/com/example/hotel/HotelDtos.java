package com.example.hotel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class HotelDtos {
    private HotelDtos() {}

    public record HotelInput(
            @NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 250) String address) {}

    public record HotelView(Long id, String name, String address) {}

    public record RoomInput(
            @NotNull @Positive Long hotelId,
            @NotBlank @Size(max = 30) String number,
            boolean available) {}

    public record RoomPatch(@Size(min = 1, max = 30) String number, Boolean available) {}

    public record RoomView(
            Long id, Long hotelId, String number, boolean available, long timesBooked) {}

    public record Occupancy(
            Long roomId,
            String number,
            long timesBooked,
            long occupiedNights,
            long capacityNights,
            double occupancyPercent) {}
}
