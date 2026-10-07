package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import finos.traderx.ordermatcher.auth.JwtAuthenticator;
import finos.traderx.ordermatcher.auth.JwtTokenMinter;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.MatchingEngine;
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.RiskMetrics;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** FR-MAR01/02/03: production HTTP handlers, real synthetic HS256 JWTs, no cluster. */
class MemberAccountReadoutTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/risk/control/accounts";
    private static final String SECRET = System.getenv().getOrDefault("AUTH_JWT_SECRET", "ri22-synthetic-test-secret");
    private static final String ADMIN = new JwtTokenMinter(SECRET).mint("synthetic-admin", Set.of(), true, 3600);

    @ParameterizedTest @ValueSource(strings = {"missing", "malformed", "wrong-secret", "expired", "non-admin"})
    void unauthorizedRequestsReadNoState(String kind) throws Exception {
        var service = mock(MatchingEngineClusteredService.class);
        String token = switch (kind) {
            case "missing" -> null;
            case "malformed" -> "not.a.jwt";
            case "wrong-secret" -> new JwtTokenMinter("synthetic-other-key").mint("ops", Set.of(), true, 3600);
            case "expired" -> new JwtTokenMinter(SECRET).mint("ops", Set.of(), true, -60);
            default -> new JwtTokenMinter(SECRET).mint("trader", Set.of(101), false, 3600);
        };
        try (var endpoint = new Endpoint(service, false)) {
            var response = endpoint.request("GET", PATH, token);
            assertEquals(401, response.status());
            assertEquals("admin JWT required", response.body().path("error").asText());
            assertFalse(response.body().has("accounts"));
            verifyNoInteractions(service);
        }
    }

    @Test void adminColdMemberIsUnavailableNotEmptyAndReconIsIndependent() throws Exception {
        try (var endpoint = new Endpoint(new MatchingEngineClusteredService(), true)) {
            var response = endpoint.request("GET", PATH, ADMIN);
            assertEquals(503, response.status());
            assertFalse(response.body().path("available").asBoolean(true));
            assertEquals(0, response.body().path("memberId").asInt(-1));
            assertFalse(response.body().has("accounts"));
            assertFalse(response.body().has("count"));
            assertEquals(503, endpoint.request("GET", "/recon/trades/blotter", ADMIN).status());
        }
    }

    @Test void initializedEmptyTableHasExplicitAvailableZeroWithRealSequence() throws Exception {
        var service = initialized();
        try (var endpoint = new Endpoint(service, true)) {
            var response = endpoint.request("GET", PATH, ADMIN);
            assertEquals(200, response.status());
            assertEquals("application/json", response.contentType());
            assertTrue(response.body().path("available").asBoolean());
            assertEquals(0, response.body().path("count").asInt(-1));
            assertTrue(response.body().path("accounts").isArray());
            assertEquals(0, response.body().path("accounts").size());
            assertEquals(service.appliedSeq(), response.body().path("appliedSeqBefore").asLong());
            assertEquals(service.appliedSeq(), response.body().path("appliedSeqAfter").asLong());
            assertScope(response.body());
            assertEquals(503, endpoint.request("GET", "/recon/trades/blotter", ADMIN).status());
        }
    }

    @Test void sequencedEnabledAndDisabledControlsArePresentButDirectoryOnlyIdIsAbsent() throws Exception {
        var service = initialized();
        applyAccount(service, 101, true, 1001);
        applyAccount(service, 202, false, 1002);
        final int directoryOnlyId = 303; // deliberately never submitted to the engine.
        try (var endpoint = new Endpoint(service, true)) {
            var response = endpoint.request("GET", PATH, ADMIN);
            assertEquals(200, response.status());
            assertEquals(Map.of(101L, true, 202L, false), accounts(response.body()));
            assertFalse(accounts(response.body()).containsKey((long) directoryOnlyId));
            assertEquals(2, response.body().path("count").asInt());
            for (var row : response.body().path("accounts")) {
                assertEquals(Set.of("accountId", "enabled"), fieldNames(row));
            }
            assertScope(response.body());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"})
    void nonGetMethodsAreRefusedBeforeStateAccess(String method) throws Exception {
        var service = mock(MatchingEngineClusteredService.class);
        try (var endpoint = new Endpoint(service, false)) {
            var response = endpoint.request(method, PATH, ADMIN);
            assertEquals(405, response.status());
            assertEquals("GET", response.allow());
            verifyNoInteractions(service);
        }
    }

    @Test void childPathIsNotTheAccountResource() throws Exception {
        var service = mock(MatchingEngineClusteredService.class);
        try (var endpoint = new Endpoint(service, false)) {
            assertEquals(404, endpoint.request("GET", PATH + "/101", ADMIN).status());
            verifyNoInteractions(service);
        }
    }

    @Test void nullRiskIsUnavailableEvenWhenEngineExists() throws Exception {
        var service = mock(MatchingEngineClusteredService.class);
        var engine = mock(MatchingEngine.class);
        when(service.engine()).thenReturn(engine);
        try (var endpoint = new Endpoint(service, false)) {
            var response = endpoint.request("GET", PATH, ADMIN);
            assertEquals(503, response.status());
            assertFalse(response.body().has("accounts"));
        }
    }

    @Test void movingSequenceReportsBothObservationsWithoutCertifyingACut() throws Exception {
        var service = mock(MatchingEngineClusteredService.class);
        var engine = mock(MatchingEngine.class);
        when(service.engine()).thenReturn(engine);
        when(engine.riskState()).thenReturn(smallRisk());
        when(service.appliedSeq()).thenReturn(7L, 9L);
        try (var endpoint = new Endpoint(service, false)) {
            var response = endpoint.request("GET", PATH, ADMIN);
            assertEquals(200, response.status());
            assertEquals(7, response.body().path("appliedSeqBefore").asLong());
            assertEquals(9, response.body().path("appliedSeqAfter").asLong());
            assertScope(response.body());
        }
    }

    @Test void tableReadFailureIsNotAnEmptySuccess() throws Exception {
        var service = mock(MatchingEngineClusteredService.class);
        var engine = mock(MatchingEngine.class);
        var risk = mock(BlpRiskState.class);
        when(service.engine()).thenReturn(engine);
        when(engine.riskState()).thenReturn(risk);
        when(risk.accountTuples()).thenThrow(new IllegalStateException("synthetic failure"));
        try (var endpoint = new Endpoint(service, false)) {
            var response = endpoint.request("GET", PATH, ADMIN);
            assertEquals(500, response.status());
            assertFalse(response.body().has("accounts"));
            assertFalse(response.body().toString().contains("synthetic failure"));
        }
    }

    @Test void existingSnapshotRestoreRepresentsEnabledAndDisabledAccounts() throws Exception {
        var source = initialized();
        applyAccount(source, 101, true, 1001);
        applyAccount(source, 202, false, 1002);
        List<byte[]> records = new ArrayList<>();
        source.writeSnapshot((buffer, offset, length) -> {
            byte[] record = new byte[length]; buffer.getBytes(offset, record); records.add(record);
        });
        assertTrue(records.size() > 2, "actual nonempty snapshot record stream");
        var restored = initialized();
        boolean done = false;
        for (byte[] record : records) done = restored.onSnapshotRecord(new UnsafeBuffer(record), 0);
        assertTrue(done, "existing restore reached END");
        try (var endpoint = new Endpoint(restored, true)) {
            var response = endpoint.request("GET", PATH, ADMIN);
            assertEquals(200, response.status());
            assertEquals(Map.of(101L, true, 202L, false), accounts(response.body()));
            assertEquals(source.appliedSeq(), response.body().path("appliedSeqBefore").asLong());
        }
    }

    @Test void sameHandlerReadsReplacementWithoutCachingOldAccounts() throws Exception {
        var service = initialized();
        applyAccount(service, 101, true, 1001);
        try (var endpoint = new Endpoint(service, true)) {
            assertEquals(Map.of(101L, true), accounts(endpoint.request("GET", PATH, ADMIN).body()));
            service.initEngine(32, 32);
            var response = endpoint.request("GET", PATH, ADMIN);
            assertEquals(200, response.status());
            assertTrue(response.body().path("available").asBoolean());
            assertEquals(Map.of(), accounts(response.body()));
        }
    }

    @Test void readoutIsBoundedByExistingOccupiedBackingSlots() throws Exception {
        var service = mock(MatchingEngineClusteredService.class);
        var engine = mock(MatchingEngine.class);
        var risk = smallRisk(); // maxAccounts2: existing implementation provides4 backing slots.
        for (int id = 1; id <= 4; id++) risk.putAccount(id, id != 4);
        when(service.engine()).thenReturn(engine);
        when(engine.riskState()).thenReturn(risk);
        try (var endpoint = new Endpoint(service, false)) {
            var response = endpoint.request("GET", PATH, ADMIN);
            assertEquals(200, response.status());
            assertEquals(4, response.body().path("count").asInt());
            assertEquals(Map.of(1L,true,2L,true,3L,true,4L,false), accounts(response.body()));
        }
    }

    @Test void getIgnoresMutationLookingQueryAndPreservesFullSnapshotBytes() throws Exception {
        var service = initialized();
        applyAccount(service, 101, true, 1001);
        applyAccount(service, 202, false, 1002);
        var before = snapshot(service);
        long seq = service.appliedSeq();
        try (var endpoint = new Endpoint(service, true)) {
            for (int i = 0; i < 3; i++) {
                var response = endpoint.request("GET", PATH + "?accountId=101&enabled=false", ADMIN);
                assertEquals(200, response.status());
                assertEquals(Map.of(101L,true,202L,false), accounts(response.body()));
            }
        }
        assertEquals(seq, service.appliedSeq());
        var after = snapshot(service);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) assertArrayEquals(before.get(i), after.get(i));
    }

    private static MatchingEngineClusteredService initialized() {
        var service = new MatchingEngineClusteredService(); service.initEngine(32, 32); return service;
    }
    private static BlpRiskState smallRisk() {
        return new BlpRiskState(2, 1, 8, 8, Long.MAX_VALUE / 4, 1000, Long.MAX_VALUE / 4,
            10000, new RiskMetrics());
    }
    private static void applyAccount(MatchingEngineClusteredService service, int id, boolean enabled, long time) {
        var event = new InputEvent(); event.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        event.accountId = id; event.setControlEnabled(enabled); event.setControlVersion(1L);
        event.eventTimeMillis = time;
        var buffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
        new AeronReplicationCodec().encodeInput(buffer, 0, event, 0, 0, 0);
        service.onSessionMessage(null, time, buffer, 0, AeronReplicationCodec.INPUT_BYTES, null);
    }
    private static List<byte[]> snapshot(MatchingEngineClusteredService service) {
        List<byte[]> records = new ArrayList<>();
        service.writeSnapshot((buffer, offset, length) -> {
            byte[] copy = new byte[length]; buffer.getBytes(offset, copy); records.add(copy);
        }); return records;
    }
    private static Set<String> fieldNames(JsonNode node) {
        Set<String> result = new java.util.HashSet<>(); node.fieldNames().forEachRemaining(result::add); return result;
    }
    private static Map<Long,Boolean> accounts(JsonNode body) {
        assertTrue(body.path("accounts").isArray());
        Map<Long,Boolean> rows = new java.util.HashMap<>();
        for (var row : body.path("accounts")) {
            assertTrue(row.path("accountId").isIntegralNumber());
            assertTrue(row.path("enabled").isBoolean());
            assertNull(rows.put(row.path("accountId").asLong(), row.path("enabled").asBoolean()));
        } return rows;
    }
    private static void assertScope(JsonNode body) {
        assertEquals("member-engine-risk-table", body.path("source").asText());
        assertEquals("sequential-non-atomic", body.path("sampling").asText());
        assertEquals("not-established", body.path("freshness").asText());
        assertEquals(Set.of("memberId", "source", "available", "sampling", "freshness",
            "appliedSeqBefore", "appliedSeqAfter", "count", "accounts"), fieldNames(body));
    }
    private record Response(int status, JsonNode body, String contentType, String allow) {}

    /** Real production context; full member health factory also verifies route installation. */
    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final List<Thread> samplers;
        Endpoint(MatchingEngineClusteredService service, boolean fullHealth) throws Exception {
            Set<Thread> before = Thread.getAllStackTraces().keySet();
            if (fullHealth) {
                assertNotNull(System.getenv("AUTH_JWT_SECRET"), "run full-handler fixture with its synthetic JWT secret");
                var factory = ClusterNodeMain.class.getDeclaredMethod("healthServer", int.class, int.class,
                    List.class, MatchingEngineClusteredService.class, ClusterRecon.class);
                factory.setAccessible(true);
                server = (HttpServer)factory.invoke(null, 0, 0, List.of("localhost"), service, null);
            } else {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(Executors.newSingleThreadExecutor());
                ClusterNodeMain.memberAccountRoute(server, 0, service, new JwtAuthenticator(SECRET));
                server.start();
            }
            samplers = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> !before.contains(t) && t.getName().equals("health-ready-sampler")).collect(Collectors.toList());
        }
        Response request(String method, String path, String token) throws Exception {
            // JDK HttpURLConnection does not support PATCH; use HttpClient for every method.
            var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
            var builder = java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + server.getAddress().getPort() + path)).timeout(java.time.Duration.ofSeconds(3))
                .method(method, java.net.http.HttpRequest.BodyPublishers.noBody());
            if (token != null) builder.header("Authorization", "Bearer " + token);
            try (client) {
                var response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                JsonNode body = response.body().isEmpty() ? JSON.createObjectNode()
                    : contentType.startsWith("application/json") ? JSON.readTree(response.body())
                    : JSON.createObjectNode().put("rawBody", response.body());
                return new Response(response.statusCode(), body, contentType,
                    response.headers().firstValue("Allow").orElse(""));
            }
        }
        @Override public void close() throws Exception {
            server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow();
            for (var sampler : samplers) { sampler.interrupt(); sampler.join(3000); assertFalse(sampler.isAlive()); }
        }
    }
}
