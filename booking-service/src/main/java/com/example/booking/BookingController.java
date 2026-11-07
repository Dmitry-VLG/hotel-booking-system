package com.example.booking;

import com.example.booking.BookingDtos.*;
import com.example.common.PageResult;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class BookingController {
    private final UserService users;
    private final BookingCoordinator coordinator;
    private final BookingTransactions transactions;

    @PostMapping
    public ResponseEntity<String> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody NewBooking input) {
        var result=coordinator.create(users.current(jwt), key, input);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
    @GetMapping
    public PageResult<BookingView> history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String status, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return transactions.history(users.current(jwt), status, page, size);
    }
    @GetMapping("/{id}")
    public BookingView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return transactions.owned(id, users.current(jwt));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<BookingView> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Long userId=users.current(jwt);
        transactions.cancelOwned(id, userId);
        coordinator.compensate(id);
        var result=transactions.owned(id, userId);
        return "REQUIRED".equals(result.compensationStatus())
                ? ResponseEntity.accepted().body(result) : ResponseEntity.noContent().build();
    }
}
