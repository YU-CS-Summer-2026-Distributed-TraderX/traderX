package finos.traderx.ordermatcher.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClOrdIdLedgerTest {

    private static final long SESSION = ClOrdIdLedger.sessionKey("BENCH01", "TRADERX");

    @Test
    void appendLookupAndDuplicateDetection(@TempDir Path dir) {
        try (ClOrdIdLedger ledger = new ClOrdIdLedger(dir)) {
            assertEquals(ClOrdIdLedger.AppendResult.OK, ledger.append(SESSION, "ord-1", 7));
            assertEquals(ClOrdIdLedger.AppendResult.DUPLICATE, ledger.append(SESSION, "ord-1", 8));
            // same ClOrdID on a DIFFERENT session is a distinct order
            long other = ClOrdIdLedger.sessionKey("OTHER", "TRADERX");
            assertEquals(ClOrdIdLedger.AppendResult.OK, ledger.append(other, "ord-1", 9));

            assertEquals(7, ledger.byClOrdId(SESSION, "ord-1").orderRef());
            assertEquals("ord-1", ledger.byOrderRef(7).clOrdId());
            assertEquals(9, ledger.byOrderRef(9).orderRef());
            assertNull(ledger.byClOrdId(SESSION, "missing"));
            assertNull(ledger.byOrderRef(999));
        }
    }

    @Test
    void rehydratesAcrossReopen(@TempDir Path dir) {
        try (ClOrdIdLedger ledger = new ClOrdIdLedger(dir)) {
            for (int i = 0; i < 200; i++) {
                assertEquals(ClOrdIdLedger.AppendResult.OK,
                    ledger.append(SESSION, "ord-" + i, i));
            }
        }
        try (ClOrdIdLedger reopened = new ClOrdIdLedger(dir)) {
            assertEquals(200, reopened.size());
            assertEquals(150, reopened.byClOrdId(SESSION, "ord-150").orderRef());
            assertEquals("ord-42", reopened.byOrderRef(42).clOrdId());
            // duplicate detection survives the restart — the safety property behind idempotent retry
            assertEquals(ClOrdIdLedger.AppendResult.DUPLICATE,
                reopened.append(SESSION, "ord-150", 1));
            // and new appends continue
            assertEquals(ClOrdIdLedger.AppendResult.OK,
                reopened.append(SESSION, "ord-new", 201));
        }
    }

    @Test
    void tornTailIsTruncatedAndLedgerContinues(@TempDir Path dir) throws Exception {
        try (ClOrdIdLedger ledger = new ClOrdIdLedger(dir)) {
            ledger.append(SESSION, "good-1", 1);
            ledger.append(SESSION, "good-2", 2);
        }
        Path file = dir.resolve("clordid-ledger.dat");
        long intact = Files.size(file);
        // simulate a crash mid-append: garbage partial record at the tail
        Files.write(file, new byte[]{1, 2, 3, 4, 5}, StandardOpenOption.APPEND);
        try (ClOrdIdLedger reopened = new ClOrdIdLedger(dir)) {
            assertEquals(2, reopened.size());
            assertEquals(ClOrdIdLedger.AppendResult.OK, reopened.append(SESSION, "good-3", 3));
        }
        // corrupt FULL record (bad length field) also stops rehydration at the last good entry
        byte[] junk = new byte[ClOrdIdLedger.RECORD_SIZE];
        junk[20] = (byte) 0xFF; junk[21] = (byte) 0x7F;  // clOrdIdLen = 32767
        Files.write(file, junk, StandardOpenOption.APPEND);
        try (ClOrdIdLedger reopened = new ClOrdIdLedger(dir)) {
            assertEquals(3, reopened.size());
            assertTrue(Files.size(file) >= intact);
        }
    }

    @Test
    void failsClosedAfterCloseAndOnBadInput(@TempDir Path dir) {
        ClOrdIdLedger ledger = new ClOrdIdLedger(dir);
        assertTrue(ledger.available());
        ledger.close();
        assertFalse(ledger.available());
        assertEquals(ClOrdIdLedger.AppendResult.UNAVAILABLE, ledger.append(SESSION, "x", 1));

        try (ClOrdIdLedger fresh = new ClOrdIdLedger(dir)) {
            assertThrows(IllegalArgumentException.class,
                () -> fresh.append(SESSION, "", 1));
            assertThrows(IllegalArgumentException.class,
                () -> fresh.append(SESSION, "x".repeat(65), 1));
        }
    }

    @Test
    void sessionKeyIsStableAndDiscriminates() {
        assertEquals(ClOrdIdLedger.sessionKey("A", "B"), ClOrdIdLedger.sessionKey("A", "B"));
        assertNotEquals(ClOrdIdLedger.sessionKey("A", "B"), ClOrdIdLedger.sessionKey("B", "A"));
        assertNotEquals(ClOrdIdLedger.sessionKey("AB", ""), ClOrdIdLedger.sessionKey("A", "B"));
    }
}
