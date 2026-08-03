package finos.traderx.aeron;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sidecar's readiness contract, driven over real HTTP.
 *
 * <p>This endpoint is what Kubernetes uses to decide whether the pod may carry traffic, so its
 * failure modes matter more than its success one. {@code /healthz} is 200 ONLY when the SBE schema
 * matches AND both directories exist AND the archive directory is writable — each of those is
 * asserted by breaking it individually, because an endpoint that returns 200 while its archive
 * directory is read-only would let Kubernetes route to a member that cannot record anything.
 *
 * <p>Uses a real {@code HttpServer} on an ephemeral port and real temp directories. There is no
 * Aeron media driver here and none is needed: the readiness decision is made from config and the
 * filesystem, which is exactly the part a driver would obscure rather than reveal.
 */
class AeronSidecarHealthServerTest {

    private AeronArchiveSidecar.HealthServer server;
    private int port;

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void startWith(Path aeronDir, Path archiveDir, String expectedChecksum) throws IOException {
        port = freePort();
        AeronArchiveSidecar.Config config = new AeronArchiveSidecar.Config(
            aeronDir, archiveDir, port, "aeron:udp", "aeron:udp", "aeron:ipc",
            "aeron:udp", "aeron:udp", 1101, expectedChecksum);
        server = new AeronArchiveSidecar.HealthServer(port, config);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthzIs200WhenTheSchemaMatchesAndBothDirectoriesAreUsable(@TempDir Path tmp) throws Exception {
        Path aeron = Files.createDirectory(tmp.resolve("aeron"));
        Path archive = Files.createDirectory(tmp.resolve("archive"));
        startWith(aeron, archive, AeronArchiveSidecar.SCHEMA_CHECKSUM);

        HttpResponse<String> response = get("/healthz");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"UP\""), response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
    }

    /**
     * A foreign schema checksum is the fail-closed case the sidecar exists to enforce: replicating
     * between members that disagree about the wire format corrupts the archive rather than failing
     * loudly, so the pod must never be declared ready.
     */
    @Test
    void healthzIs503WhenTheSchemaChecksumIsForeign(@TempDir Path tmp) throws Exception {
        Path aeron = Files.createDirectory(tmp.resolve("aeron"));
        Path archive = Files.createDirectory(tmp.resolve("archive"));
        startWith(aeron, archive, "a-different-build");

        HttpResponse<String> response = get("/healthz");

        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"DOWN\""), response.body());
    }

    @Test
    void healthzIs503WhenTheArchiveDirectoryIsMissing(@TempDir Path tmp) throws Exception {
        Path aeron = Files.createDirectory(tmp.resolve("aeron"));
        startWith(aeron, tmp.resolve("archive-that-was-never-created"),
            AeronArchiveSidecar.SCHEMA_CHECKSUM);

        assertEquals(503, get("/healthz").statusCode());
    }

    @Test
    void healthzIs503WhenTheAeronDirectoryIsMissing(@TempDir Path tmp) throws Exception {
        Path archive = Files.createDirectory(tmp.resolve("archive"));
        startWith(tmp.resolve("aeron-that-was-never-created"), archive,
            AeronArchiveSidecar.SCHEMA_CHECKSUM);

        assertEquals(503, get("/healthz").statusCode());
    }

    /**
     * Both recording subscription ids appear in the body, and they read -1 until recording actually
     * starts. That -1 is the useful part: it distinguishes "ready and recording" from "ready but
     * recording nothing", which are otherwise identical from outside.
     */
    @Test
    void healthzReportsBothRecordingSubscriptionIds(@TempDir Path tmp) throws Exception {
        Path aeron = Files.createDirectory(tmp.resolve("aeron"));
        Path archive = Files.createDirectory(tmp.resolve("archive"));
        startWith(aeron, archive, AeronArchiveSidecar.SCHEMA_CHECKSUM);

        String before = get("/healthz").body();
        assertTrue(before.contains("\"inboundRecordingSubscriptionId\":-1"), before);
        assertTrue(before.contains("\"outboundRecordingSubscriptionId\":-1"), before);

        server.recordingSubscriptionIds(7L, 9L);

        String after = get("/healthz").body();
        assertTrue(after.contains("\"inboundRecordingSubscriptionId\":7"), after);
        assertTrue(after.contains("\"outboundRecordingSubscriptionId\":9"), after);
    }

    @Test
    void schemaIs200AndReportsAMatch(@TempDir Path tmp) throws Exception {
        startWith(tmp, tmp, AeronArchiveSidecar.SCHEMA_CHECKSUM);

        HttpResponse<String> response = get("/schema");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"matches\":true"), response.body());
        assertTrue(response.body().contains(AeronArchiveSidecar.SCHEMA_CHECKSUM), response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
    }

    /**
     * 409 rather than 503: the schema endpoint answers a question about identity, not liveness, and
     * a conflict is the honest word for two builds that disagree. It also reports the checksum this
     * build actually carries, so the operator can see which side is wrong without reading logs.
     */
    @Test
    void schemaIs409OnAMismatchAndStillReportsThisBuildsChecksum(@TempDir Path tmp) throws Exception {
        startWith(tmp, tmp, "a-different-build");

        HttpResponse<String> response = get("/schema");

        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("\"matches\":false"), response.body());
        assertTrue(response.body().contains(AeronArchiveSidecar.SCHEMA_CHECKSUM), response.body());
    }
}
