package com.example.hotel;

import com.example.common.ApiException;
import com.example.common.Checks;
import com.example.common.InventoryApi.Candidate;
import com.example.common.InventoryApi.CandidateQuery;
import com.example.common.PageResult;
import com.example.hotel.HotelDtos.*;
import com.example.hotel.HotelModel.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CatalogService {
    private final HotelRepository hotels;
    private final RoomRepository rooms;
    private final ReservationRepository reservations;
    private final EntityManager entityManager;
    private final Clock clock;

    private HotelEntity hotel(Long id) {
        return hotels.findByIdAndActiveTrue(id)
                .orElseThrow(() -> ApiException.missing("Отель не найден"));
    }

    private HotelEntity lockedHotel(Long id) {
        HotelEntity hotel =
                entityManager.find(HotelEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (hotel == null || !hotel.isActive()) throw ApiException.missing("Отель не найден");
        return hotel;
    }

    private RoomEntity lockedRoom(Long id) {
        var room = rooms.locked(id).orElseThrow(() -> ApiException.missing("Номер не найден"));
        if (!room.isActive()) throw ApiException.missing("Номер не найден");
        return room;
    }

    private HotelView hotelView(HotelEntity h) {
        return new HotelView(h.getId(), h.getName(), h.getAddress());
    }

    private RoomView roomView(RoomEntity r) {
        return new RoomView(
                r.getId(), r.getHotelId(), r.getNumber(), r.isAvailable(), r.getTimesBooked());
    }

    public HotelView createHotel(HotelInput input) {
        var h = new HotelEntity();
        h.setName(input.name().trim());
        h.setAddress(input.address().trim());
        return hotelView(hotels.save(h));
    }

    @Transactional(readOnly = true)
    public HotelView getHotel(Long id) {
        return hotelView(hotel(id));
    }

    @Transactional(readOnly = true)
    public PageResult<HotelView> hotels(String name, int page, int size) {
        Checks.page(page, size);
        var result = hotels.search(name, PageRequest.of(page, size, Sort.by("id")));
        return new PageResult<>(
                result.map(this::hotelView).getContent(), page, size, result.getTotalElements());
    }

    public HotelView updateHotel(Long id, HotelInput input) {
        var h = lockedHotel(id);
        h.setName(input.name().trim());
        h.setAddress(input.address().trim());
        return hotelView(h);
    }

    public void deleteHotel(Long id) {
        var h = lockedHotel(id);
        if (rooms.existsByHotelIdAndActiveTrue(id))
            throw ApiException.conflict("Сначала удалите номера отеля");
        h.setActive(false);
    }

    public RoomView createRoom(RoomInput input) {
        lockedHotel(input.hotelId());
        var r = new RoomEntity();
        r.setHotelId(input.hotelId());
        r.setNumber(input.number().trim());
        r.setAvailable(input.available());
        return roomView(rooms.save(r));
    }

    @Transactional(readOnly = true)
    public RoomView getRoom(Long id) {
        return roomView(
                rooms.findByIdAndActiveTrue(id)
                        .orElseThrow(() -> ApiException.missing("Номер не найден")));
    }

    public RoomView updateRoom(Long id, RoomPatch input) {
        var r = lockedRoom(id);
        if (input.number() != null) {
            if (input.number().isBlank())
                throw ApiException.bad("Номер комнаты не должен быть пустым");
            r.setNumber(input.number().trim());
        }
        if (input.available() != null) r.setAvailable(input.available());
        return roomView(r);
    }

    public void deleteRoom(Long id) {
        var r = lockedRoom(id);
        if (reservations.activeCount(id, clock.instant()) > 0)
            throw ApiException.conflict("У номера есть активные резервы");
        r.setActive(false);
    }

    @Transactional(readOnly = true)
    public PageResult<RoomView> allRooms(int page, int size) {
        Checks.page(page, size);
        var result = rooms.findByActiveTrue(PageRequest.of(page, size, Sort.by("id")));
        return new PageResult<>(
                result.map(this::roomView).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResult<Candidate> free(
            Long hotelId, LocalDate start, LocalDate end, boolean recommend, int page, int size) {
        return free(hotelId, start, end, recommend, page, size, List.of(0L));
    }

    @Transactional(readOnly = true)
    public PageResult<Candidate> candidates(CandidateQuery query) {
        var excluded = query.excludedIds().isEmpty() ? List.of(0L) : query.excludedIds();
        return free(query.hotelId(), query.startDate(), query.endDate(), true, 0, 100, excluded);
    }

    private PageResult<Candidate> free(
            Long hotelId,
            LocalDate start,
            LocalDate end,
            boolean recommend,
            int page,
            int size,
            Collection<Long> excluded) {
        Checks.newDates(start, end, clock);
        Checks.page(page, size);
        if (hotelId != null) hotel(hotelId);
        Sort sort =
                recommend ? Sort.by("timesBooked").ascending().and(Sort.by("id")) : Sort.by("id");
        var result =
                rooms.free(
                        hotelId,
                        start,
                        end,
                        clock.instant(),
                        excluded,
                        PageRequest.of(page, size, sort));
        var content =
                result.stream()
                        .map(
                                r ->
                                        new Candidate(
                                                r.getId(),
                                                r.getHotelId(),
                                                r.getNumber(),
                                                r.getTimesBooked()))
                        .toList();
        return new PageResult<>(content, page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<Occupancy> occupancy(Long hotelId, LocalDate from, LocalDate to) {
        Checks.dates(from, to);
        if (hotelId != null) hotel(hotelId);
        long capacity = ChronoUnit.DAYS.between(from, to);
        var totals = new HashMap<Long, Long>();
        for (var interval : reservations.committedInPeriod(hotelId, from, to)) {
            LocalDate left = interval.getStartDate().isAfter(from) ? interval.getStartDate() : from;
            LocalDate right = interval.getEndDate().isBefore(to) ? interval.getEndDate() : to;
            totals.merge(interval.getRoomId(), ChronoUnit.DAYS.between(left, right), Long::sum);
        }
        return rooms.activeForHotel(hotelId).stream()
                .map(
                        room -> {
                            long nights = totals.getOrDefault(room.getId(), 0L);
                            return new Occupancy(
                                    room.getId(),
                                    room.getNumber(),
                                    room.getTimesBooked(),
                                    nights,
                                    capacity,
                                    100.0 * nights / capacity);
                        })
                .toList();
    }
}
