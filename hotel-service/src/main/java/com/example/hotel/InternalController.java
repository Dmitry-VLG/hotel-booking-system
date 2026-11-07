package com.example.hotel;

import com.example.common.InventoryApi;
import com.example.common.PageResult;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SCOPE_inventory.write') and authentication.name == 'booking-service'")
public class InternalController {
    private final InventoryService inventory;
    private final CatalogService catalog;
    @PostMapping("/api/rooms/{id}/confirm-availability")
    public InventoryApi.Reservation hold(@PathVariable Long id, @Valid @RequestBody InventoryApi.Command input) {
        return inventory.hold(id, input);
    }
    @PostMapping("/api/rooms/{id}/commit")
    public InventoryApi.Reservation commit(@PathVariable Long id, @Valid @RequestBody InventoryApi.Command input) {
        return inventory.commit(id, input);
    }
    @PostMapping("/api/rooms/{id}/release")
    public InventoryApi.Reservation release(@PathVariable Long id, @Valid @RequestBody InventoryApi.Command input) {
        return inventory.release(id, input);
    }
    @GetMapping("/internal/reservations/{id}")
    public InventoryApi.Reservation get(@PathVariable UUID id) { return inventory.get(id); }
    @GetMapping("/internal/rooms/recommend")
    public PageResult<InventoryApi.Candidate> recommend(@RequestParam Long hotelId, @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) { return catalog.free(hotelId, startDate, endDate, true, 0, 100); }
}
