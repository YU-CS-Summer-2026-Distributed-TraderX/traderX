package finos.traderx.ordermatcher.lmax;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Uploads closed journal segments (see Journaler#rotate) to GCS, off the journaler thread.
 * GCS's XML API is S3-compatible (the same interoperability mode YU07's tick-store DuckDB
 * connector uses), so an HMAC key/secret authenticates the same way — just an S3 client pointed
 * at storage.googleapis.com instead of AWS. Fire-and-forget: a slow or failing upload never
 * blocks the journaler thread, and a segment that fails to upload is simply left on disk (no data
 * loss — see archiveAsync).
 * // ponytail: AWS SDK v2 for correct SigV4 signing against GCS's XML API, instead of hand-rolling
 * // HMAC request signing. Upgrade path: swap for google-cloud-storage + Workload Identity if this
 * // state ever moves off HMAC keys.
 */
final class JournalArchiver implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JournalArchiver.class);
    private static final URI GCS_ENDPOINT = URI.create("https://storage.googleapis.com");

    private final boolean enabled;
    private final String bucket;
    private final String prefix;
    private final ExecutorService executor;
    private final S3Client client;

    JournalArchiver(boolean enabled, String bucketUri, String keyId, String secret) {
        boolean hasBucket = bucketUri != null && !bucketUri.isBlank();
        this.enabled = enabled && hasBucket;
        if (this.enabled) {
            URI uri = URI.create(bucketUri);
            this.bucket = uri.getHost();
            String path = uri.getPath();
            this.prefix = (path == null || path.isBlank() || path.equals("/")) ? "" : path.substring(1) + "/";
        } else {
            this.bucket = null;
            this.prefix = null;
        }
        boolean hasCreds = keyId != null && !keyId.isBlank() && secret != null && !secret.isBlank();
        if (this.enabled && hasCreds) {
            this.client = S3Client.builder()
                .endpointOverride(GCS_ENDPOINT)
                .region(Region.US_EAST_1)   // GCS's XML API ignores region for HMAC-signed requests
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, secret)))
                // GCS's S3-compatible XML API doesn't support the chunked-transfer-encoding +
                // trailing-checksum upload path AWS SDK v2 defaults to — that combination fails
                // with an opaque 403 Access Denied against GCS, not a checksum-specific error.
                // Disabling chunked encoding falls back to the classic signed-payload upload,
                // which GCS's interoperability layer does support.
                // GCS's interoperability docs specify path-style requests
                // (storage.googleapis.com/<bucket>/<key>); the SDK's default virtual-hosted style
                // (<bucket>.storage.googleapis.com/...) signs against a host GCS doesn't expect,
                // which also surfaces as 403 Access Denied rather than a DNS/host error.
                .serviceConfiguration(S3Configuration.builder()
                    .chunkedEncodingEnabled(false)
                    .pathStyleAccessEnabled(true)
                    .build())
                .build();
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "journal-archiver");
                t.setDaemon(true);
                return t;
            });
        } else {
            this.client = null;
            this.executor = null;
            if (this.enabled) {
                log.warn("Journal archival enabled but JOURNAL_ARCHIVE_GCS_HMAC_KEY_ID/"
                    + "JOURNAL_ARCHIVE_GCS_HMAC_SECRET_ACCESS_KEY are not set; rotated segments will "
                    + "accumulate on local disk (see order-matcher-journal-gcs-hmac Secret)");
            }
        }
    }

    /** Package-visible offline-test seam: injects an S3 client while retaining the production
     * daemon executor and archive/delete behavior. */
    JournalArchiver(String bucketUri, S3Client client) {
        URI uri = URI.create(bucketUri);
        this.enabled = true;
        this.bucket = uri.getHost();
        String path = uri.getPath();
        this.prefix = (path == null || path.isBlank() || path.equals("/")) ? "" : path.substring(1) + "/";
        this.client = client;
        this.executor = newArchiveExecutor();
    }

    private static ExecutorService newArchiveExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "journal-archiver");
            t.setDaemon(true);
            return t;
        });
    }

    /** Wait until every archive queued before this call has finished. Useful for orderly shutdown
     * and deterministic offline verification; it does not stop the executor. */
    boolean awaitIdle(long timeout, TimeUnit unit) throws Exception {
        if (executor == null) {
            return true;
        }
        executor.submit(() -> { }).get(timeout, unit);
        return true;
    }

    /** Fire-and-forget upload of a closed (already-rotated-away) journal segment. Runs on its own
     *  background thread, never the journaler thread, so a slow/failed upload can't stall hot-path
     *  journaling. On failure (or when uploads aren't configured) the local file is left in place —
     *  no data is ever lost to a bad upload, only local disk keeps growing until it's fixed. */
    void archiveAsync(Path segment) {
        // Watermark check runs on EVERY rotation, before the creds/client short-circuit: the
        // fail-safe (never delete an unconfirmed segment) means a dead upload leg — missing
        // secret or persistent failures — grows the journal volume without bound. Loki alert
        // target: "journal volume" + level.
        logDiskWatermark(segment);
        if (!enabled || client == null) {
            return;
        }
        executor.submit(() -> {
            String key = prefix + segment.getFileName();
            try {
                client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), segment);
                Files.deleteIfExists(segment);
                log.info("Archived journal segment {} -> gs://{}/{}", segment.getFileName(), bucket, key);
            } catch (software.amazon.awssdk.services.s3.model.S3Exception ex) {
                log.warn("Failed to archive journal segment {} (left in place on local disk): status={} "
                        + "awsErrorCode={} awsErrorMessage={} requestId={} extendedRequestId={} raw={}",
                    segment, ex.statusCode(),
                    ex.awsErrorDetails() != null ? ex.awsErrorDetails().errorCode() : null,
                    ex.awsErrorDetails() != null ? ex.awsErrorDetails().errorMessage() : null,
                    ex.requestId(), ex.extendedRequestId(),
                    ex.awsErrorDetails() != null && ex.awsErrorDetails().rawResponse() != null
                        ? ex.awsErrorDetails().rawResponse().asUtf8String() : null);
            } catch (Exception ex) {
                log.warn("Failed to archive journal segment {} (left in place on local disk): {}",
                    segment, ex.toString());
            }
        });
    }

    /** Rotation-cadence disk watermark on the journal volume (max one log per snapshot interval).
     * ponytail: fixed 80/90 thresholds + log-based alerting; make them env-tunable / wire a
     * Micrometer gauge if operations ever needs more than a Loki alert on these lines. */
    private static void logDiskWatermark(Path onVolume) {
        try {
            var store = Files.getFileStore(onVolume);
            int pct = usedPercent(store.getUsableSpace(), store.getTotalSpace());
            if (pct >= 90) {
                log.error("journal volume {}% full — rotated segments are accumulating faster than "
                    + "they are archived; journaling stops when the disk fills. Provision the "
                    + "archive secret or grow the PVC now.", pct);
            } else if (pct >= 80) {
                log.warn("journal volume {}% full — check that segment archival is keeping up "
                    + "(gs upload configured and succeeding).", pct);
            }
        } catch (Exception ex) {
            log.debug("journal volume watermark check failed: {}", ex.toString());
        }
    }

    /** Pure helper for the watermark math (testable without a full filesystem). */
    static int usedPercent(long usable, long total) {
        if (total <= 0) {
            return 0;
        }
        long used = total - Math.max(0, usable);
        return (int) ((used * 100) / total);
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdown();
        }
        if (client != null) {
            client.close();
        }
    }
}
