package com.example.booking;

import com.example.booking.BookingDtos.*;
import com.example.booking.BookingModel.BookingEntity;
import com.example.booking.BookingState.*;
import com.example.common.ApiError;
import com.example.common.ApiException;
import com.example.common.Checks;
import com.example.common.PageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BookingTransactions {
    private final BookingRepository bookings;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Value("${app.booking-deadline-seconds:15}")
    private long deadlineSeconds;

    public record Opened(UUID id, boolean fresh) {}

    public record Snapshot(
            UUID id,
            Long userId,
            Long roomId,
            Long hotelId,
            boolean autoSelect,
            LocalDate startDate,
            LocalDate endDate,
            Status status,
            UUID reservationId,
            Instant deadlineAt,
            String releaseReason,
            Compensation compensationStatus) {}

    private BookingEntity lock(UUID id) {
        return bookings.locked(id).orElseThrow(() -> ApiException.missing("Бронь не найдена"));
    }

    private BookingEntity find(UUID id) {
        return bookings.findById(id).orElseThrow(() -> ApiException.missing("Бронь не найдена"));
    }

    private void owner(BookingEntity b, Long userId) {
        if (!b.getUserId().equals(userId)) throw ApiException.missing("Бронь не найдена");
    }

    private BookingView view(BookingEntity b) {
        return new BookingView(
                b.getId(),
                b.getRoomId(),
                b.getHotelId(),
                b.getStartDate(),
                b.getEndDate(),
                b.getStatus().name(),
                b.getCompensationStatus().name(),
                b.getSagaPhase().name(),
                b.getCreatedAt());
    }

    private Snapshot snapshot(BookingEntity b) {
        return new Snapshot(
                b.getId(),
                b.getUserId(),
                b.getRoomId(),
                b.getHotelId(),
                b.isAutoSelect(),
                b.getStartDate(),
                b.getEndDate(),
                b.getStatus(),
                b.getReservationId(),
                b.getDeadlineAt(),
                b.getReleaseReason(),
                b.getCompensationStatus());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot serialize API response", ex);
        }
    }

    private boolean pending(BookingEntity b) {
        return b.getStatus() == Status.PENDING && b.getDeadlineAt().isAfter(clock.instant());
    }

    public static String fingerprint(NewBooking input) {
        String selection =
                input.autoSelect() ? "auto|" + input.hotelId() : "manual|" + input.roomId();
        String text = selection + "|" + input.startDate() + "|" + input.endDate();
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Opened replay(BookingEntity b, String hash) {
        if (!b.getPayloadHash().equals(hash))
            throw ApiException.conflict("Ключ идемпотентности использован с другим запросом");
        return new Opened(b.getId(), false);
    }

    public Opened open(Long userId, String key, NewBooking input, String hash) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw ApiException.bad("Idempotency-Key: от 1 до 100 латинских букв, цифр или . _ : -");
        }
        var existing = bookings.findByUserIdAndRequestKey(userId, key);
        if (existing.isPresent()) return replay(existing.get(), hash);
        Checks.newDates(input.startDate(), input.endDate(), clock);
        if (input.autoSelect()) {
            if (input.hotelId() == null || input.hotelId() <= 0)
                throw ApiException.bad("Для автоподбора нужен hotelId > 0");
        } else if (input.roomId() == null || input.roomId() <= 0) {
            throw ApiException.bad("Для ручного выбора нужен roomId > 0");
        }
        var b = new BookingEntity();
        b.setId(UUID.randomUUID());
        b.setUserId(userId);
        b.setAutoSelect(input.autoSelect());
        b.setRoomId(input.autoSelect() ? null : input.roomId());
        b.setHotelId(input.autoSelect() ? input.hotelId() : null);
        b.setStartDate(input.startDate());
        b.setEndDate(input.endDate());
        b.setStatus(Status.PENDING);
        b.setCreatedAt(clock.instant());
        b.setUpdatedAt(clock.instant());
        b.setDeadlineAt(clock.instant().plusSeconds(deadlineSeconds));
        b.setSagaPhase(Phase.CREATED);
        b.setCompensationStatus(Compensation.NONE);
        b.setRequestKey(key);
        b.setPayloadHash(hash);
        b = bookings.saveAndFlush(b);
        log.info("event=booking_pending bookingId={} userId={}", b.getId(), userId);
        return new Opened(b.getId(), true);
    }

    @Transactional(readOnly = true)
    public Opened afterDuplicate(Long userId, String key, String hash) {
        return replay(
                bookings.findByUserIdAndRequestKey(userId, key)
                        .orElseThrow(
                                () ->
                                        ApiException.conflict(
                                                "Не удалось получить конкурентную операцию; повторите запрос")),
                hash);
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID id) {
        return snapshot(find(id));
    }

    public Snapshot choose(UUID id, Long roomId) {
        var b = lock(id);
        if (!pending(b)) throw ApiException.conflict("Операция уже отменена или истёк её срок");
        b.setRoomId(roomId);
        b.setReservationId(UUID.randomUUID());
        b.setSagaPhase(Phase.RESERVING);
        b.setUpdatedAt(clock.instant());
        return snapshot(b);
    }

    public void holdRejected(UUID id, UUID reservationId) {
        var b = lock(id);
        if (!reservationId.equals(b.getReservationId()) || b.getStatus() == Status.CONFIRMED)
            return;
        if (b.getStatus() == Status.PENDING) b.setSagaPhase(Phase.HOLD_REJECTED);
        if (b.getStatus() == Status.CANCELLED
                && b.getCompensationStatus() == Compensation.REQUIRED) {
            b.setCompensationStatus(Compensation.DONE);
            b.setNextRetryAt(null);
        }
        b.setUpdatedAt(clock.instant());
    }

    public boolean held(UUID id, UUID reservationId) {
        var b = lock(id);
        if (!pending(b) || !reservationId.equals(b.getReservationId())) return false;
        b.setSagaPhase(Phase.HELD);
        b.setUpdatedAt(clock.instant());
        return true;
    }

    public boolean confirmed(UUID id, UUID reservationId) {
        var b = lock(id);
        if (!pending(b) || !reservationId.equals(b.getReservationId())) return false;
        b.setStatus(Status.CONFIRMED);
        b.setSagaPhase(Phase.CONFIRMED);
        b.setUpdatedAt(clock.instant());
        b.setCachedStatus(201);
        b.setCachedBody(json(view(b)));
        log.info("event=booking_confirmed bookingId={} reservationId={}", id, reservationId);
        return true;
    }

    private void cancelPending(BookingEntity b, int status, String code, String message) {
        boolean noHold = b.getReservationId() == null || b.getSagaPhase() == Phase.HOLD_REJECTED;
        b.setStatus(Status.CANCELLED);
        b.setSagaPhase(Phase.CANCELLED);
        b.setUpdatedAt(clock.instant());
        b.setReleaseReason("COMPENSATION");
        b.setCompensationStatus(noHold ? Compensation.NONE : Compensation.REQUIRED);
        b.setNextRetryAt(noHold ? null : clock.instant());
        b.setCachedStatus(status);
        b.setCachedBody(
                json(
                        new ApiError(
                                clock.instant(),
                                status,
                                code,
                                message,
                                "/api/bookings",
                                MDC.get("traceId"),
                                b.getId(),
                                Map.of())));
        log.info(
                "event=booking_cancelled bookingId={} reason={} compensation={}",
                b.getId(),
                code,
                b.getCompensationStatus());
    }

    public void fail(UUID id, int status, String code, String message) {
        var b = lock(id);
        if (b.getStatus() == Status.PENDING) cancelPending(b, status, code, message);
    }

    public void timeout(UUID id) {
        var b = lock(id);
        if (b.getStatus() == Status.PENDING && !b.getDeadlineAt().isAfter(clock.instant())) {
            cancelPending(b, 504, "BOOKING_TIMEOUT", "Истекло время подтверждения брони");
        }
    }

    public void cancelOwned(UUID id, Long userId) {
        var b = lock(id);
        owner(b, userId);
        if (b.getStatus() == Status.CANCELLED) return;
        if (b.getStatus() == Status.PENDING) {
            cancelPending(b, 409, "BOOKING_CANCELLED", "Бронирование отменено пользователем");
        } else {
            b.setStatus(Status.CANCELLED);
            b.setSagaPhase(Phase.CANCELLED);
            b.setReleaseReason("USER_CANCELLATION");
            b.setCompensationStatus(Compensation.REQUIRED);
            b.setNextRetryAt(clock.instant());
            b.setUpdatedAt(clock.instant());
        }
    }

    public void released(UUID id, UUID reservationId) {
        var b = lock(id);
        if (b.getStatus() == Status.CANCELLED && reservationId.equals(b.getReservationId())) {
            b.setCompensationStatus(Compensation.DONE);
            b.setNextRetryAt(null);
            b.setUpdatedAt(clock.instant());
            log.info("event=compensation_done bookingId={} reservationId={}", id, reservationId);
        }
    }

    public void releaseFailed(UUID id) {
        var b = lock(id);
        if (b.getStatus() == Status.CANCELLED
                && b.getCompensationStatus() == Compensation.REQUIRED) {
            b.setRetryCount(b.getRetryCount() + 1);
            long seconds = Math.min(60, 1L << Math.min(6, b.getRetryCount()));
            b.setNextRetryAt(clock.instant().plusSeconds(seconds));
        }
    }

    @Transactional(readOnly = true)
    public StoredResponse response(UUID id) {
        var b = find(id);
        return b.getCachedBody() == null
                ? new StoredResponse(202, json(view(b)))
                : new StoredResponse(b.getCachedStatus(), b.getCachedBody());
    }

    @Transactional(readOnly = true)
    public BookingView owned(UUID id, Long userId) {
        var b = find(id);
        owner(b, userId);
        return view(b);
    }

    @Transactional(readOnly = true)
    public PageResult<BookingView> history(Long userId, String status, int page, int size) {
        Checks.page(page, size);
        if (status != null && !Set.of("PENDING", "CONFIRMED", "CANCELLED").contains(status)) {
            throw ApiException.bad("Неизвестный статус бронирования");
        }
        var result =
                bookings.history(
                        userId,
                        status == null ? null : Status.valueOf(status),
                        PageRequest.of(
                                page,
                                size,
                                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new PageResult<>(
                result.map(this::view).getContent(), page, size, result.getTotalElements());
    }
}
