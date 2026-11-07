package com.example.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class WebErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException ex, HttpServletRequest request) {
        return response(ex.status().value(), ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        ApiError base = ApiError.of(400, "VALIDATION_FAILED", "Проверьте поля запроса", request.getRequestURI());
        return ResponseEntity.badRequest().body(new ApiError(base.timestamp(), 400, base.code(),
                base.message(), base.path(), base.traceId(), null, fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class, ServletRequestBindingException.class})
    ResponseEntity<ApiError> malformed(Exception ex, HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", "Некорректные параметры запроса", request);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    ResponseEntity<ApiError> conflict(Exception ex, HttpServletRequest request) {
        return response(409, "DATA_CONFLICT", "Конфликт уникальности или связности данных", request);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiError> lock(Exception ex, HttpServletRequest request) {
        return response(503, "LOCK_TIMEOUT", "Данные изменяются другим запросом; повторите позже", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(Exception ex, HttpServletRequest request) {
        return response(403, "FORBIDDEN", "Недостаточно прав", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        log.error("event=unexpected_error path={}", request.getRequestURI(), ex);
        return response(500, "INTERNAL_ERROR", "Внутренняя ошибка сервиса", request);
    }

    private ResponseEntity<ApiError> response(int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiError.of(status, code, message, request.getRequestURI()));
    }
}
