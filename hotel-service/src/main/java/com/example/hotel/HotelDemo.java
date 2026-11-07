package com.example.hotel;

import com.example.hotel.HotelDtos.HotelInput;
import com.example.hotel.HotelDtos.RoomInput;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="app.demo.enabled", havingValue="true")
public class HotelDemo implements CommandLineRunner {
    private final CatalogService catalog;
    @Override
    public void run(String... args) {
        var first = catalog.createHotel(new HotelInput("Учебный отель", "Центральная улица 1"));
        catalog.createRoom(new RoomInput(first.id(), "101", true));
        catalog.createRoom(new RoomInput(first.id(), "102", true));
        catalog.createRoom(new RoomInput(first.id(), "103", false));
        var second = catalog.createHotel(new HotelInput("Второй отель", "Парковая улица 2"));
        catalog.createRoom(new RoomInput(second.id(), "201", true));
    }
}
