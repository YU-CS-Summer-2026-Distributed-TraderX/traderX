package finos.traderx.ordermatcher.lmax;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Startup-only client for authenticated recovery-bundle transfer from the current primary. */
public final class AeronBootstrapBundleClient {
    private final HttpClient http;
    private final Path journalDir;
    private final byte[] secret;
    private final long timeoutMs;

    public AeronBootstrapBundleClient(Path journalDir, byte[] secret, long timeoutMs) {
        this.journalDir = journalDir;
        this.secret = secret.clone();
        this.timeoutMs = Math.max(1L, timeoutMs);
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(this.timeoutMs))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public AeronBootstrapInstaller.InstallResult fetchAndInstall(
        String baseUrl, String localPeerId, long epoch, LongSupplier negotiatedEpoch
    ) {
        long correlation = positiveCorrelation();
        Path download = journalDir.toAbsolutePath().resolveSibling(
            journalDir.getFileName() + ".bootstrap-download-" + correlation + ".tmp");
        long deadline = System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(3L * timeoutMs);
        RuntimeException lastFailure = null;
        boolean downloaded = false;
        try {
            while (System.nanoTime() < deadline) {
                if (negotiatedEpoch.getAsLong() != epoch) {
                    throw new IllegalStateException("leader epoch changed during bundle download");
                }
                Files.deleteIfExists(download);
                long issuedAt = System.currentTimeMillis();
                String tag = AeronBootstrapAuth.requestTag(
                    secret, localPeerId, epoch, correlation, issuedAt);
                URI uri = URI.create(baseUrl + AeronBootstrapBundleController.PATH
                    + "?epoch=" + epoch + "&correlation=" + correlation
                    + "&issuedAt=" + issuedAt);
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header(AeronBootstrapBundleController.PEER_HEADER, localPeerId)
                    .header(AeronBootstrapBundleController.AUTH_HEADER, tag)
                    .GET()
                    .build();
                try {
                    HttpResponse<Path> response = http.send(request,
                        HttpResponse.BodyHandlers.ofFile(download,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
                    if (response.statusCode() == 200) {
                        downloaded = true;
                        break;
                    }
                    if (response.statusCode() == 403) {
                        throw new IllegalStateException(
                            "bootstrap primary rejected peer authentication");
                    }
                    lastFailure = new IllegalStateException(
                        "bootstrap primary returned HTTP " + response.statusCode());
                } catch (IOException ex) {
                    lastFailure = new IllegalStateException(
                        "bootstrap primary is not accepting transfers yet", ex);
                }
                java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
            }
            if (!downloaded || !Files.isRegularFile(download)) {
                throw new IllegalStateException(
                    "bootstrap bundle transfer timed out", lastFailure);
            }
            return new AeronBootstrapInstaller(journalDir).install(download, secret, epoch,
                correlation, () -> negotiatedEpoch.getAsLong() == epoch);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("bootstrap bundle download interrupted", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("bootstrap bundle transfer failed", ex);
        } finally {
            try {
                Files.deleteIfExists(download);
            } catch (IOException ignored) {
                // Startup cleanup handles an inert partial download; it is never an active generation.
            }
        }
    }

    private static long positiveCorrelation() {
        long value = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        return value == Long.MIN_VALUE ? 0L : Math.abs(value);
    }
}
