package com.example.booking;

import com.example.booking.BookingDtos.*;
import com.example.booking.BookingModel.UserEntity;
import com.example.common.ApiException;
import com.example.common.Checks;
import com.example.common.PageResult;
import java.time.Clock;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final Clock clock;
    private String username(String text) {
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 64) throw ApiException.bad("Некорректное имя пользователя");
        return normalized;
    }
    private UserEntity entity(Long id) {
        return users.findByIdAndActiveTrue(id).orElseThrow(() -> ApiException.missing("Пользователь не найден"));
    }
    private UserView view(UserEntity u) { return new UserView(u.getId(), u.getUsername(), u.getRole()); }
    private UserEntity add(String name, String password, String role) {
        Checks.password(password);
        var u = new UserEntity(); u.setUsername(username(name)); u.setPassword(passwords.encode(password));
        u.setRole(role); u.setCreatedAt(clock.instant());
        return users.saveAndFlush(u);
    }
    public Token register(Credentials input) { return tokens.user(add(input.username(), input.password(), "USER")); }
    @Transactional(readOnly=true)
    public Token login(Credentials input) {
        Checks.password(input.password());
        var u = users.findByUsernameAndActiveTrue(username(input.username())).orElse(null);
        if (u == null || !passwords.matches(input.password(), u.getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Неверное имя или пароль");
        }
        return tokens.user(u);
    }
    @Transactional(readOnly=true)
    public Long current(Jwt jwt) {
        Long id;
        try { id = Long.valueOf(jwt.getSubject()); }
        catch (Exception ex) { throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SUBJECT", "Некорректный токен"); }
        if (users.findByIdAndActiveTrue(id).isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "USER_DISABLED", "Пользователь отключён");
        }
        return id;
    }
    public UserView create(UserCreate input) { return view(add(input.username(), input.password(), input.role())); }
    @Transactional(readOnly=true)
    public UserView get(Long id) { return view(entity(id)); }
    @Transactional(readOnly=true)
    public PageResult<UserView> list(int page, int size) {
        Checks.page(page, size);
        var result = users.findByActiveTrue(PageRequest.of(page, size, Sort.by("id")));
        return new PageResult<>(result.map(this::view).getContent(), page, size, result.getTotalElements());
    }
    public UserView update(Long id, UserPatch input) {
        var u = entity(id);
        if (input.username() != null) u.setUsername(username(input.username()));
        if (input.password() != null) { Checks.password(input.password()); u.setPassword(passwords.encode(input.password())); }
        if (input.role() != null) u.setRole(input.role());
        return view(u);
    }
    public void delete(Long id) { entity(id).setActive(false); }
}
