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
    @Query("select b from BookingEntity b where b.userId=:userId and (:status is null or b.status=:status)")
    Page<BookingEntity> history(@Param("userId") Long userId, @Param("status") String status, Pageable pageable);
    @Query("select b.id from BookingEntity b where b.status='PENDING' and b.deadlineAt<=:now order by b.deadlineAt")
    List<UUID> expired(@Param("now") Instant now, Pageable pageable);
    @Query("select b.id from BookingEntity b where b.status='CANCELLED' and b.compensationStatus='REQUIRED' and b.nextRetryAt<=:now order by b.nextRetryAt")
    List<UUID> compensationDue(@Param("now") Instant now, Pageable pageable);
}
