package com.example.booking;

import com.example.booking.BookingDtos.NewBooking;
import com.example.booking.BookingDtos.StoredResponse;
import com.example.common.ApiException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingCoordinator {
    private final BookingTransactions transactions;
    private final HotelClient hotel;

    public StoredResponse create(Long userId, String key, NewBooking input) {
        String hash=BookingTransactions.fingerprint(input);
        BookingTransactions.Opened opened;
        try { opened=transactions.open(userId, key, input, hash); }
        catch (DataIntegrityViolationException duplicate) {
            opened=transactions.afterDuplicate(userId, key, hash);
        }
        UUID id=opened.id();
        if (!opened.fresh()) return transactions.response(id);
        try {
            var initial=transactions.snapshot(id);
            List<Long> candidates=initial.autoSelect()
                    ? hotel.recommend(initial).stream().map(c -> c.id()).toList()
                    : List.of(initial.roomId());
            boolean confirmed=false;
            for (Long roomId : candidates) {
                var attempt=transactions.choose(id, roomId);
                try { hotel.hold(attempt); }
                catch (HotelClient.Failure conflict) {
                    // Только однозначный отказ hold позволяет перейти к другому номеру.
                    if (initial.autoSelect() && conflict.getStatus()==409) continue;
                    throw conflict;
                }
                if (!transactions.held(id, attempt.reservationId())) {
                    throw new HotelClient.Failure(504, "BOOKING_STOPPED", "Подтверждение прервано", false);
                }
                hotel.commit(attempt);
                if (!transactions.confirmed(id, attempt.reservationId())) {
                    throw new HotelClient.Failure(504, "BOOKING_STOPPED", "Подтверждение прервано", false);
                }
                confirmed=true;
                break;
            }
            if (!confirmed) throw new HotelClient.Failure(409, "NO_AVAILABLE_ROOM", "Не удалось выделить номер среди кандидатов", false);
        } catch (HotelClient.Failure ex) {
            transactions.timeout(id);
            transactions.fail(id, ex.getStatus(), ex.getCode(), ex.getMessage());
        } catch (ApiException ex) {
            transactions.timeout(id);
            transactions.fail(id, ex.status().value(), ex.code(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("event=booking_unexpected bookingId={}", id, ex);
            transactions.timeout(id);
            transactions.fail(id, 500, "BOOKING_FAILED", "Не удалось завершить бронирование");
        }
        compensate(id);
        return transactions.response(id);
    }

    public void compensate(UUID id) {
        var snapshot=transactions.snapshot(id);
        if (!"CANCELLED".equals(snapshot.status()) || !"REQUIRED".equals(snapshot.compensationStatus())
                || snapshot.reservationId()==null) return;
        try {
            hotel.release(snapshot);
            transactions.released(id, snapshot.reservationId());
        } catch (RuntimeException ex) {
            log.warn("event=compensation_pending bookingId={} reservationId={}", id, snapshot.reservationId(), ex);
            transactions.releaseFailed(id);
        }
    }
}
