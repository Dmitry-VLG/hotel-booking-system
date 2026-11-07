package com.example.booking;

import com.example.booking.BookingDtos.*;
import com.example.common.PageResult;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserService users;

    @SecurityRequirements
    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Token register(@Valid @RequestBody Credentials input) {
        return users.register(input);
    }

    @SecurityRequirements
    @PostMapping("/api/auth/login")
    public Token login(@Valid @RequestBody Credentials input) {
        return users.login(input);
    }

    @PostMapping("/api/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public UserView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UserCreate input) {
        users.current(jwt);
        return users.create(input);
    }

    @GetMapping("/api/users")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResult<UserView> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        users.current(jwt);
        return users.list(page, size);
    }

    @GetMapping("/api/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserView get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        users.current(jwt);
        return users.get(id);
    }

    @PatchMapping("/api/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserView patch(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody UserPatch input) {
        users.current(jwt);
        return users.update(id, input);
    }

    @DeleteMapping("/api/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        users.current(jwt);
        users.delete(id);
    }
}
