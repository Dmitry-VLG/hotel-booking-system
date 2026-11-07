package com.example.hotel;

import com.example.hotel.HotelModel.HotelEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HotelRepository extends JpaRepository<HotelEntity, Long> {
    Optional<HotelEntity> findByIdAndActiveTrue(Long id);
    @Query("select h from HotelEntity h where h.active=true and (:name is null or lower(h.name) like lower(concat('%',:name,'%')))")
    Page<HotelEntity> search(@Param("name") String name, Pageable pageable);
}
