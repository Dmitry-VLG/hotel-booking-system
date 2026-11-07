package com.example.hotel;

import com.example.hotel.HotelModel.ReservationEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {
    @Query(
            """
        select count(x) from ReservationEntity x where x.roomId=:roomId
        and (x.state=com.example.hotel.ReservationState.COMMITTED or (x.state=com.example.hotel.ReservationState.HELD and x.expiresAt>:now))
        and x.startDate<:endDate and x.endDate>:startDate
        """)
    long overlapping(
            @Param("roomId") Long roomId,
            @Param("startDate") LocalDate start,
            @Param("endDate") LocalDate end,
            @Param("now") Instant now);

    @Query(
            "select x.reservationId from ReservationEntity x where x.state=com.example.hotel.ReservationState.HELD and x.expiresAt<=:now")
    List<UUID> expiredIds(@Param("now") Instant now);

    @Query(
            "select x from ReservationEntity x, RoomEntity r where x.roomId=r.id and r.active=true and (:hotelId is null or r.hotelId=:hotelId) and x.state=com.example.hotel.ReservationState.COMMITTED and x.startDate<:endDate and x.endDate>:startDate")
    List<ReservationEntity> committedInPeriod(
            @Param("hotelId") Long hotelId,
            @Param("startDate") LocalDate start,
            @Param("endDate") LocalDate end);

    @Query(
            "select count(x) from ReservationEntity x where x.roomId=:roomId and (x.state=com.example.hotel.ReservationState.COMMITTED or (x.state=com.example.hotel.ReservationState.HELD and x.expiresAt>:now))")
    long activeCount(@Param("roomId") Long roomId, @Param("now") Instant now);
}
