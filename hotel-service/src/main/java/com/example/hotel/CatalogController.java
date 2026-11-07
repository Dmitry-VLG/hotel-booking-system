package com.example.hotel;

import com.example.common.InventoryApi.Candidate;
import com.example.common.PageResult;
import com.example.hotel.HotelDtos.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class CatalogController {
    private final CatalogService service;
    @PostMapping("/api/hotels") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')")
    public HotelView createHotel(@Valid @RequestBody HotelInput input) { return service.createHotel(input); }
    @GetMapping("/api/hotels")
    public PageResult<HotelView> hotels(@RequestParam(required=false) String name,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) { return service.hotels(name, page, size); }
    @GetMapping("/api/hotels/{id}") public HotelView hotel(@PathVariable Long id) { return service.getHotel(id); }
    @PatchMapping("/api/hotels/{id}") @PreAuthorize("hasRole('ADMIN')")
    public HotelView updateHotel(@PathVariable Long id, @Valid @RequestBody HotelInput input) { return service.updateHotel(id, input); }
    @DeleteMapping("/api/hotels/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')")
    public void deleteHotel(@PathVariable Long id) { service.deleteHotel(id); }
    @PostMapping("/api/rooms") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')")
    public RoomView createRoom(@Valid @RequestBody RoomInput input) { return service.createRoom(input); }
    @GetMapping("/api/rooms/{id}") public RoomView room(@PathVariable Long id) { return service.getRoom(id); }
    @PatchMapping("/api/rooms/{id}") @PreAuthorize("hasRole('ADMIN')")
    public RoomView updateRoom(@PathVariable Long id, @Valid @RequestBody RoomPatch input) { return service.updateRoom(id, input); }
    @DeleteMapping("/api/rooms/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')")
    public void deleteRoom(@PathVariable Long id) { service.deleteRoom(id); }
    @GetMapping("/api/rooms")
    public PageResult<Candidate> free(@RequestParam(required=false) Long hotelId, @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) { return service.free(hotelId, startDate, endDate, false, page, size); }
    @GetMapping("/api/rooms/recommend")
    public PageResult<Candidate> recommend(@RequestParam(required=false) Long hotelId, @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) { return service.free(hotelId, startDate, endDate, true, page, size); }
    @GetMapping("/api/admin/rooms") @PreAuthorize("hasRole('ADMIN')")
    public PageResult<RoomView> all(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return service.allRooms(page, size);
    }
    @GetMapping("/api/admin/statistics/occupancy") @PreAuthorize("hasRole('ADMIN')")
    public List<Occupancy> occupancy(@RequestParam(required=false) Long hotelId, @RequestParam LocalDate from,
                                     @RequestParam LocalDate to) { return service.occupancy(hotelId, from, to); }
}
