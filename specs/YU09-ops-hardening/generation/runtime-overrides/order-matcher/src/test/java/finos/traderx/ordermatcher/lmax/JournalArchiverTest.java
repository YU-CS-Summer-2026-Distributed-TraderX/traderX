package finos.traderx.ordermatcher.lmax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

class JournalArchiverTest {

    @Test
    void enabledWithoutCredentialsLeavesSegmentOnDisk(@TempDir Path dir) throws Exception {
        Path segment = Files.writeString(dir.resolve("input-events-1.journal"), "segment");
        try (JournalArchiver archiver = new JournalArchiver(true, "gs://bucket/journals", "", "")) {
            archiver.archiveAsync(segment);
            assertTrue(Files.exists(segment));
        }
    }

    @Test
    void failedUploadLeavesSegmentAndSuccessfulUploadDeletesIt(@TempDir Path dir) throws Exception {
        Path failed = Files.writeString(dir.resolve("input-events-2.journal"), "failed");
        S3Client failingClient = mock(S3Client.class);
        when(failingClient.putObject(any(PutObjectRequest.class), any(Path.class)))
            .thenThrow(S3Exception.builder().statusCode(500).message("injected").build());
        try (JournalArchiver archiver = new JournalArchiver("gs://bucket/journals", failingClient)) {
            archiver.archiveAsync(failed);
            assertTrue(archiver.awaitIdle(5, TimeUnit.SECONDS));
            assertTrue(Files.exists(failed));
        }

        Path succeeded = Files.writeString(dir.resolve("input-events-3.journal"), "success");
        S3Client successfulClient = mock(S3Client.class);
        when(successfulClient.putObject(any(PutObjectRequest.class), any(Path.class)))
            .thenReturn(PutObjectResponse.builder().build());
        try (JournalArchiver archiver = new JournalArchiver("gs://bucket/journals", successfulClient)) {
            archiver.archiveAsync(succeeded);
            assertTrue(archiver.awaitIdle(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(succeeded));
        }
    }

    @Test
    void archiveReturnsBeforeBlockingUploadAndUsesNamedDaemonThread(@TempDir Path dir) throws Exception {
        Path segment = Files.writeString(dir.resolve("input-events-4.journal"), "slow");
        S3Client client = mock(S3Client.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicBoolean daemon = new AtomicBoolean();
        when(client.putObject(any(PutObjectRequest.class), any(Path.class))).thenAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            daemon.set(Thread.currentThread().isDaemon());
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return PutObjectResponse.builder().build();
        });

        try (JournalArchiver archiver = new JournalArchiver("gs://bucket/journals", client)) {
            archiver.archiveAsync(segment);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(Files.exists(segment), "archiveAsync returned while upload remained blocked");
            assertEquals("journal-archiver", threadName.get());
            assertTrue(daemon.get());
            release.countDown();
            assertTrue(archiver.awaitIdle(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(segment));
        }
    }
}
