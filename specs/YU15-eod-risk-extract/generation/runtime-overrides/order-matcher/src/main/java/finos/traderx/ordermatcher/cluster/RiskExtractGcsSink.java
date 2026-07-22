package finos.traderx.ordermatcher.cluster;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Immutable object delivery to GCS (YU15). Same transport YU09's journal archiver already uses —
 * GCS's XML API is S3-compatible, so an HMAC key/secret authenticates an S3 client pointed at
 * storage.googleapis.com — including the two settings that are not optional there: chunked
 * encoding off and path-style addressing on, either of which surfaces as an opaque 403 if wrong.
 *
 * <p>Write-once is enforced by GCS itself: {@code x-goog-if-generation-match: 0} makes the upload
 * fail if the object already exists, so a redelivered EOD event can never quietly replace a
 * fixture the consumer has already scored against.
 */
final class RiskExtractGcsSink {

    private static final URI GCS_ENDPOINT = URI.create("https://storage.googleapis.com");

    private RiskExtractGcsSink() {
    }

    /** Uploads the cut and the fixture; returns the fixture's {@code gs://} URI. */
    static String put(final String sinkUri, final String key, final String cut, final String extract) {
        final URI uri = URI.create(sinkUri);
        final String bucket = uri.getHost();
        final String path = uri.getPath();
        final String prefix = path == null || path.isBlank() || path.equals("/")
            ? "" : path.substring(1) + "/";

        final String keyId = System.getenv("RISK_EXTRACT_GCS_HMAC_KEY_ID");
        final String secret = System.getenv("RISK_EXTRACT_GCS_HMAC_SECRET_ACCESS_KEY");
        if (keyId == null || keyId.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException("RISK_EXTRACT_SINK_URI is gs:// but "
                + "RISK_EXTRACT_GCS_HMAC_KEY_ID/RISK_EXTRACT_GCS_HMAC_SECRET_ACCESS_KEY are unset");
        }

        try (S3Client s3 = S3Client.builder()
                .endpointOverride(GCS_ENDPOINT)
                .region(Region.US_EAST_1)   // ignored for HMAC-signed requests against GCS
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(keyId, secret)))
                .serviceConfiguration(S3Configuration.builder()
                    .chunkedEncodingEnabled(false)
                    .pathStyleAccessEnabled(true)
                    .build())
                .build()) {
            upload(s3, bucket, prefix + key + ".cut", cut);
            upload(s3, bucket, prefix + key + ".csv", extract);
        }
        return "gs://" + bucket + "/" + prefix + key + ".csv";
    }

    private static void upload(final S3Client s3, final String bucket, final String key,
                               final String body) {
        s3.putObject(PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("text/csv")
                .overrideConfiguration(o -> o.putHeader("x-goog-if-generation-match", "0"))
                .build(),
            RequestBody.fromString(body, StandardCharsets.US_ASCII));
    }
}
