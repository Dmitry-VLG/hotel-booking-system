package com.example.hotel;

import com.example.hotel.HotelModel.RoomEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<RoomEntity, Long> {
    Optional<RoomEntity> findByIdAndActiveTrue(Long id);

    @Query(
            "select r from RoomEntity r where r.active=true and (:hotelId is null or r.hotelId=:hotelId) order by r.id")
    List<RoomEntity> activeForHotel(@Param("hotelId") Long hotelId);

    boolean existsByHotelIdAndActiveTrue(Long hotelId);

    Page<RoomEntity> findByActiveTrue(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RoomEntity r where r.id=:id")
    Optional<RoomEntity> locked(@Param("id") Long id);

    @Query(
            """
        select r from RoomEntity r where r.active=true and r.available=true
        and (:hotelId is null or r.hotelId=:hotelId) and r.id not in :excludedIds
        and not exists (select x from ReservationEntity x where x.roomId=r.id
          and (x.state=com.example.hotel.ReservationState.COMMITTED or (x.state=com.example.hotel.ReservationState.HELD and x.expiresAt>:now))
          and x.startDate<:endDate and x.endDate>:startDate)
        """)
    Page<RoomEntity> free(
            @Param("hotelId") Long hotelId,
            @Param("startDate") LocalDate start,
            @Param("endDate") LocalDate end,
            @Param("now") Instant now,
            @Param("excludedIds") Collection<Long> excludedIds,
            Pageable pageable);
}
