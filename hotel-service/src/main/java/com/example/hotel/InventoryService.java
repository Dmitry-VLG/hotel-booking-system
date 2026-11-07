package com.example.hotel;

import com.example.common.ApiException;
import com.example.common.Checks;
import com.example.common.InventoryApi.Command;
import com.example.common.InventoryApi.Reservation;
import com.example.hotel.HotelModel.ReservationEntity;
import com.example.hotel.HotelModel.RoomEntity;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {
    private final RoomRepository rooms;
    private final ReservationRepository reservations;
    private final EntityManager entityManager;
    private final Clock clock;
    @Value("${app.hold-ttl-seconds:60}") private long ttl;

    private RoomEntity lock(Long id) {
        return rooms.locked(id).orElseThrow(() -> ApiException.missing("Номер не найден"));
    }
    private void command(Command c, String operation) {
        Checks.dates(c.startDate(), c.endDate());
        if (!Objects.equals(c.requestId(), c.reservationId() + ":" + operation)) {
            throw ApiException.bad("requestId не соответствует операции");
        }
    }
    private void same(ReservationEntity r, Long roomId, Command c) {
        if (!r.getBookingId().equals(c.bookingId()) || !r.getRoomId().equals(roomId)
                || !r.getStartDate().equals(c.startDate()) || !r.getEndDate().equals(c.endDate())) {
            throw ApiException.conflict("reservationId уже использован с другими параметрами");
        }
    }
    private ReservationEntity create(Long roomId, Command c, String state) {
        var r = new ReservationEntity(); r.setReservationId(c.reservationId()); r.setBookingId(c.bookingId());
        r.setRoomId(roomId); r.setStartDate(c.startDate()); r.setEndDate(c.endDate());
        r.setState(state); r.setCreatedAt(clock.instant());
        return r;
    }
    private Reservation view(ReservationEntity r) {
        return new Reservation(r.getReservationId(), r.getBookingId(), r.getRoomId(),
                r.getStartDate(), r.getEndDate(), r.getState(), r.getExpiresAt());
    }
    private boolean validHold(ReservationEntity r) {
        return "HELD".equals(r.getState()) && r.getExpiresAt().isAfter(clock.instant());
    }

    @Transactional
    public Reservation hold(Long roomId, Command c) {
        command(c, "hold");
        var room = lock(roomId);
        var previous = reservations.findById(c.reservationId());
        if (previous.isPresent()) {
            var r = previous.get(); same(r, roomId, c);
            if (validHold(r) || "COMMITTED".equals(r.getState())) return view(r);
            throw ApiException.conflict("Этот резерв уже завершён или истёк");
        }
        Checks.newDates(c.startDate(), c.endDate(), clock);
        if (!room.isActive() || !room.isAvailable()) throw ApiException.conflict("Номер недоступен");
        if (reservations.overlapping(roomId, c.startDate(), c.endDate(), clock.instant()) != 0) {
            throw ApiException.conflict("Номер занят на указанные даты");
        }
        var r = create(roomId, c, "HELD"); r.setExpiresAt(clock.instant().plusSeconds(ttl));
        reservations.saveAndFlush(r);
        log.info("event=inventory_hold bookingId={} reservationId={} roomId={} requestId={}", c.bookingId(), c.reservationId(), roomId, c.requestId());
        return view(r);
    }

    @Transactional
    public Reservation commit(Long roomId, Command c) {
        command(c, "commit"); var room = lock(roomId);
        var r = reservations.findById(c.reservationId()).orElseThrow(() -> ApiException.conflict("Резерв не создан"));
        same(r, roomId, c);
        if ("COMMITTED".equals(r.getState())) return view(r);
        if (!validHold(r)) throw ApiException.conflict("Резерв истёк или освобождён");
        r.setState("COMMITTED"); r.setCounted(true); room.setTimesBooked(room.getTimesBooked() + 1);
        log.info("event=inventory_commit bookingId={} reservationId={} requestId={}", c.bookingId(), c.reservationId(), c.requestId());
        return view(r);
    }

    @Transactional
    public Reservation release(Long roomId, Command c) {
        command(c, "release");
        if (!"COMPENSATION".equals(c.reason()) && !"USER_CANCELLATION".equals(c.reason())) {
            throw ApiException.bad("Неизвестная причина освобождения");
        }
        var room = lock(roomId);
        var r = reservations.findById(c.reservationId()).orElse(null);
        if (r == null) {
            r = create(roomId, c, "RELEASED"); r.setReleaseReason(c.reason());
            reservations.saveAndFlush(r);
        } else {
            same(r, roomId, c);
            if ("RELEASED".equals(r.getState())) return view(r);
            if (r.isCounted() && "COMPENSATION".equals(c.reason())) {
                if (room.getTimesBooked() == 0) throw new IllegalStateException("Counter invariant violated");
                room.setTimesBooked(room.getTimesBooked() - 1); r.setCounted(false);
            }
            r.setState("RELEASED"); r.setReleaseReason(c.reason());
        }
        log.info("event=inventory_release bookingId={} reservationId={} reason={} requestId={}", c.bookingId(), c.reservationId(), c.reason(), c.requestId());
        return view(r);
    }

    @Transactional(readOnly = true)
    public Reservation get(UUID id) {
        return view(reservations.findById(id).orElseThrow(() -> ApiException.missing("Резерв не найден")));
    }

    @Transactional
    public void expire(UUID id) {
        var values = entityManager.createQuery("select r.roomId from ReservationEntity r where r.reservationId=:id", Long.class)
                .setParameter("id", id).getResultList();
        if (values.isEmpty()) return;
        lock(values.getFirst());
        var r = reservations.findById(id).orElseThrow();
        if ("HELD".equals(r.getState()) && !r.getExpiresAt().isAfter(clock.instant())) {
            r.setState("EXPIRED");
            log.info("event=inventory_expired bookingId={} reservationId={}", r.getBookingId(), id);
        }
    }
}
