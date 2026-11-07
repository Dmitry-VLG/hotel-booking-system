package com.example.common;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class WebErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException ex, HttpServletRequest request) {
        return response(ex.status().value(), ex.code(), ex.getMessage(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        HttpServletRequest servlet = ((ServletWebRequest) request).getRequest();
        ApiError base =
                ApiError.of(
                        400,
                        "VALIDATION_FAILED",
                        "Проверьте поля запроса",
                        servlet.getRequestURI());
        return new ResponseEntity<>(
                new ApiError(
                        base.timestamp(),
                        400,
                        base.code(),
                        base.message(),
                        base.path(),
                        base.traceId(),
                        null,
                        fields),
                headers,
                status);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> malformed(Exception ex, HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", "Некорректные параметры запроса", request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        HttpServletRequest servlet = ((ServletWebRequest) request).getRequest();
        if (servlet.isAsyncStarted() && servlet.getDispatcherType() != DispatcherType.ASYNC) {
            return super.handleExceptionInternal(ex, body, headers, status, request);
        }
        String code =
                switch (status.value()) {
                    case 404 -> "NOT_FOUND";
                    case 405 -> "METHOD_NOT_ALLOWED";
                    case 406 -> "NOT_ACCEPTABLE";
                    case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                    default -> status.is4xxClientError() ? "INVALID_REQUEST" : "INTERNAL_ERROR";
                };
        String message =
                HttpStatus.resolve(status.value()) == null
                        ? "Ошибка HTTP"
                        : HttpStatus.valueOf(status.value()).getReasonPhrase();
        return super.handleExceptionInternal(
                ex,
                ApiError.of(status.value(), code, message, servlet.getRequestURI()),
                headers,
                status,
                request);
    }

    @ExceptionHandler({
        DataIntegrityViolationException.class,
        OptimisticLockingFailureException.class
    })
    ResponseEntity<ApiError> conflict(Exception ex, HttpServletRequest request) {
        return response(
                409, "DATA_CONFLICT", "Конфликт уникальности или связности данных", request);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiError> lock(Exception ex, HttpServletRequest request) {
        return response(
                503, "LOCK_TIMEOUT", "Данные изменяются другим запросом; повторите позже", request);
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

    private ResponseEntity<ApiError> response(
            int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status, code, message, request.getRequestURI()));
    }
}
