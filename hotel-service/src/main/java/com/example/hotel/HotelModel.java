package com.example.hotel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

public final class HotelModel {
    private HotelModel() {}

    @Entity(name = "HotelEntity")
    @Table(name = "hotels")
    @Getter
    @Setter
    @NoArgsConstructor
    public static class HotelEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false, length = 120)
        private String name;

        @Column(nullable = false, length = 250)
        private String address;

        private boolean active = true;
        @Version private long version;
    }

    @Entity(name = "RoomEntity")
    @Table(name = "rooms")
    @Getter
    @Setter
    @NoArgsConstructor
    public static class RoomEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "hotel_id", nullable = false)
        private Long hotelId;

        @Column(nullable = false, length = 30)
        private String number;

        private boolean available = true;

        @Column(name = "times_booked", nullable = false)
        private long timesBooked;

        private boolean active = true;
        @Version private long version;
    }

    @Entity(name = "ReservationEntity")
    @Table(name = "room_reservations")
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ReservationEntity {
        @Id
        @Column(name = "reservation_id")
        private UUID reservationId;

        @Column(name = "booking_id", nullable = false)
        private UUID bookingId;

        @Column(name = "room_id", nullable = false)
        private Long roomId;

        private LocalDate startDate;
        private LocalDate endDate;

        @Column(length = 20, nullable = false)
        @Enumerated(EnumType.STRING)
        @JdbcTypeCode(SqlTypes.VARCHAR)
        private ReservationState state;

        private Instant expiresAt;
        private boolean counted;

        @Column(length = 30)
        private String releaseReason;

        private Instant createdAt;
        @Version private long version;
    }
}
