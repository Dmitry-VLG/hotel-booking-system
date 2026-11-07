package com.example.hotel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.example.hotel", "com.example.common"})
public class HotelApplication {
    public static void main(String[] args) {
        SpringApplication.run(HotelApplication.class, args);
    }
}
