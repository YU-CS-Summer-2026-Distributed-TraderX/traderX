package finos.traderx.ordermatcher.cluster;

import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static finos.traderx.ordermatcher.cluster.ArchivePurgeTest.await;
import static finos.traderx.ordermatcher.cluster.ArchivePurgeTest.connect;
import static finos.traderx.ordermatcher.cluster.ArchivePurgeTest.send;
import static finos.traderx.ordermatcher.cluster.ArchivePurgeTest.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-079 §3: purged history is offloaded before it is deleted, and it comes back. On a real 3-member cluster:
 * purge with a file offload; replay from 0 is refused (the history is off the member); restore the offloaded
 * segments and attach them; replay from 0 is accepted again, and the strongest check there is — an EMPTY-disk
 * member, which ArchivePurgeTest proves cannot rejoin a purged cluster, rejoins and converges to the same
 * deterministic state. That is the property FR-RXT10 needs: a past cut re-created by replaying from 0.
 */
class ArchiveOffloadTest {

    @TempDir
    Path tmp;

    @Test
    void purgeWithoutAnOffloadOrAnExplicitDiscardIsRefused() {
        final IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> ArchivePurgeMain.offloadFrom(null, "0", false));
        assertTrue(e.getMessage().contains("ARCHIVE_PURGE_DISCARD_HISTORY"), e.getMessage());
        assertNull(ArchivePurgeMain.offloadFrom("", "0", true), "discard is an explicit choice, and the only null");
        assertThrows(IllegalStateException.class, () -> ArchivePurgeMain.offloadFrom("file:///x", "0", true));
        assertThrows(IllegalStateException.class, () -> ArchivePurgeMain.offloadFrom("s3://x", "0", false));
        assertThrows(IllegalStateException.class, () -> ArchivePurgeMain.offloadFrom("gs://bucket/p", "0", false),
            "gs:// without HMAC credentials refuses rather than deleting with nowhere to put the bytes");
    }

    @Test
    void fileOffloadIsWriteOnce() throws Exception {
        final ArchivePurgeMain.Offload o = ArchivePurgeMain.offloadFrom(tmp.resolve("bucket").toUri().toString(), "1", false);
        final File seg = tmp.resolve("7-0.rec").toFile();
        Files.write(seg.toPath(), new byte[] {1, 2, 3});
        o.put(seg);
        o.put(seg);   // a poll that stored it and died before the unlink retries: same bytes, accepted
        assertEquals(3, Files.size(tmp.resolve("bucket/member-1/7-0.rec")));
        Files.write(seg.toPath(), new byte[] {1, 2, 4});
        assertThrows(IllegalStateException.class, () -> o.put(seg), "different bytes under the same name never replace");
    }

    private List<File> segments(final int n) throws Exception {
        final List<File> files = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final File f = tmp.resolve("7-" + (i << 20) + ".rec").toFile();
            Files.write(f.toPath(), new byte[] {(byte) i});
            files.add(f);
        }
        return files;
    }

    /** ADR-079 §3, parallel: uploads overlap (>= 2 in flight), and each file is unlinked after its own upload. */
    @Test
    void uploadsRunConcurrentlyAndEachSegmentIsDeletedAfterItsOwnUpload() throws Exception {
        final List<File> files = segments(6);
        final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch two = new java.util.concurrent.CountDownLatch(2);
        final java.util.Set<String> stored = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final List<String> deletedWhileUnstored = java.util.Collections.synchronizedList(new ArrayList<>());
        final ArchivePurgeMain.Offload slow = seg -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            two.countDown();
            two.await(10, java.util.concurrent.TimeUnit.SECONDS);   // only passes once a second upload is running too
            stored.add(seg.getName());
            inFlight.decrementAndGet();
        };
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            final int deleted = ArchivePurgeMain.offloadThenDelete(files, slow, pool, files.size(), line -> {
                final String name = line.replaceAll(".*file=(\\S+).*", "$1");
                if (line.startsWith("PURGE_DELETE") && !stored.contains(name)) deletedWhileUnstored.add(name);
            });
            assertEquals(6, deleted);
        } finally {
            pool.shutdownNow();
        }
        assertTrue(maxInFlight.get() >= 2, "uploads overlapped: max in flight " + maxInFlight.get());
        assertTrue(deletedWhileUnstored.isEmpty(), "a segment was unlinked before its upload returned: " + deletedWhileUnstored);
        assertTrue(files.stream().noneMatch(File::exists));
    }

    /** A failure mid-batch: the segment before it is gone, it and EVERY later one stay, even ones whose upload succeeded. */
    @Test
    void aFailedUploadMidBatchLeavesThatSegmentAndAllLaterOnesOnDisk() throws Exception {
        final List<File> files = segments(5);
        final String bad = files.get(2).getName();
        final java.util.Set<String> stored = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final ArchivePurgeMain.Offload flaky = seg -> {
            if (seg.getName().equals(bad)) throw new java.io.IOException("simulated 503");
            stored.add(seg.getName());
        };
        final List<String> lines = new ArrayList<>();
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        final int deleted;
        try {
            deleted = ArchivePurgeMain.offloadThenDelete(files, flaky, pool, files.size(), lines::add);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, deleted, String.join("\n", lines));
        assertTrue(!files.get(0).exists() && !files.get(1).exists(), "the segments before the failure are deleted");
        for (int i = 2; i < 5; i++) assertTrue(files.get(i).exists(), files.get(i).getName() + " must stay on disk");
        assertTrue(stored.contains(files.get(3).getName()) && stored.contains(files.get(4).getName()),
            "later uploads did succeed, and their files were still kept: no hole below a file the archive holds");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("PURGE_OFFLOAD_FAILED file=" + bad)), String.join("\n", lines));
    }

    @Test
    void offloadedHistoryRestoresAndAnEmptyDiskMemberRejoins() throws Exception {
        final File root = tmp.toFile();
        final File bucket = new File(root, "bucket");
        final List<ArchivePurgeTest.Member> members = new ArrayList<>();
        for (int i = 0; i < 3; i++) members.add(new ArchivePurgeTest.Member(i, root));
        for (final ArchivePurgeTest.Member m : members) m.start(true);
        final MediaDriver clientDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
            .threadingMode(ThreadingMode.SHARED).dirDeleteOnShutdown(true));
        AeronCluster client = connect(clientDriver);
        final long[] expected = {0, 0};
        try {
            send(client, 4000, expected, members);
            snapshot(members); send(client, 4000, expected, members);
            snapshot(members); send(client, 4000, expected, members);
            snapshot(members); send(client, 2000, expected, members);

            final List<String> urls = members.stream().map(ArchivePurgeTest.Member::url).toList();
            long logId = -1;
            for (final ArchivePurgeTest.Member m : members) {
                try (ArchivePurgeMain p = new ArchivePurgeMain(new File(m.base, "cluster"), m.aeronDir, urls, 5000, 0, 70,
                        ArchivePurgeMain.offloadFrom(bucket.toURI().toString(), String.valueOf(m.id), false))) {
                    final ArchivePurgeMain.Snapshots s = p.snapshots();
                    logId = s.logRecordingIds().iterator().next();
                    final String line = p.poll();
                    assertTrue(line != null && line.startsWith("PURGE "), "member " + m.id + ": " + line);
                    final int deleted = p.deleteDetached(s.logRecordingIds(), Integer.MAX_VALUE);
                    final String[] stored = new File(bucket, "member-" + m.id).list((d, n) -> n.endsWith(".rec"));
                    assertTrue(deleted > 0 && stored != null && stored.length >= deleted,
                        "member " + m.id + " deleted " + deleted + " only after offloading them");
                    assertTrue(p.descriptor(logId)[0] > 0, "member " + m.id + " log start moved");
                    final long start = p.descriptor(logId)[0];
                    assertThrows(Exception.class, () -> p.archive().startReplay(
                        s.logRecordingIds().iterator().next(), 0, AeronArchive.NULL_LENGTH, "aeron:ipc", 1998),
                        "replay from 0 is refused while the history is offloaded (start " + start + ")");
                }
            }

            // The member that will rejoin from nothing: stop it and wipe its disk.
            final ArchivePurgeTest.Member j = members.get(2);
            j.stop();
            org.agrona.IoUtil.delete(j.base, true);

            // Restore the survivors' history (gcloud storage cp in the cloud) and attach it.
            for (final ArchivePurgeTest.Member m : members.subList(0, 2)) {
                final File from = new File(bucket, "member-" + m.id);
                for (final String name : from.list((d, n) -> n.endsWith(".rec"))) {
                    final Path target = m.archiveDir().toPath().resolve(name);
                    if (!Files.exists(target)) Files.copy(from.toPath().resolve(name), target);
                }
                try (ArchivePurgeMain p = new ArchivePurgeMain(new File(m.base, "cluster"), m.aeronDir, urls, 0, 0, 100)) {
                    final String attached = p.attach();
                    System.out.println("member " + m.id + " " + attached);
                    assertEquals(0, p.descriptor(logId)[0], "member " + m.id + " history back to position 0: " + attached);
                    final long replay = p.archive().startReplay(logId, 0, AeronArchive.NULL_LENGTH, "aeron:ipc", 1997);
                    p.archive().stopReplay(replay);
                }
            }

            j.start(true);
            await(() -> j.svc.applied.get() == expected[0], "empty-disk member rejoined to applied=" + expected[0]);
            assertEquals(expected[1], j.svc.sum.get(), "the rejoined member's state equals its peers'");
            send(client, 500, expected, members);
            for (final ArchivePurgeTest.Member m : members) {
                assertEquals(expected[1], m.svc.sum.get(), "member " + m.id + " applies after the restore");
            }
        } finally {
            client.close();
            for (final ArchivePurgeTest.Member m : members) {
                try { m.stop(); } catch (final Exception ignore) { /* already stopped */ }
                m.health.stop(0);
            }
            clientDriver.close();
        }
    }
}
