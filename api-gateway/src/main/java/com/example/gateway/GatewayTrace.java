package com.example.gateway;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayTrace implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String id;
        try { id = UUID.fromString(exchange.getRequest().getHeaders().getFirst("X-Trace-Id")).toString(); }
        catch (Exception ignored) { id = UUID.randomUUID().toString(); }
        String trace = id;
        exchange.getAttributes().put("traceId", trace);
        exchange.getResponse().getHeaders().set("X-Trace-Id", trace);
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.set("X-Trace-Id", trace);
            headers.remove("X-User-Id");
            headers.remove("X-Role");
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }
}
