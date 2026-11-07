package com.example.booking;

import com.example.booking.BookingDtos.NewBooking;
import com.example.booking.BookingDtos.StoredResponse;
import com.example.common.ApiException;
import com.example.common.InventoryApi;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private final HotelInventory hotel;

    public StoredResponse create(Long userId, String key, NewBooking input) {
        String hash = BookingTransactions.fingerprint(input);
        BookingTransactions.Opened opened;
        try {
            opened = transactions.open(userId, key, input, hash);
        } catch (DataIntegrityViolationException duplicate) {
            opened = transactions.afterDuplicate(userId, key, hash);
        }
        UUID id = opened.id();
        if (!opened.fresh()) return transactions.response(id);
        try {
            confirm(id);
        } catch (InventoryFailure ex) {
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

    private void confirm(UUID id) {
        var initial = transactions.snapshot(id);
        var rejected = new LinkedHashSet<Long>();
        boolean confirmed = false;
        do {
            List<Long> candidates =
                    initial.autoSelect()
                            ? hotel.recommend(initial, Set.copyOf(rejected)).stream()
                                    .map(c -> c.id())
                                    .toList()
                            : List.of(initial.roomId());
            if (candidates.isEmpty()) break;
            boolean progressed = false;
            for (Long roomId : candidates) {
                if (rejected.contains(roomId)) continue;
                progressed = true;
                var attempt = transactions.choose(id, roomId);
                try {
                    hotel.hold(attempt);
                } catch (InventoryFailure failure) {
                    if (failure.isDefiniteRejection()) {
                        transactions.holdRejected(id, attempt.reservationId());
                        if (initial.autoSelect()
                                && (failure.getStatus() == 409 || failure.getStatus() == 404)) {
                            rejected.add(roomId);
                            continue;
                        }
                    }
                    throw failure;
                }
                if (!transactions.held(id, attempt.reservationId())) {
                    throw new InventoryFailure(
                            504, "BOOKING_STOPPED", "Подтверждение прервано", false);
                }
                hotel.commit(attempt);
                if (!transactions.confirmed(id, attempt.reservationId())) {
                    throw new InventoryFailure(
                            504, "BOOKING_STOPPED", "Подтверждение прервано", false);
                }
                confirmed = true;
                break;
            }
            if (!progressed)
                throw new InventoryFailure(
                        503,
                        "HOTEL_PROTOCOL_ERROR",
                        "Hotel повторил исключённых кандидатов",
                        false);
            if (rejected.size() >= InventoryApi.MAX_CANDIDATE_EXCLUSIONS && !confirmed) {
                throw new InventoryFailure(
                        503,
                        "SELECTION_BUDGET_EXCEEDED",
                        "Исчерпан бюджет подбора; повторите позже",
                        false);
            }
        } while (initial.autoSelect() && !confirmed);
        if (!confirmed)
            throw new InventoryFailure(
                    409, "NO_AVAILABLE_ROOM", "Нет свободных номеров на указанные даты", false);
    }

    public void compensate(UUID id) {
        var snapshot = transactions.snapshot(id);
        if (snapshot.status() != BookingState.Status.CANCELLED
                || snapshot.compensationStatus() != BookingState.Compensation.REQUIRED
                || snapshot.reservationId() == null) return;
        try {
            hotel.release(snapshot);
            transactions.released(id, snapshot.reservationId());
        } catch (RuntimeException ex) {
            log.warn(
                    "event=compensation_pending bookingId={} reservationId={}",
                    id,
                    snapshot.reservationId(),
                    ex);
            transactions.releaseFailed(id);
        }
    }
}
