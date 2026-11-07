package com.example.testing;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

public class AdjustableClock extends Clock {
    private static final Instant INITIAL = Instant.parse("2030-01-01T00:00:00Z");
    private final AtomicReference<Instant> current = new AtomicReference<>(INITIAL);

    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    public Instant instant() {
        return current.get();
    }

    public void reset() {
        current.set(INITIAL);
    }

    public void advance(long seconds) {
        current.updateAndGet(value -> value.plusSeconds(seconds));
    }
}
