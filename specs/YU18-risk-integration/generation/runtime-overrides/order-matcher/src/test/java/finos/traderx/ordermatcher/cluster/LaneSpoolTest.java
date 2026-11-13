package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FR-DRS-57: one lane's spool on its own: segments, trim, the search by segment, and a disk that refuses a write. */
class LaneSpoolTest {
    private static TradeNatsPublisher.Rec side(final long seq) {
        final TradeNatsPublisher.Rec r = new TradeNatsPublisher.Rec();
        r.tradeSeq = seq;
        r.accountId = 7;
        r.security = "IBM";
        r.leader = (seq & 1) == 0;
        return r;
    }

    private static LaneSpool<TradeNatsPublisher.Rec> spool(final Path dir) {
        if (LaneSpool.budget < 0) { LaneSpool.budget = 1L << 30; }
        return new LaneSpool<>(dir, "p0", TradeNatsPublisher.SPOOL, 16);
    }

    @Test
    void recordsKeepTheirIndexAcrossSegmentsAndATrimDeletesWholeSegmentsOnly(@TempDir final Path dir) throws Exception {
        final long before = LaneSpool.held();
        final LaneSpool<TradeNatsPublisher.Rec> s = spool(dir);
        for (long k = 1000; k < 1050; k++) { assertTrue(s.append(side(k), k)); }   // 3 full segments and 2 records
        assertEquals(1048, s.end(), "full segments are written as they fill; the last two records wait for a flush");
        assertTrue(s.flush());
        assertEquals(1000, s.start());
        assertEquals(1050, s.end());
        assertEquals(4, Files.list(dir).count());
        for (long k = 1000; k < 1050; k++) {
            final TradeNatsPublisher.Rec r = s.at(k);
            assertEquals(k, r.tradeSeq);
            assertEquals(k, r.at);
            assertEquals((k & 1) == 0, r.leader);
        }
        assertNull(s.at(999));
        assertNull(s.at(1050));
        // the growing last segment is read again from where the reader stopped
        assertTrue(s.append(side(1050), 1050));
        assertTrue(s.flush());
        assertEquals(1050, s.at(1050).tradeSeq);
        // the first record after tradeSeq 1020 lies in the segment that starts at 1016
        final long[] b = s.bracket(r -> Long.compare(r.tradeSeq, 1020));
        assertEquals(1016, b[0]);
        assertEquals(1032, b[1]);
        s.trim(1031);                                     // segment 1016..1031 still holds 1031
        assertEquals(1016, s.start());
        assertNull(s.at(1015));
        assertEquals(1031, s.at(1031).tradeSeq);
        s.trim(1051);                                     // past the end: empty
        assertTrue(s.isEmpty());
        assertEquals(0, Files.list(dir).count());
        assertEquals(before, LaneSpool.held(), "the bytes are given back");
    }

    @Test
    void aDiskThatRefusesAWriteStopsTheSpoolAndKeepsWhatItHolds(@TempDir final Path dir) throws Exception {
        final long before = LaneSpool.held();
        final LaneSpool<TradeNatsPublisher.Rec> s = spool(dir);
        for (long k = 0; k < 16; k++) { assertTrue(s.append(side(k + 1), k)); }
        assertTrue(s.flush());
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));   // the next segment cannot be created
        try {
            assertTrue(s.append(side(17), 16), "buffered");
            assertFalse(s.flush(), "the write is refused, not thrown");
            assertEquals(16, s.end(), "the end did not move");
            assertEquals(16, s.at(15).tradeSeq, "and what was spooled is still read");
            assertNull(s.at(16));
            assertFalse(s.append(side(17), 16) && s.flush(), "it stays refused");
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertTrue(s.append(side(17), 16) && s.flush(), "and grows again once the disk takes writes");
        assertEquals(17, s.at(16).tradeSeq);
        s.clear();
        assertEquals(before, LaneSpool.held());
    }

    @Test
    void theBudgetRefusesAWriteAndNothingOfItIsKept(@TempDir final Path dir) {
        final long budget = LaneSpool.budget, before = LaneSpool.held();
        final LaneSpool<TradeNatsPublisher.Rec> s = spool(dir);
        try {
            for (long k = 0; k < 16; k++) { assertTrue(s.append(side(k + 1), k)); }
            assertTrue(s.flush());
            LaneSpool.budget = LaneSpool.held();          // spent
            assertTrue(s.append(side(17), 16));
            assertFalse(s.flush());
            assertEquals(16, s.end());
            assertEquals(1, dir.toFile().list().length, "no empty segment is left behind");
        } finally {
            LaneSpool.budget = budget;
            s.clear();
        }
        assertEquals(before, LaneSpool.held());
    }

    /** ADR-106 follow-up: the spool's root may be a mount point, which cannot be deleted; a wipe empties it and keeps
     *  it. A root whose parent refuses deletes stands in for the mount (GKE run 3: "/spool: Device or resource busy",
     *  every bridge unspooled). Before: the wipe deleted the root itself and threw. */
    @org.junit.jupiter.api.Test
    void aWipeEmptiesARootItCannotDelete() throws Exception {
        final java.nio.file.Path parent = java.nio.file.Files.createTempDirectory("spool-mount");
        final java.nio.file.Path root = java.nio.file.Files.createDirectories(parent.resolve("spool"));
        java.nio.file.Files.createDirectories(root.resolve("trade-1"));
        java.nio.file.Files.writeString(root.resolve("trade-1").resolve("p0-0.seg"), "stale");
        org.junit.jupiter.api.Assumptions.assumeTrue(parent.toFile().setWritable(false), "cannot make the parent read-only");
        try {
            LaneSpool.wipe(root);
            org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.isDirectory(root), "the root itself is kept");
            try (var left = java.nio.file.Files.list(root)) {
                org.junit.jupiter.api.Assertions.assertEquals(0, left.count(), "the root is emptied");
            }
        } finally {
            parent.toFile().setWritable(true);
        }
    }
}
