package finos.traderx.ordermatcher.lmax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalerRotationTest {

    @Test
    void snapshotRotatesToTimestampedSegmentAndResetsActiveOffsets(@TempDir Path dir) throws Exception {
        JournalArchiver archiver = new JournalArchiver(false, null, "", "");
        try (Journaler journaler = new Journaler(true, dir, new HotPathMetrics(), 1, archiver)) {
            journaler.onEvent(event(InputEvent.TYPE_ORDER_NEW, 1), 0, true);
            journaler.onEvent(event(InputEvent.TYPE_SNAPSHOT, 2), 1, true);

            List<Path> segments;
            try (var files = Files.list(dir)) {
                segments = files.filter(p -> p.getFileName().toString()
                    .matches("input-events-\\d+\\.journal")).toList();
            }
            assertEquals(1, segments.size());
            assertEquals(128L, Files.size(segments.get(0)));
            assertTrue(Files.exists(dir.resolve("input-events.journal")));
            // YU11: the fresh file opens with a 64-byte input-tail anchor. SNAPSHOT occupies
            // inputSeq=2 even though it is a business-state no-op, so restart continues at 3.
            assertEquals(64L, Files.size(dir.resolve("input-events.journal")));
            assertEquals(64L, journaler.lastSnapshotOffset());
            assertEquals(2L, new JournalReader(dir).lastInputSeq());
            assertEquals(0L, new JournalReader(dir).replay(e -> { }));
        }
    }

    @Test
    void startupResubmitsLeftoverSegmentsToArchiver(@TempDir Path dir) throws Exception {
        // Segments stranded by an earlier run whose upload leg was down (e.g. no archive secret).
        Files.write(dir.resolve("input-events-111.journal"), new byte[]{1});
        Files.write(dir.resolve("input-events-222.journal"), new byte[]{2});
        var client = org.mockito.Mockito.mock(software.amazon.awssdk.services.s3.S3Client.class);
        org.mockito.Mockito.when(client.putObject(
                org.mockito.ArgumentMatchers.any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
                org.mockito.ArgumentMatchers.any(Path.class)))
            .thenReturn(software.amazon.awssdk.services.s3.model.PutObjectResponse.builder().build());
        JournalArchiver archiver = new JournalArchiver("gs://bucket/journals", client);
        try (Journaler journaler = new Journaler(true, dir, new HotPathMetrics(), 1, archiver)) {
            assertTrue(archiver.awaitIdle(5, java.util.concurrent.TimeUnit.SECONDS));
            // successful archive deletes the local segment; the active journal is untouched
            assertTrue(Files.notExists(dir.resolve("input-events-111.journal")));
            assertTrue(Files.notExists(dir.resolve("input-events-222.journal")));
            assertTrue(Files.exists(dir.resolve("input-events.journal")));
        }
    }

    @Test
    void nullArchiverLeavesRotationDisabledAndActiveJournalGrowing(@TempDir Path dir) throws Exception {
        try (Journaler journaler = new Journaler(true, dir, new HotPathMetrics(), 1)) {
            journaler.onEvent(event(InputEvent.TYPE_ORDER_NEW, 1), 0, true);
            journaler.onEvent(event(InputEvent.TYPE_SNAPSHOT, 2), 1, true);

            try (var files = Files.list(dir)) {
                assertEquals(0, files.filter(p -> p.getFileName().toString()
                    .matches("input-events-\\d+\\.journal")).count());
            }
            assertEquals(128L, Files.size(dir.resolve("input-events.journal")));
            assertEquals(128L, journaler.lastSnapshotOffset());
        }
    }

    @Test
    void rotationIOExceptionIsSwallowedAndNextEventStillAppends(@TempDir Path dir) throws Exception {
        JournalArchiver archiver = new JournalArchiver(false, null, "", "");
        try (Journaler journaler = new Journaler(true, dir, new HotPathMetrics(), 1, archiver)) {
            journaler.onEvent(event(InputEvent.TYPE_ORDER_NEW, 1), 0, true);
            // The channel remains writable, but unlinking its active pathname makes rotate's move
            // fail after close. The recovery path must reopen a fresh active journal.
            Files.delete(dir.resolve("input-events.journal"));
            journaler.onEvent(event(InputEvent.TYPE_SNAPSHOT, 2), 1, true);
            journaler.onEvent(event(InputEvent.TYPE_ORDER_NEW, 3), 2, true);

            assertEquals(2L, journaler.journaledSeq());
            assertEquals(64L, Files.size(dir.resolve("input-events.journal")));
        }
    }

    private static InputEvent event(byte type, long seq) {
        InputEvent event = InputEvent.newInstance();
        event.type = type;
        event.seq = seq;
        event.orderRef = (int) seq;
        event.accountId = 1;
        event.securityId = 2;
        event.qty = 10;
        event.limitPx = 100_000_000L;
        event.eventTimeMillis = 1_000L + seq;
        return event;
    }
}
