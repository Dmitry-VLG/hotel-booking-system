package com.example.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Real processes and H2 databases; the HTTP proxy injects faults only in this test. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystemFlowIT {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<Process> processes = new ArrayList<>();
    private final Set<Integer> ports = new HashSet<>();
    private final ConcurrentLinkedQueue<Command> commands = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Faults> faults = new AtomicReference<>(Faults.NONE);
    private final LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(10);
    private final LocalDate end = start.plusDays(2);
    private final Path root = Path.of(System.getProperty("project.root"));
    private final Path output = root.resolve("integration-tests/target/e2e");
    private final String password = "E2e-" + UUID.randomUUID();
    private ExecutorService proxyThreads;
    private HttpServer proxy;
    private KeyPair keys;
    private int discoveryPort;
    private int gatewayPort;
    private int hotelPort;
    private int bookingPort;
    private String alice;
    private String bob;
    private String admin;

    private record Faults(boolean loseCommit, boolean rejectHold, boolean rejectRelease) {
        static final Faults NONE = new Faults(false, false, false);
    }

    private record Command(String bookingId, String reservationId, String operation) {}

    private record Result(int status, JsonNode body, HttpResponse<String> response) {}

    private record Fixture(long hotelId, List<Long> rooms) {}

    @BeforeAll
    void startApplications() throws Exception {
        Files.createDirectories(output);
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        Files.writeString(
                output.resolve("private.pem"), pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
        Files.writeString(
                output.resolve("public.pem"), pem("PUBLIC KEY", keys.getPublic().getEncoded()));
        discoveryPort = freePort();
        gatewayPort = freePort();
        hotelPort = freePort();
        bookingPort = freePort();
        proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ports.add(proxy.getAddress().getPort());
        proxyThreads = Executors.newVirtualThreadPerTaskExecutor();
        proxy.setExecutor(proxyThreads);
        proxy.createContext("/", this::forwardHotel);
        proxy.start();
        try {
            launch(
                    "discovery-server",
                    discoveryPort,
                    "--eureka.server.enable-self-preservation=false",
                    "--eureka.server.use-read-only-response-cache=false");
            healthy(discoveryPort);
            launch(
                    "hotel-service",
                    hotelPort,
                    "--eureka.instance.non-secure-port=" + proxy.getAddress().getPort());
            launch("booking-service", bookingPort);
            launch("api-gateway", gatewayPort);
            healthy(hotelPort);
            healthy(bookingPort);
            healthy(gatewayPort);
            await().atMost(Duration.ofSeconds(60))
                    .untilAsserted(
                            () -> {
                                var result = login("alice");
                                assertThat(result.status()).isEqualTo(200);
                                assertThat(result.body().path("accessToken").asText()).isNotBlank();
                            });
            alice = login("alice").body().path("accessToken").asText();
            bob = login("bob").body().path("accessToken").asText();
            admin = login("admin").body().path("accessToken").asText();
        } catch (Throwable failure) {
            stopApplications();
            throw failure;
        }
    }

    private int freePort() throws IOException {
        for (; ; ) {
            try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
                if (ports.add(socket.getLocalPort())) return socket.getLocalPort();
            }
        }
    }

    private void launch(String module, int port, String... additional) throws IOException {
        var args = new ArrayList<String>();
        String java =
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name").startsWith("Windows")
                                        ? "java.exe"
                                        : "java")
                        .toString();
        args.addAll(
                List.of(
                        java,
                        "-Xms64m",
                        "-Xmx256m",
                        "-XX:ActiveProcessorCount=2",
                        "-Dreactor.netty.ioWorkerCount=2",
                        "-jar",
                        root.resolve(
                                        module
                                                + "/target/"
                                                + module
                                                + "-"
                                                + System.getProperty("project.version")
                                                + ".jar")
                                .toString(),
                        "--server.port=" + port,
                        "--eureka.client.service-url.defaultZone=http://127.0.0.1:"
                                + discoveryPort
                                + "/eureka/",
                        "--eureka.client.registry-fetch-interval-seconds=1",
                        "--eureka.client.initial-instance-info-replication-interval-seconds=1",
                        "--eureka.instance.lease-renewal-interval-in-seconds=1"));
        args.addAll(List.of(additional));
        var builder = new ProcessBuilder(args).directory(output.toFile());
        builder.environment().put("PUBLIC_KEY", output.resolve("public.pem").toUri().toString());
        builder.environment().put("PRIVATE_KEY", output.resolve("private.pem").toUri().toString());
        builder.environment().put("DEMO_ENABLED", "true");
        builder.environment().put("DEMO_ADMIN_PASSWORD", password);
        builder.environment().put("DEMO_USER_PASSWORD", password);
        builder.redirectErrorStream(true).redirectOutput(output.resolve(module + ".log").toFile());
        processes.add(builder.start());
    }

    private void healthy(int port) {
        await().atMost(Duration.ofSeconds(90))
                .ignoreExceptionsMatching(
                        error ->
                                error instanceof java.net.ConnectException
                                        || error instanceof java.net.http.HttpTimeoutException)
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(
                        () -> {
                            assertThat(processes).allMatch(Process::isAlive);
                            var response =
                                    request(port, "GET", "/actuator/health", null, null, null);
                            assertThat(response.status()).isEqualTo(200);
                            assertThat(response.body().path("status").asText()).isEqualTo("UP");
                        });
    }

    @AfterEach
    void clearFaults() {
        faults.set(Faults.NONE);
    }

    @BeforeEach
    void warmInventoryAfterPreviousFaults() throws Exception {
        long room = fixture(1).rooms().getFirst();
        // Ten successful protected calls replace the complete ten-call sliding
        // window, including when the preceding scenario left the circuit closed
        // but with recorded failures. A single successful booking is not enough.
        for (int i = 0; i < 5; i++) {
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () -> {
                                var result = create(manual(room), UUID.randomUUID().toString());
                                assertThat(result.status()).isEqualTo(201);
                                assertThat(
                                                request(
                                                                gatewayPort,
                                                                "DELETE",
                                                                "/api/bookings/"
                                                                        + result.body()
                                                                                .path("id")
                                                                                .asText(),
                                                                alice,
                                                                null,
                                                                null)
                                                        .status())
                                        .isEqualTo(204);
                            });
        }
    }

    @AfterAll
    void stopApplications() throws Exception {
        for (Process process : processes.reversed()) {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }
        if (proxy != null) proxy.stop(0);
        if (proxyThreads != null) proxyThreads.shutdownNow();
        Files.deleteIfExists(output.resolve("private.pem"));
        Files.deleteIfExists(output.resolve("public.pem"));
    }

    private void forwardHotel(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().toString();
            String operation = path.substring(path.lastIndexOf('/') + 1);
            if (operation.equals("confirm-availability")
                    || operation.equals("commit")
                    || operation.equals("release")) {
                JsonNode data = mapper.readTree(body);
                commands.add(
                        new Command(
                                data.path("bookingId").asText(),
                                data.path("reservationId").asText(),
                                operation));
            }
            Faults fault = faults.get();
            if ((fault.rejectHold() && operation.equals("confirm-availability"))
                    || (fault.rejectRelease() && operation.equals("release"))) {
                respond(
                        exchange,
                        503,
                        "{\"status\":503,\"code\":\"INTERNAL_ERROR\"}"
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }
            var builder =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + hotelPort + path))
                            .timeout(Duration.ofSeconds(5))
                            .method(
                                    exchange.getRequestMethod(),
                                    HttpRequest.BodyPublishers.ofByteArray(body));
            for (String name : List.of("Authorization", "Content-Type", "X-Trace-Id")) {
                String value = exchange.getRequestHeaders().getFirst(name);
                if (value != null) builder.header(name, value);
            }
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (fault.loseCommit() && operation.equals("commit")) {
                // Hotel has committed the transaction, but Booking's real read timeout expires.
                Thread.sleep(1800);
                return;
            }
            response.headers()
                    .firstValue("Allow")
                    .ifPresent(value -> exchange.getResponseHeaders().set("Allow", value));
            respond(exchange, response.statusCode(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private Result request(
            int port, String method, String path, String token, Object body, String key)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(35));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        builder.header("Content-Type", "application/json");
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        var response =
                client.send(
                        builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json =
                response.body().isBlank() ? mapper.nullNode() : mapper.readTree(response.body());
        return new Result(response.statusCode(), json, response);
    }

    private Result login(String username) throws Exception {
        return request(
                gatewayPort,
                "POST",
                "/api/auth/login",
                null,
                Map.of("username", username, "password", password),
                null);
    }

    private Fixture fixture(int roomCount) throws Exception {
        var hotel =
                request(
                        gatewayPort,
                        "POST",
                        "/api/hotels",
                        admin,
                        Map.of("name", "E2E " + UUID.randomUUID(), "address", "Test address"),
                        null);
        assertThat(hotel.status()).isEqualTo(201);
        long id = hotel.body().path("id").asLong();
        var rooms = new ArrayList<Long>();
        for (int i = 0; i < roomCount; i++) {
            var room =
                    request(
                            gatewayPort,
                            "POST",
                            "/api/rooms",
                            admin,
                            Map.of("hotelId", id, "number", "R" + i, "available", true),
                            null);
            assertThat(room.status()).isEqualTo(201);
            rooms.add(room.body().path("id").asLong());
        }
        return new Fixture(id, rooms);
    }

    private Map<String, Object> manual(long room) {
        return Map.of(
                "autoSelect",
                false,
                "roomId",
                room,
                "startDate",
                start.toString(),
                "endDate",
                end.toString());
    }

    private Result create(Object input, String key) throws Exception {
        return request(gatewayPort, "POST", "/api/bookings", alice, input, key);
    }

    private Result booking(String id) throws Exception {
        return request(gatewayPort, "GET", "/api/bookings/" + id, alice, null, null);
    }

    private Result reservation(String bookingId) throws Exception {
        String id =
                commands.stream()
                        .filter(c -> c.bookingId().equals(bookingId))
                        .findFirst()
                        .orElseThrow()
                        .reservationId();
        return request(
                hotelPort, "GET", "/internal/reservations/" + id, internalToken(), null, null);
    }

    private String internalToken() throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String header =
                encoder.encodeToString(
                        "{\"alg\":\"RS256\",\"kid\":\"local-key\"}"
                                .getBytes(StandardCharsets.UTF_8));
        String payload =
                encoder.encodeToString(
                        mapper.writeValueAsBytes(
                                Map.of(
                                        "iss",
                                        "booking-internal",
                                        "sub",
                                        "booking-service",
                                        "aud",
                                        List.of("hotel-inventory"),
                                        "scope",
                                        "inventory.write",
                                        "iat",
                                        Instant.now().getEpochSecond(),
                                        "exp",
                                        Instant.now().plusSeconds(120).getEpochSecond())));
        String signed = header + "." + payload;
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keys.getPrivate());
        signature.update(signed.getBytes(StandardCharsets.US_ASCII));
        return signed + "." + encoder.encodeToString(signature.sign());
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN "
                + type
                + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
                + "\n-----END "
                + type
                + "-----\n";
    }

    @Test
    void successReplayOwnershipAndRealRelease() throws Exception {
        Fixture fixture = fixture(1);
        String key = UUID.randomUUID().toString();
        var first = create(manual(fixture.rooms().getFirst()), key);
        assertThat(first.status()).isEqualTo(201);
        String id = first.body().path("id").asText();
        assertThat(first.body().path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(create(manual(fixture.rooms().getFirst()), key).body()).isEqualTo(first.body());
        assertThat(reservation(id).body().path("state").asText()).isEqualTo("COMMITTED");
        assertThat(request(gatewayPort, "GET", "/api/bookings/" + id, bob, null, null).status())
                .isEqualTo(404);
        assertThat(
                        request(gatewayPort, "DELETE", "/api/bookings/" + id, alice, null, null)
                                .status())
                .isEqualTo(204);
        assertThat(booking(id).body().path("compensationStatus").asText()).isEqualTo("DONE");
        assertThat(reservation(id).body().path("state").asText()).isEqualTo("RELEASED");
    }

    @Test
    void twelveConcurrentBookingsHaveExactlyOneWinner() throws Exception {
        long room = fixture(1).rooms().getFirst();
        int count = 12;
        var gate = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<Result>>();
            for (int i = 0; i < count; i++)
                futures.add(
                        threads.submit(
                                () -> {
                                    gate.await();
                                    return create(manual(room), UUID.randomUUID().toString());
                                }));
            gate.countDown();
            var responses = new ArrayList<Result>();
            for (var future : futures) responses.add(future.get(40, TimeUnit.SECONDS));
            assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
            assertThat(responses).filteredOn(r -> r.status() == 409).hasSize(count - 1);
            for (Result response : responses) {
                String id =
                        response.body()
                                .path(response.status() == 201 ? "id" : "bookingId")
                                .asText();
                assertThat(booking(id).body().path("status").asText())
                        .isEqualTo(response.status() == 201 ? "CONFIRMED" : "CANCELLED");
            }
            var stored = request(gatewayPort, "GET", "/api/rooms/" + room, alice, null, null);
            assertThat(stored.body().path("timesBooked").asLong()).isEqualTo(1);
        }
    }

    @Test
    void missingRoomIsCancelledWithoutImpossibleCompensation() throws Exception {
        var response = create(manual(999999999L), UUID.randomUUID().toString());
        assertThat(response.status()).isEqualTo(404);
        String id = response.body().path("bookingId").asText();
        assertThat(booking(id).body().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(booking(id).body().path("compensationStatus").asText()).isEqualTo("NONE");
        assertThat(commands)
                .noneMatch(c -> c.bookingId().equals(id) && c.operation().equals("release"));
    }

    @Test
    void frameworkErrorsPassThroughGatewayWithCorrectStatusAndHeaders() throws Exception {
        var missing = request(gatewayPort, "GET", "/api/hotels/no/such/path", alice, null, null);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body().path("code").asText()).isEqualTo("NOT_FOUND");
        var method = request(gatewayPort, "PUT", "/api/hotels", alice, null, null);
        assertThat(method.status()).isEqualTo(405);
        assertThat(method.response().headers().firstValue("Allow").orElseThrow()).contains("GET");
        var unsupported =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:" + gatewayPort + "/api/hotels"))
                                .header("Authorization", "Bearer " + admin)
                                .header("Content-Type", "text/plain")
                                .POST(HttpRequest.BodyPublishers.ofString("wrong type"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(unsupported.statusCode()).isEqualTo(415);
        assertThat(mapper.readTree(unsupported.body()).path("code").asText())
                .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void lostCommitResponseAndReleaseOutageRecoverAutomatically() throws Exception {
        long room = fixture(1).rooms().getFirst();
        faults.set(new Faults(true, false, true));
        var response = create(manual(room), UUID.randomUUID().toString());
        assertThat(response.status()).isEqualTo(504);
        String id = response.body().path("bookingId").asText();
        assertThat(booking(id).body().path("compensationStatus").asText()).isEqualTo("REQUIRED");
        assertThat(reservation(id).body().path("state").asText()).isEqualTo("COMMITTED");
        assertThat(
                        commands.stream()
                                .filter(
                                        c ->
                                                c.bookingId().equals(id)
                                                        && c.operation().equals("commit"))
                                .count())
                .isGreaterThan(1);
        faults.set(Faults.NONE);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            assertThat(booking(id).body().path("compensationStatus").asText())
                                    .isEqualTo("DONE");
                            assertThat(reservation(id).body().path("state").asText())
                                    .isEqualTo("RELEASED");
                        });
        assertThat(
                        request(gatewayPort, "GET", "/api/rooms/" + room, alice, null, null)
                                .body()
                                .path("timesBooked")
                                .asLong())
                .isZero();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            // Allow the real circuit breaker to leave its open state before a new
                            // operation.
                            var result = create(manual(room), UUID.randomUUID().toString());
                            assertThat(result.status()).isEqualTo(201);
                        });
    }

    @Test
    void unavailableHotelCancelsAndExecutesRealRelease() throws Exception {
        long room = fixture(1).rooms().getFirst();
        faults.set(new Faults(false, true, false));
        var response = create(manual(room), UUID.randomUUID().toString());
        assertThat(response.status()).isEqualTo(503);
        String id = response.body().path("bookingId").asText();
        assertThat(booking(id).body().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(booking(id).body().path("compensationStatus").asText()).isEqualTo("DONE");
        assertThat(reservation(id).body().path("state").asText()).isEqualTo("RELEASED");
    }

    @Test
    void automaticSelectionBalancesNineBookingsAcrossThreeRooms() throws Exception {
        Fixture fixture = fixture(3);
        for (int i = 0; i < 9; i++) {
            var created =
                    create(
                            Map.of(
                                    "autoSelect",
                                    true,
                                    "hotelId",
                                    fixture.hotelId(),
                                    "startDate",
                                    start.toString(),
                                    "endDate",
                                    end.toString()),
                            UUID.randomUUID().toString());
            assertThat(created.status()).isEqualTo(201);
            String id = created.body().path("id").asText();
            assertThat(
                            request(gatewayPort, "DELETE", "/api/bookings/" + id, alice, null, null)
                                    .status())
                    .isEqualTo(204);
        }
        for (long room : fixture.rooms()) {
            assertThat(
                            request(gatewayPort, "GET", "/api/rooms/" + room, alice, null, null)
                                    .body()
                                    .path("timesBooked")
                                    .asLong())
                    .isEqualTo(3);
        }
    }

    @Test
    void realDiscoveryOpenApiAndAccessRestrictionsAreAvailable() throws Exception {
        var registry =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + discoveryPort
                                                        + "/eureka/apps"))
                                .header("Accept", "application/json")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(registry.statusCode()).isEqualTo(200);
        assertThat(registry.body()).contains("BOOKING-SERVICE", "HOTEL-SERVICE", "API-GATEWAY");
        assertThat(request(hotelPort, "GET", "/v3/api-docs", null, null, null).status())
                .isEqualTo(200);
        assertThat(request(bookingPort, "GET", "/v3/api-docs", null, null, null).status())
                .isEqualTo(200);
        assertThat(request(gatewayPort, "GET", "/api/users", alice, null, null).status())
                .isEqualTo(403);
        assertThat(request(gatewayPort, "GET", "/api/users", admin, null, null).status())
                .isEqualTo(200);
        assertThat(request(gatewayPort, "GET", "/api/hotels", null, null, null).status())
                .isEqualTo(401);
        assertThat(
                        request(gatewayPort, "POST", "/api/rooms/1/release", admin, Map.of(), null)
                                .status())
                .isEqualTo(403);
    }
}
