package finos.traderx.ordermatcher.lmax;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AeronBootstrapBundleClientTest {
    private static final byte[] SECRET =
        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @TempDir Path tempDir;

    @Test
    void retriesUntilTheAuthenticatedPrimaryCanServeThenInstalls() throws Exception {
        Path primary = tempDir.resolve("primary");
        Files.createDirectories(primary);
        Files.writeString(primary.resolve("symbols.tab"), "0\tAAPL\n");
        AeronSnapshotBoundary boundary =
            new AeronSnapshotBoundary(7L, 1388L, 101_472L, 0x1234L, 77);
        SnapshotStore.Data source = new SnapshotStore.Data(
            9999L, 42, 3L, List.of(new long[] {0L, 12_300L}),
            List.of(), List.of(), -1L);
        AeronBootstrapBundleStore store = new AeronBootstrapBundleStore(primary);
        store.requestCapture();
        store.capture(boundary, source);

        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(AeronBootstrapBundleController.PATH, exchange -> {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            long correlation = Long.parseLong(query.get("correlation"));
            long issuedAt = Long.parseLong(query.get("issuedAt"));
            String peer = exchange.getRequestHeaders()
                .getFirst(AeronBootstrapBundleController.PEER_HEADER);
            String tag = exchange.getRequestHeaders()
                .getFirst(AeronBootstrapBundleController.AUTH_HEADER);
            assertThat(AeronBootstrapAuth.verifyRequest(SECRET, "order-matcher-1", 7L,
                correlation, issuedAt, tag, issuedAt)).isTrue();
            if (attempts.getAndIncrement() == 0) {
                exchange.sendResponseHeaders(503, -1L);
            } else {
                byte[] body = Files.readAllBytes(store.build(boundary, correlation, SECRET));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            Path follower = tempDir.resolve("follower");
            Files.createDirectory(follower);
            AeronBootstrapInstaller.InstallResult result =
                new AeronBootstrapBundleClient(follower, SECRET, 1000L)
                    .fetchAndInstall("http://127.0.0.1:" + server.getAddress().getPort(),
                        "order-matcher-1", 7L, () -> 7L);

            assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
            assertThat(result.checkpoint().inputSeq()).isEqualTo(1388L);
            assertThat(new JournalReader(follower).lastInputSeq()).isEqualTo(1388L);
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return values;
    }
}
