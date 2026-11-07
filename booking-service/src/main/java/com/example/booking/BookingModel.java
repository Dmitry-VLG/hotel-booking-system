package com.example.booking;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

public final class BookingModel {
    private BookingModel() {}

    @Entity(name = "UserEntity")
    @Table(name = "users")
    @Getter
    @Setter
    @NoArgsConstructor
    public static class UserEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false, length = 64)
        private String username;

        @Column(nullable = false, length = 100)
        private String password;

        @Column(nullable = false, length = 10)
        private String role;

        private boolean active = true;
        private Instant createdAt;
        @Version private long version;
    }

    @Entity(name = "BookingEntity")
    @Table(name = "bookings")
    @Getter
    @Setter
    @NoArgsConstructor
    public static class BookingEntity {
        @Id private UUID id;
        private Long userId;
        private Long roomId;
        private Long hotelId;
        private boolean autoSelect;
        private LocalDate startDate;
        private LocalDate endDate;

        @Column(length = 16)
        @Enumerated(EnumType.STRING)
        @JdbcTypeCode(SqlTypes.VARCHAR)
        private BookingState.Status status;

        private Instant createdAt;
        private Instant updatedAt;
        private Instant deadlineAt;
        private UUID reservationId;

        @Column(length = 24)
        @Enumerated(EnumType.STRING)
        @JdbcTypeCode(SqlTypes.VARCHAR)
        private BookingState.Phase sagaPhase;

        @Column(length = 16)
        @Enumerated(EnumType.STRING)
        @JdbcTypeCode(SqlTypes.VARCHAR)
        private BookingState.Compensation compensationStatus;

        @Column(length = 30)
        private String releaseReason;

        @Column(length = 100)
        private String requestKey;

        @Column(length = 64)
        private String payloadHash;

        private Integer cachedStatus;
        @Lob private String cachedBody;
        private int retryCount;
        private Instant nextRetryAt;
        @Version private long version;
    }
}
