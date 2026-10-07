package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.*;

/** FR-RD01..04: production sampler/renderer/HTTP, disposable synthetic sequence fixtures only. */
class ReadinessDiagnosticsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void initialCacheIsExplicitlyUnsampled() throws Exception {
        final var j = JSON.readTree(ClusterNodeMain.readinessBody(false, -1, -1, 2, -1, 5000, null, null));
        assertFalse(j.get("ready").asBoolean());
        assertEquals("not_sampled", j.get("peerObservationStatus").asText());
        for (String k : List.of("observedPeerCount", "unavailablePeerCount", "observedLag",
            "withinLagTolerance", "caughtUpToObservedSequence", "sampleCompletedAtEpochMs")) {
            assertTrue(j.get(k).isNull(), k);
        }
    }

    @Test void coldHandlerReturns503AndUnknownComparisons() throws Exception {
        try (var e = new Endpoint(new MatchingEngineClusteredService(), List.of("localhost", "["))) {
            var j = e.await("not_sampled", 503);
            assertEquals("not started", j.get("reason").asText());
            assertEquals(1, j.get("configuredPeerCount").asInt());
            assertFalse(j.get("localSequenceAvailable").asBoolean());
            assertTrue(j.get("caughtUpToObservedSequence").isNull());
        }
    }

    @Test void noConfiguredPeersIsReadyWithNoComparison() throws Exception {
        try (var e = new Endpoint(service(100, 100), List.of("localhost"))) {
            var j = e.await("none_configured", 200);
            assertEquals(0, j.get("configuredPeerCount").asInt());
            assertEquals(0, j.get("observedPeerCount").asInt());
            assertEquals(-1, j.get("maxPeerApplied").asLong());
            unknown(j);
        }
    }

    @Test void configuredButUnavailableIsReadyWithNoComparison() throws Exception {
        try (var e = new Endpoint(service(100, 100), List.of("localhost", "[", "["))) {
            var j = e.await("unavailable", 200);
            assertEquals(2, j.get("unavailablePeerCount").asInt());
            assertEquals(0, j.get("observedPeerCount").asInt());
            unknown(j);
        }
    }

    @Test void partialPeerCoverageDoesNotHideMissingPeers() throws Exception {
        try (var e = new Endpoint(service(100, 100), List.of("localhost", "localhost", "["))) {
            var j = e.await("partial", 200);
            assertEquals(2, j.get("configuredPeerCount").asInt());
            assertEquals(1, j.get("observedPeerCount").asInt());
            assertEquals(1, j.get("unavailablePeerCount").asInt());
            assertTrue(j.get("caughtUpToObservedSequence").asBoolean());
            assertEquals("maximum_observed_applied_sequence", j.get("comparisonScope").asText());
        }
    }

    @Test void allObservedAtSameSequenceOnlyClaimsSequenceCatchup() throws Exception {
        try (var e = new Endpoint(service(100, 100), List.of("localhost", "localhost", "localhost"))) {
            var j = e.await("complete", 200);
            assertEquals(2, j.get("observedPeerCount").asInt());
            assertEquals(0, j.get("observedLag").asLong());
            assertTrue(j.get("caughtUpToObservedSequence").asBoolean());
            assertTrue(j.get("withinLagTolerance").asBoolean());
            for (String k : List.of("converged", "quorum", "leaderEligible", "bookEqual")) assertFalse(j.has(k));
        }
    }

    @Test void readyWithinToleranceDoesNotClaimCaughtUp() throws Exception {
        try (var e = new Endpoint(service(100, 5100), List.of("localhost", "localhost"))) {
            var j = e.await("complete", 200);
            assertEquals(5000, j.get("observedLag").asLong());
            assertEquals(5000, j.get("configuredMaxLag").asLong());
            assertTrue(j.get("withinLagTolerance").asBoolean());
            assertFalse(j.get("caughtUpToObservedSequence").asBoolean());
        }
    }

    @Test void beyondToleranceRetains503() throws Exception {
        try (var e = new Endpoint(service(100, 5101), List.of("localhost", "localhost"))) {
            var j = e.await("complete", 503);
            assertEquals(5001, j.get("observedLag").asLong());
            assertFalse(j.get("withinLagTolerance").asBoolean());
            assertFalse(j.get("caughtUpToObservedSequence").asBoolean());
        }
    }

    @Test void aheadOfObservedPeerHasZeroLag() throws Exception {
        try (var e = new Endpoint(service(200, 100), List.of("localhost", "localhost"))) {
            var j = e.await("complete", 200);
            assertEquals(0, j.get("observedLag").asLong());
            assertTrue(j.get("caughtUpToObservedSequence").asBoolean());
        }
    }

    @Test void invalidLocalSequenceIsUnknownEvenWithObservedPeer() throws Exception {
        try (var e = new Endpoint(service(-1, 0), List.of("localhost", "localhost"))) {
            var j = e.await("complete", 200); // legacy routing remains within 5000
            assertFalse(j.get("localSequenceAvailable").asBoolean());
            unknown(j);
        }
    }

    @Test void invalidPeerSequenceIsUnavailableRatherThanCaughtUp() throws Exception {
        try (var e = new Endpoint(service(100, -1), List.of("localhost", "localhost"))) {
            var j = e.await("unavailable", 200);
            assertEquals(0, j.get("observedPeerCount").asInt());
            unknown(j);
        }
    }

    @Test void malformedNegativeOverflowAndMissingPeerReadsAreUnavailable() throws Exception {
        for (String body : List.of("{}", "{\"applied\":-1}", "{\"applied\":oops}",
            "{\"applied\":9223372036854775808}")) {
            try (var peer = new Peer(body)) {
                var sample = ClusterNodeMain.sampleReadiness(peer.port(), 0,
                    List.of("ignored", "127.0.0.1"), service(100, 100).service(), 5000);
                var j = JSON.readTree(sample.body());
                assertTrue(sample.ready());
                assertEquals("unavailable", j.get("peerObservationStatus").asText());
                unknown(j);
            }
        }
    }

    @Test void invalidJsonNumbersCannotBecomeDiagnosticObservations() throws Exception {
        for (String body : List.of("{\"applied\":1.5}", "{\"applied\":\"100\"}",
            "{\"applied\":100}junk", "{\"applied\":100,\"applied\":200}",
            "{\"applied\":100,\"started\":false}")) {
            try (var peer = new Peer(body)) {
                var sample = ClusterNodeMain.sampleReadiness(peer.port(), 0,
                    List.of("ignored", "127.0.0.1"), service(100, 100).service(), 5000);
                var j = JSON.readTree(sample.body());
                assertEquals(0, j.get("observedPeerCount").asInt(), body);
                assertTrue(j.get("maxObservedPeerApplied").isNull());
                unknown(j);
                // The old regex routing arm still sees integer prefixes in some invalid bodies.
                var matcher = java.util.regex.Pattern.compile("\"applied\":(-?\\d+)").matcher(body);
                long legacy = matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
                assertEquals(legacy, j.get("maxPeerApplied").asLong());
                assertEquals(legacy < 0 || 100 >= legacy - 5000, sample.ready());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"false\"", "\"true\"", "0", "1", "[]", "{}"})
    void presentInvalidStartedCannotCertifyObservation(String started) throws Exception {
        try (var peer = new Peer("{\"applied\":100,\"started\":" + started + "}")) {
            var sample = ClusterNodeMain.sampleReadiness(peer.port(), 0,
                List.of("ignored", "127.0.0.1"), service(100, 100).service(), 5000);
            var j = JSON.readTree(sample.body());
            assertTrue(sample.ready(), "legacy routing remains true for applied100");
            assertEquals(100, j.get("maxPeerApplied").asLong());
            assertEquals(0, j.get("observedPeerCount").asInt(), started);
            assertEquals(1, j.get("unavailablePeerCount").asInt());
            assertEquals("unavailable", j.get("peerObservationStatus").asText());
            assertTrue(j.get("maxObservedPeerApplied").isNull());
            unknown(j);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "true", "false"})
    void startedCompatibilityUsesOnlyMissingOrBooleanTrue(String started) throws Exception {
        String field = started.equals("missing") ? "" : ",\"started\":" + started;
        try (var peer = new Peer("{\"applied\":100" + field + "}")) {
            var sample = ClusterNodeMain.sampleReadiness(peer.port(), 0,
                List.of("ignored", "127.0.0.1"), service(100, 100).service(), 5000);
            var j = JSON.readTree(sample.body());
            assertTrue(sample.ready(), "legacy routing ignores started");
            assertEquals(100, j.get("maxPeerApplied").asLong());
            if (started.equals("false")) {
                assertEquals(0, j.get("observedPeerCount").asInt());
                unknown(j);
            } else {
                assertEquals(1, j.get("observedPeerCount").asInt());
                assertEquals(100, j.get("maxObservedPeerApplied").asLong());
                assertTrue(j.get("caughtUpToObservedSequence").asBoolean());
                assertTrue(j.get("withinLagTolerance").asBoolean());
            }
        }
    }

    @Test void validSpacedJsonIsObservedWithoutChangingLegacyRoutingParse() throws Exception {
        try (var peer = new Peer("{\"applied\": 10000}")) {
            var sample = ClusterNodeMain.sampleReadiness(peer.port(), 0,
                List.of("ignored", "127.0.0.1"), service(100, 100).service(), 5000);
            var j = JSON.readTree(sample.body());
            assertTrue(sample.ready()); // unchanged legacy regex ignores the spaced number
            assertEquals(-1, j.get("maxPeerApplied").asLong());
            assertEquals(10000, j.get("maxObservedPeerApplied").asLong());
            assertEquals(9900, j.get("observedLag").asLong());
            assertFalse(j.get("withinLagTolerance").asBoolean());
        }
    }

    @Test void actualConnectionRefusalIsUnavailable() throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        var sample = ClusterNodeMain.sampleReadiness(port, 0,
            List.of("ignored", "127.0.0.1"), service(100, 100).service(), 5000);
        var j = JSON.readTree(sample.body());
        assertTrue(sample.ready());
        assertEquals("unavailable", j.get("peerObservationStatus").asText());
        unknown(j);
    }

    @Test void measuredZeroPeerSequenceIsAnObservation() throws Exception {
        try (var peer = new Peer("{\"applied\":0}")) {
            var sample = ClusterNodeMain.sampleReadiness(peer.port(), 0,
                List.of("ignored", "127.0.0.1"), service(0, 0).service(), 0);
            var j = JSON.readTree(sample.body());
            assertEquals(1, j.get("observedPeerCount").asInt());
            assertTrue(j.get("caughtUpToObservedSequence").asBoolean());
        }
    }

    @Test void maximumIsTakenAcrossDifferentActualPeerResponses() throws Exception {
        try (var a = new Peer("{\"applied\":100}")) {
            try (var b = new Peer("{\"applied\":200}", "::1", a.port())) {
                var sample = ClusterNodeMain.sampleReadiness(a.port(), 0,
                    List.of("ignored", "127.0.0.1", "[::1]", "["), service(150, 150).service(), 5000);
                var j = JSON.readTree(sample.body());
                assertEquals(3, j.get("configuredPeerCount").asInt());
                assertEquals(2, j.get("observedPeerCount").asInt());
                assertEquals("partial", j.get("peerObservationStatus").asText());
                assertEquals(200, j.get("maxObservedPeerApplied").asLong());
                assertEquals(50, j.get("observedLag").asLong());
                assertTrue(j.get("withinLagTolerance").asBoolean());
                assertFalse(j.get("caughtUpToObservedSequence").asBoolean());
            }
        }
    }

    @Test void negativeToleranceCannotClaimValidToleranceComparison() throws Exception {
        var j = JSON.readTree(ClusterNodeMain.readinessBody(true, 100, 100, 1, 1, -1, 1L, 2L));
        assertTrue(j.get("withinLagTolerance").isNull());
        assertTrue(j.get("caughtUpToObservedSequence").asBoolean());
        assertFalse(j.get("ready").asBoolean()); // exact legacy expression
    }

    @Test void readinessBooleanMatchesLegacyAcrossBoundaries() throws Exception {
        for (long mine : new long[]{-1, 0, 1, 4999, 5000, 5001, Long.MAX_VALUE}) {
            for (long peer : new long[]{-1, 0, 1, 5000, 5001, Long.MAX_VALUE}) {
                for (long tolerance : new long[]{-1, 0, 5000, Long.MAX_VALUE}) {
                    var j = JSON.readTree(ClusterNodeMain.readinessBody(true, mine, peer,
                        1, peer < 0 ? 0 : 1, tolerance, 1L, 2L));
                    assertEquals(peer < 0 || mine >= peer - tolerance, j.get("ready").asBoolean());
                }
            }
        }
    }

    private static void unknown(JsonNode j) {
        for (String k : List.of("observedLag", "withinLagTolerance", "caughtUpToObservedSequence")) {
            assertTrue(j.get(k).isNull(), k);
        }
    }

    // Independently controlled synthetic local sequence and disposable peer /health response.
    // No cluster/book identity or coherence is claimed from this loopback fixture.
    private record Fixture(MatchingEngineClusteredService service, long peer) {}
    private static Fixture service(long mine, long peer) throws Exception {
        var service = new MatchingEngineClusteredService();
        service.initEngine(16, 16);
        var applied = MatchingEngineClusteredService.class.getDeclaredField("appliedSeq");
        applied.setAccessible(true); applied.setLong(service, mine);
        return new Fixture(service, peer);
    }

    private static final class Endpoint implements AutoCloseable {
        final HttpServer server;
        final Thread sampler;
        final Long expectedPeer;
        Endpoint(Fixture fixture, List<String> hosts) throws Exception {
            this(fixture.service(), hosts, fixture.peer());
        }
        Endpoint(MatchingEngineClusteredService service, List<String> hosts) throws Exception {
            this(service, hosts, null);
        }
        private Endpoint(MatchingEngineClusteredService service, List<String> hosts, Long peer) throws Exception {
            expectedPeer = peer;
            final Set<Thread> before = Thread.getAllStackTraces().keySet();
            final var factory = ClusterNodeMain.class.getDeclaredMethod("healthServer", int.class,
                int.class, List.class, MatchingEngineClusteredService.class, ClusterRecon.class);
            factory.setAccessible(true);
            // Reserve an ephemeral port briefly: production sampler uses its configured port,
            // so port zero would prevent it reaching the disposable /health handler.
            int port;
            try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            server = (HttpServer)factory.invoke(null, port, 0, hosts, service, null);
            if (peer != null) {
                server.removeContext("/health");
                server.createContext("/health", e -> {
                    byte[] bytes = ("{\"applied\":" + peer + "}").getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200, bytes.length);
                    try (var out = e.getResponseBody()) { out.write(bytes); }
                });
            }
            sampler = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> !before.contains(t) && t.getName().equals("health-ready-sampler"))
                .findFirst().orElseThrow();
        }
        JsonNode await(String status, int code) throws Exception {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                var conn = (HttpURLConnection)URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/ready").toURL().openConnection();
                conn.setConnectTimeout(1000); conn.setReadTimeout(1000);
                try {
                    int actualCode = conn.getResponseCode();
                    try (var in = actualCode < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                        var j = JSON.readTree(in);
                        if (j.has("peerObservationStatus") && status.equals(j.get("peerObservationStatus").asText())
                            && !j.get("sampleCompletedAtEpochMs").isNull()
                            && (expectedPeer == null || !j.has("maxPeerApplied")
                                || j.get("maxPeerApplied").asLong() == Math.max(-1, expectedPeer)
                                || status.equals("none_configured") || status.equals("unavailable"))) {
                            assertEquals(code, actualCode);
                            assertEquals(code == 200, j.get("ready").asBoolean());
                            assertTrue(j.get("sampleCompletedAtEpochMs").asLong() >= j.get("sampleStartedAtEpochMs").asLong());
                            System.out.println("READY_FIXTURE=" + j);
                            return j;
                        }
                    }
                } finally { conn.disconnect(); }
                Thread.sleep(10);
            }
            fail("production /ready did not expose " + status);
            return null;
        }
        @Override public void close() throws Exception {
            sampler.interrupt(); sampler.join(2000);
            server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow();
            assertFalse(sampler.isAlive(), "owned sampler stopped");
        }
    }

    private static final class Peer implements AutoCloseable {
        final HttpServer server;
        Peer(String body) throws Exception { this(body, "127.0.0.1", 0); }
        Peer(String body, String host, int port) throws Exception {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/health", e -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(200, bytes.length);
                try (var out = e.getResponseBody()) { out.write(bytes); }
            });
            server.start();
        }
        int port() { return server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
