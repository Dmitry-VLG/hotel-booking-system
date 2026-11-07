package com.example.booking;

import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class BookingRecovery {
    private final BookingRepository bookings;
    private final BookingTransactions transactions;
    private final BookingCoordinator coordinator;
    private final Clock clock;

    @Scheduled(fixedDelay = 2000)
    public void recover() {
        for (var id : bookings.expired(clock.instant(), PageRequest.of(0, 100))) {
            traced(id, () -> transactions.timeout(id));
        }
        for (var id : bookings.compensationDue(clock.instant(), PageRequest.of(0, 100))) {
            traced(id, () -> coordinator.compensate(id));
        }
    }

    private void traced(UUID id, Runnable work) {
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            work.run();
        } catch (Exception ex) {
            log.warn("event=recovery_retry bookingId={}", id, ex);
        } finally {
            MDC.remove("traceId");
        }
    }
}
