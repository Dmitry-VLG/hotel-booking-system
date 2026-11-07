package com.example.booking;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {
    @Bean
    @LoadBalanced
    RestClient.Builder hotelRestClientBuilder() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(1));
        return RestClient.builder().requestFactory(factory);
    }
}
