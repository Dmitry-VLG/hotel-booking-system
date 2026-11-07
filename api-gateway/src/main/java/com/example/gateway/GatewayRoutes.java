package com.example.gateway;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutes {
    @Bean
    RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route(
                        "booking",
                        r ->
                                r.path("/api/auth/**", "/api/users/**", "/api/bookings/**")
                                        .uri("lb://booking-service"))
                .route(
                        "hotel",
                        r ->
                                r.path("/api/hotels/**", "/api/rooms/**", "/api/admin/**")
                                        .uri("lb://hotel-service"))
                .build();
    }

    @Bean
    HttpClientCustomizer timeouts() {
        return client ->
                client.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                        .responseTimeout(Duration.ofSeconds(30));
    }
}
