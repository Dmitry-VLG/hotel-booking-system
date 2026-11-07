package com.example.common;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

public record ApiError(Instant timestamp, int status, String code, String message,
                       String path, String traceId, UUID bookingId,
                       Map<String, String> fieldErrors) {
    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(Instant.now(), status, code, message, path,
                MDC.get("traceId"), null, Map.of());
    }
}
