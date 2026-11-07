package com.example.booking;

public final class BookingState {
    private BookingState() {}

    public enum Status {
        PENDING,
        CONFIRMED,
        CANCELLED
    }

    public enum Phase {
        CREATED,
        RESERVING,
        HOLD_REJECTED,
        HELD,
        CONFIRMED,
        CANCELLED
    }

    public enum Compensation {
        NONE,
        REQUIRED,
        DONE
    }
}
