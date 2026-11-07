package com.example.hotel;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class HotelMaintenance {
    private final InventoryService inventory;
    private final ReservationRepository reservations;
    private final Clock clock;

    @Scheduled(fixedDelay = 2000)
    public void expire() {
        for (var id : reservations.expiredIds(clock.instant())) {
            try {
                inventory.expire(id);
            } catch (Exception ex) {
                log.warn("event=expiry_retry reservationId={}", id, ex);
            }
        }
    }
}
