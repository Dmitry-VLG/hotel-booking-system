package com.example.booking;

import com.example.booking.BookingTransactions.Snapshot;
import com.example.common.InventoryApi;
import com.example.common.JwtSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HotelClientTest {
    HotelClient client;
    MockRestServiceServer server;
    Snapshot snapshot;
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @BeforeEach
    void setup() {
        var keys=BookingTest.KEYS;
        var tokens=new TokenService(JwtSupport.encoder(new FileSystemResource(keys[0]), new FileSystemResource(keys[1])), Clock.systemUTC());
        var builder=RestClient.builder(); server=MockRestServiceServer.bindTo(builder).build();
        client=new HotelClient(builder,tokens,Clock.systemUTC());
        var start=LocalDate.now(ZoneOffset.UTC).plusDays(10);
        snapshot=new Snapshot(UUID.randomUUID(),1L,1L,null,false,start,start.plusDays(2),"PENDING",
                UUID.randomUUID(),Instant.now().plusSeconds(15),"COMPENSATION","REQUIRED");
    }
    @Test
    void retriesServerErrorThreeTimesWithSameRequestId() {
        server.expect(ExpectedCount.times(3),requestTo("http://hotel-service/api/rooms/1/confirm-availability"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.requestId").value(snapshot.reservationId()+":hold"))
                .andRespond(withServerError());
        assertThatThrownBy(() -> client.hold(snapshot)).isInstanceOf(HotelClient.Failure.class)
                .satisfies(ex -> assertThat(((HotelClient.Failure)ex).getStatus()).isEqualTo(503));
        server.verify();
    }
    @Test
    void neverRetriesBusinessConflict() {
        server.expect(ExpectedCount.once(),requestTo("http://hotel-service/api/rooms/1/confirm-availability"))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> client.hold(snapshot)).isInstanceOf(HotelClient.Failure.class)
                .satisfies(ex -> assertThat(((HotelClient.Failure)ex).getStatus()).isEqualTo(409));
        server.verify();
    }
    @Test
    void lostResponseRetriesSameReservation() throws Exception {
        String uri="http://hotel-service/api/rooms/1/confirm-availability";
        server.expect(requestTo(uri)).andExpect(jsonPath("$.reservationId").value(snapshot.reservationId().toString()))
                .andRespond(request -> { throw new SocketTimeoutException("Simulated lost response"); });
        var result=new InventoryApi.Reservation(snapshot.reservationId(),snapshot.id(),1L,snapshot.startDate(),snapshot.endDate(),"HELD",Instant.now().plusSeconds(60));
        server.expect(requestTo(uri)).andExpect(jsonPath("$.reservationId").value(snapshot.reservationId().toString()))
                .andRespond(withSuccess(mapper.writeValueAsString(result),MediaType.APPLICATION_JSON));
        assertThat(client.hold(snapshot).reservationId()).isEqualTo(snapshot.reservationId());
        server.verify();
    }
    @Test
    void openCircuitStillAllowsCompensation() throws Exception {
        server.expect(ExpectedCount.times(5),requestTo("http://hotel-service/api/rooms/1/confirm-availability"))
                .andRespond(withServerError());
        var released=new InventoryApi.Reservation(snapshot.reservationId(),snapshot.id(),1L,snapshot.startDate(),snapshot.endDate(),"RELEASED",null);
        server.expect(requestTo("http://hotel-service/api/rooms/1/release"))
                .andRespond(withSuccess(mapper.writeValueAsString(released),MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.hold(snapshot)).isInstanceOf(HotelClient.Failure.class);
        assertThatThrownBy(() -> client.hold(snapshot)).isInstanceOf(HotelClient.Failure.class);
        assertThatThrownBy(() -> client.hold(snapshot)).isInstanceOf(HotelClient.Failure.class)
                .satisfies(ex -> assertThat(((HotelClient.Failure)ex).getCode()).isEqualTo("HOTEL_CIRCUIT_OPEN"));
        assertThat(client.release(snapshot).state()).isEqualTo("RELEASED");
        server.verify();
    }
}
