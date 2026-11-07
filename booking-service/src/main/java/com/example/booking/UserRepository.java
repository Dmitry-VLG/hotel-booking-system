package com.example.booking;

import com.example.booking.BookingModel.UserEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsernameAndActiveTrue(String username);
    Optional<UserEntity> findByIdAndActiveTrue(Long id);
    Page<UserEntity> findByActiveTrue(Pageable pageable);
}
