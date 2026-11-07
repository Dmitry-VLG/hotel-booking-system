package com.example.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayErrors implements ErrorWebExceptionHandler {
    private final ObjectMapper mapper;
    public GatewayErrors(ObjectMapper mapper) { this.mapper = mapper; }
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) return Mono.error(ex);
        int status = ex instanceof ResponseStatusException r ? r.getStatusCode().value() : 502;
        return GatewaySecurity.error(exchange, mapper, status);
    }
}
