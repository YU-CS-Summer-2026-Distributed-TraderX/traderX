package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Warm-standby building blocks (FR-09B30..B32): the journal follower must apply exactly the
 * leader's complete records — catching up, then picking up live appends, never consuming a torn
 * tail — and the leader lock must be exclusive and hand over on release.
 */
class FailoverPrimitivesTest {

    @TempDir
    Path dir;

    // ----- journal follower ---------------------------------------------------------------

    @Test
    void followerCatchesUpThenTailsLiveAppends() throws Exception {
        Journaler journaler = new Journaler(true, dir, new HotPathMetrics());
        journaler.onEvent(order(1, 7, 100), 0, true);
        journaler.onEvent(order(2, 7, 200), 1, true);
        journaler.onEvent(order(3, 8, 300), 2, true);

        List<long[]> applied = new CopyOnWriteArrayList<>();
        JournalFollower follower = new JournalFollower(dir, new SymbolTable(16),
            e -> applied.add(new long[] { e.seq, e.orderRef, e.qty }), 0, 1);
        follower.start();
        awaitCount(applied, 3);
        assertEquals(3 * 64, follower.appliedOffset());

        // live appends after the follower caught up (the warm-standby steady state)
        journaler.onEvent(order(4, 9, 400), 3, true);
        journaler.onEvent(order(5, 9, 500), 4, true);
        awaitCount(applied, 5);
        follower.stopAndJoin();
        journaler.close();

        assertEquals(5, follower.appliedEvents());
        assertEquals(5 * 64, follower.appliedOffset());
        assertEquals(0, follower.lagBytes());
        assertEquals(4, applied.get(3)[0]);     // seq survives the round trip
        assertEquals(9, applied.get(3)[1]);     // orderRef too
        assertEquals(500, applied.get(4)[2]);   // and qty
    }

    @Test
    void followerNeverConsumesATornTailRecord() throws Exception {
        Journaler journaler = new Journaler(true, dir, new HotPathMetrics());
        journaler.onEvent(order(1, 7, 100), 0, true);
        journaler.close();

        // Simulate an append caught mid-write: only the first 24 bytes of the second record.
        byte[] second = encode(order(2, 8, 200));
        Path journal = dir.resolve("input-events.journal");
        try (FileChannel ch = FileChannel.open(journal, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(second, 0, 24));
        }

        List<long[]> applied = new CopyOnWriteArrayList<>();
        JournalFollower follower = new JournalFollower(dir, new SymbolTable(16),
            e -> applied.add(new long[] { e.seq, e.orderRef }), 0, 1);
        follower.start();
        awaitCount(applied, 1);
        Thread.sleep(50);
        assertEquals(1, applied.size());        // the partial tail must not be applied
        assertEquals(64, follower.appliedOffset());

        // The writer's remaining bytes land: the follower assembles and applies the record.
        try (FileChannel ch = FileChannel.open(journal, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(second, 24, second.length - 24));
        }
        awaitCount(applied, 2);
        follower.stopAndJoin();
        assertEquals(2, applied.get(1)[0]);
        assertEquals(8, applied.get(1)[1]);
    }

    // ----- leader lock ---------------------------------------------------------------------

    @Test
    void leaderLockIsExclusiveAndHandsOverOnRelease() {
        LeaderLock first = new LeaderLock(dir.resolve("leader.lock"));
        LeaderLock second = new LeaderLock(dir.resolve("leader.lock"));
        assertTrue(first.tryAcquire("first"));
        assertTrue(first.held());
        assertFalse(second.tryAcquire("second"));    // exclusive while the holder lives
        assertFalse(second.acquireWithin(150, "second"));

        first.close();                               // holder gone (process death analogue)
        assertTrue(second.acquireWithin(1000, "second"));
        assertTrue(second.held());
        second.close();
    }

    // ----- symbol table reload -------------------------------------------------------------

    @Test
    void readOnlySymbolTableReloadsLeaderAppendsAndNeverWrites(@TempDir Path symDir) throws IOException {
        Path tab = symDir.resolve("symbols.tab");
        Files.writeString(tab, "0\tIBM\n1\tMSFT\n");

        SymbolTable follower = new SymbolTable(16);
        follower.enableReadOnly(tab);
        assertEquals("IBM", follower.tickerFor(0));
        assertEquals(2, follower.size());

        // Leader registers a new ticker; the follower sees it only after reload().
        Files.writeString(tab, "2\tNVDA\n", StandardOpenOption.APPEND);
        assertEquals(2, follower.size());
        follower.reload();
        assertEquals("NVDA", follower.tickerFor(2));

        // Follower-side registration must not touch the leader's file...
        long sizeBefore = Files.size(tab);
        follower.idFor("GS");
        assertEquals(sizeBefore, Files.size(tab));

        // ...until promotion turns persistence on.
        follower.beginPersisting();
        int id = follower.idFor("JPM");
        assertTrue(Files.readString(tab).contains(id + "\tJPM"));
    }

    // ----- helpers ---------------------------------------------------------------------------

    private static InputEvent order(long seq, int orderRef, int qty) {
        InputEvent e = new InputEvent();
        e.seq = seq;
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.orderRef = orderRef;
        e.accountId = 22214;
        e.securityId = 1;
        e.side = InputEvent.SIDE_BUY;
        e.qty = qty;
        e.limitPx = 1_000_000L;
        e.eventTimeMillis = 1700000000000L + seq;
        return e;
    }

    /** Journaler's exact 64-byte little-endian record layout, for byte-level torn-write tests. */
    private static byte[] encode(InputEvent e) {
        ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(e.seq);
        b.put(e.type);
        b.put(e.side);
        b.putShort((short) 0);
        b.putInt(e.orderRef);
        b.putInt(e.accountId);
        b.putInt(e.securityId);
        b.putInt(e.qty);
        b.putLong(e.limitPx);
        b.putLong(e.priceTicks);
        b.putLong(e.eventTimeMillis);
        return b.array();
    }

    private static void awaitCount(List<long[]> applied, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (applied.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, applied.size());
    }
}
