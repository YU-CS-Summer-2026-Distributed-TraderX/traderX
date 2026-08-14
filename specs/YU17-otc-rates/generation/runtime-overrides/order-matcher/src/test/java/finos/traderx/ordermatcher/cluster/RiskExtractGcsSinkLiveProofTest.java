package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live proof of the gs:// delivery path (brief 04 task 2), against the real bucket with the real
 * HMAC pair. Skipped unless the creds are in the environment — this is the standing proof you run
 * with creds exported, not a CI unit test:
 *
 * <pre>
 *   RISK_EXTRACT_GCS_HMAC_KEY_ID=... RISK_EXTRACT_GCS_HMAC_SECRET_ACCESS_KEY=... \
 *     ./gradlew test --tests RiskExtractGcsSinkLiveProofTest
 * </pre>
 *
 * Proves, in one run: the sink lands both objects in GCS; both read back byte-identical to what
 * was written (the real sample fixture from the contract, so byte-identity to the file:// cut is
 * exactly what is being asserted); and a second put of the same batch is refused by the server
 * (write-once), not by client-side politeness.
 */
class RiskExtractGcsSinkLiveProofTest {

    private static final String SINK = System.getenv().getOrDefault(
        "RISK_EXTRACT_SINK_URI", "gs://traderx-501015-risk-extracts");

    /** YU17: the sink delivers the contracts artifact alongside the netted one (D3). Header only
     *  here — this test is about the transport's write-once behaviour, not about the rendering. */
    private static final String CONTRACTS =
        "# traderx-swap-contracts schema=1\n" + SwapContractCsv.HEADER + "\n";

    @Test
    @EnabledIfEnvironmentVariable(named = "RISK_EXTRACT_GCS_HMAC_KEY_ID", matches = ".+")
    void deliversWriteOnceByteIdenticalObjects() throws Exception {
        // The real delivered fixture from the contract, not synthetic bytes.
        final Path sample = Path.of("../../../..",
            "specs/YU15-eod-risk-extract/contracts/sample").normalize();
        final String cut = Files.readString(sample.resolve("risk-extract.cut"), StandardCharsets.US_ASCII);
        final String extract = Files.readString(sample.resolve("risk-extract.csv"), StandardCharsets.US_ASCII);

        final String key = "proof/" + System.currentTimeMillis() + "/seq-0";
        final String uri = RiskExtractGcsSink.put(SINK, key, cut, extract, CONTRACTS)[0];
        assertTrue(uri.startsWith("gs://") && uri.endsWith(key + ".csv"), uri);

        final String bucket = URI.create(SINK).getHost();
        try (S3Client s3 = reader()) {
            assertEquals(cut, read(s3, bucket, key + ".cut"), "stored cut differs from local cut");
            assertEquals(extract, read(s3, bucket, key + ".csv"), "stored fixture differs from local fixture");
            // Both artifacts, or neither: they share a consensus sequence and a cutSha256 and are
            // meaningless apart, so a delivery that lands one is a broken delivery (D3).
            assertEquals(CONTRACTS, read(s3, bucket, key + "-contracts.csv"),
                "stored contracts artifact differs from local contracts artifact");
        }

        // Write-once: the SERVER must refuse the redelivery. On this bucket that surfaces as 403
        // (the SA has no storage.objects.delete, checked before the If-None-Match precondition's
        // 412) — either code is a server-side refusal, and the delivered bytes must be untouched.
        final S3Exception clobber = assertThrows(S3Exception.class,
            () -> RiskExtractGcsSink.put(SINK, key, cut + "TAMPERED", extract, CONTRACTS));
        assertTrue(clobber.statusCode() == 403 || clobber.statusCode() == 412,
            "expected 403/412 refusal, got: " + clobber);
        try (S3Client s3 = reader()) {
            assertEquals(cut, read(s3, bucket, key + ".cut"), "redelivery attempt altered the stored cut");
            assertEquals(extract, read(s3, bucket, key + ".csv"), "redelivery attempt altered the stored fixture");
        }
    }

    private static String read(final S3Client s3, final String bucket, final String key) {
        return new String(s3.getObjectAsBytes(GetObjectRequest.builder()
            .bucket(bucket).key(key).build()).asByteArray(), StandardCharsets.US_ASCII);
    }

    private static S3Client reader() {
        return S3Client.builder()
            .endpointOverride(URI.create("https://storage.googleapis.com"))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                System.getenv("RISK_EXTRACT_GCS_HMAC_KEY_ID"),
                System.getenv("RISK_EXTRACT_GCS_HMAC_SECRET_ACCESS_KEY"))))
            .serviceConfiguration(S3Configuration.builder()
                .chunkedEncodingEnabled(false)
                .pathStyleAccessEnabled(true)
                .build())
            .build();
    }
}
