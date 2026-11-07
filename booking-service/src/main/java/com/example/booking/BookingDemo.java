package com.example.booking;

import com.example.booking.BookingDtos.UserCreate;
import com.example.common.Checks;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="app.demo.enabled", havingValue="true")
public class BookingDemo implements CommandLineRunner {
    private final UserService users;
    @Value("${app.demo.admin-password}") private String adminPassword;
    @Value("${app.demo.user-password}") private String userPassword;
    @Override
    public void run(String... args) {
        Checks.password(adminPassword); Checks.password(userPassword);
        users.create(new UserCreate("admin", adminPassword, "ADMIN"));
        users.create(new UserCreate("alice", userPassword, "USER"));
        users.create(new UserCreate("bob", userPassword, "USER"));
    }
}
