package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import finos.traderx.ordermatcher.auth.JwtAuthenticator;
import finos.traderx.ordermatcher.auth.JwtTokenMinter;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** FR-ERH-F15: the member readout of applied security controls, production handler, synthetic JWTs, no cluster. */
class MemberSecurityReadoutTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/risk/control/securities";
    private static final String SECRET = "gcf-synthetic-test-secret";
    private static final String ADMIN = new JwtTokenMinter(SECRET).mint("synthetic-admin", Set.of(), true, 3600);

    @Test void onlyAnAdminReadsIt() throws Exception {
        var trader = new JwtTokenMinter(SECRET).mint("trader", Set.of(101), false, 3600);
        try (var endpoint = new Endpoint(initialized())) {
            assertEquals(401, endpoint.get(PATH, null).status);
            assertEquals(401, endpoint.get(PATH, trader).status);
            assertEquals(404, endpoint.get(PATH + "/x", ADMIN).status);
        }
    }

    @Test void aColdMemberIsUnavailableNotEmpty() throws Exception {
        try (var endpoint = new Endpoint(new MatchingEngineClusteredService())) {
            var response = endpoint.get(PATH, ADMIN);
            assertEquals(503, response.status);
            assertFalse(response.body.has("securities"));
        }
    }

    @Test void eachRegisteredSecurityReportsTheControlAsApplied() throws Exception {
        var service = initialized();
        register(service, "MSFT", 1); register(service, "UST-BILL-20260910", 2);
        control(service, 0, true); control(service, 1, true); control(service, 1, false);   // the bill: enabled, then disabled
        try (var endpoint = new Endpoint(service)) {
            var response = endpoint.get(PATH, ADMIN);
            assertEquals(200, response.status);
            assertEquals(2, response.body.path("count").asInt());
            var rows = response.body.path("securities");
            assertEquals("MSFT", rows.get(0).path("ticker").asText()); assertTrue(rows.get(0).path("enabled").asBoolean());
            assertEquals("UST-BILL-20260910", rows.get(1).path("ticker").asText()); assertFalse(rows.get(1).path("enabled").asBoolean());
        }
    }

    private static MatchingEngineClusteredService initialized() {
        var service = new MatchingEngineClusteredService(); service.initEngine(32, 32); return service;
    }
    private static void register(MatchingEngineClusteredService service, String ticker, long requestId) {
        var buffer = new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
        new AeronReplicationCodec().encodeSymbolRegister(buffer, 0, requestId, ticker);
        service.onSessionMessage(null, 1L, buffer, 0, AeronReplicationCodec.SYMBOL_BYTES, null);
    }
    private static void control(MatchingEngineClusteredService service, int securityId, boolean enabled) {
        var event = new InputEvent(); event.type = InputEvent.TYPE_SECURITY_CONTROL;
        event.securityId = securityId; event.setControlEnabled(enabled); event.setControlVersion(1L); event.eventTimeMillis = 1L;
        var buffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
        new AeronReplicationCodec().encodeInput(buffer, 0, event, 0, 0, 0);
        service.onSessionMessage(null, 1L, buffer, 0, AeronReplicationCodec.INPUT_BYTES, null);
    }
    private record Response(int status, JsonNode body) {}
    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        Endpoint(MatchingEngineClusteredService service) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newSingleThreadExecutor());
            ClusterNodeMain.memberSecurityRoute(server, 0, service, new JwtAuthenticator(SECRET));
            server.start();
        }
        Response get(String path, String token) throws Exception {
            var builder = java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .timeout(java.time.Duration.ofSeconds(3)).GET();
            if (token != null) builder.header("Authorization", "Bearer " + token);
            try (var client = java.net.http.HttpClient.newHttpClient()) {
                var r = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                return new Response(r.statusCode(), r.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(r.body()));
            }
        }
        @Override public void close() { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
    }
}
