package com.example.booking;

import com.example.booking.BookingModel.BookingEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<BookingEntity, UUID> {
    Optional<BookingEntity> findByUserIdAndRequestKey(Long userId, String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BookingEntity b where b.id=:id")
    Optional<BookingEntity> locked(@Param("id") UUID id);

    @Query(
            "select b from BookingEntity b where b.userId=:userId and (:status is null or b.status=:status)")
    Page<BookingEntity> history(
            @Param("userId") Long userId,
            @Param("status") BookingState.Status status,
            Pageable pageable);

    @Query(
            "select b.id from BookingEntity b where b.status=:status and b.deadlineAt<=:now order by b.deadlineAt")
    List<UUID> expiredWithStatus(
            @Param("now") Instant now,
            @Param("status") BookingState.Status status,
            Pageable pageable);

    default List<UUID> expired(Instant now, Pageable pageable) {
        return expiredWithStatus(now, BookingState.Status.PENDING, pageable);
    }

    @Query(
            "select b.id from BookingEntity b where b.status=:status and b.compensationStatus=:compensation and b.nextRetryAt<=:now order by b.nextRetryAt")
    List<UUID> compensationDueWithStatus(
            @Param("now") Instant now,
            @Param("status") BookingState.Status status,
            @Param("compensation") BookingState.Compensation compensation,
            Pageable pageable);

    default List<UUID> compensationDue(Instant now, Pageable pageable) {
        return compensationDueWithStatus(
                now, BookingState.Status.CANCELLED, BookingState.Compensation.REQUIRED, pageable);
    }
}
