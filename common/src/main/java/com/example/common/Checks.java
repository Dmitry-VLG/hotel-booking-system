package com.example.common;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;

public final class Checks {
    private Checks() {}

    public static void dates(LocalDate start, LocalDate end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw ApiException.bad("Дата заезда должна быть раньше даты выезда");
        }
    }

    public static void newDates(LocalDate start, LocalDate end, Clock clock) {
        dates(start, end);
        if (start.isBefore(LocalDate.now(clock))) throw ApiException.bad("Дата заезда уже прошла");
    }

    public static void page(int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw ApiException.bad("page >= 0, size от 1 до 100");
    }

    public static void password(String value) {
        if (value == null
                || value.length() < 8
                || value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.bad("Пароль: минимум 8 символов, максимум 72 байта UTF-8");
        }
    }
}
