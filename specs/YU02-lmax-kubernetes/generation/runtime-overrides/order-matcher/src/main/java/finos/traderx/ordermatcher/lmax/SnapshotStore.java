package finos.traderx.ordermatcher.lmax;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable full-state snapshot of the BLP (state 009b recovery, step "a"): the in-memory book,
 * net positions, last prices, and counters at a sequence boundary, plus the journal byte offset the
 * snapshot covers up to. Recovery loads the latest snapshot and replays only the journal TAIL after
 * {@code coveredOffset}, bounding replay length as the journal grows. The byte offset is stable
 * across restarts because the journal is a single append-only file.
 *
 * <p>Written atomically (temp + atomic rename) so a crash mid-write leaves the prior snapshot intact.
 * Binary layout: magic|version|coveredOffset|nextOrderRef|tradeCounter, then count-prefixed prices,
 * positions, and orders. Not hot-path (taken on an interval); plain {@code DataOutputStream}.
 */
public final class SnapshotStore {
    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);
    private static final int MAGIC = 0x534E4150;   // "SNAP"
    private static final int VERSION = 1;
    private static final int VERSION_WITH_JETSTREAM = 2;

    private final Path file;
    private final Path tmp;

    public SnapshotStore(Path dir) {
        this.file = dir.resolve("snapshot.dat");
        this.tmp = dir.resolve("snapshot.dat.tmp");
    }

    /** prices: {securityId, ticks}; positions: {acct, sec, qty, avgTicks};
     *  orders: {ref, acct, sec, side, qty, rem, limitPx, status, lastExecPx, lastFillQty, createdMs, updatedMs}.
     *  jetsStreamSeq: JetStream sequence number of the last event covered by this snapshot (-1 if primary/unknown). */
    public record Data(long coveredOffset, int nextOrderRef, long tradeCounter,
                       List<long[]> prices, List<long[]> positions, List<long[]> orders,
                       long jetsStreamSeq) {
        public Data(long coveredOffset, int nextOrderRef, long tradeCounter,
                    List<long[]> prices, List<long[]> positions, List<long[]> orders) {
            this(coveredOffset, nextOrderRef, tradeCounter, prices, positions, orders, -1L);
        }
    }

    public boolean exists() {
        return Files.exists(file);
    }

    public void write(Data d) throws IOException {
        Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION_WITH_JETSTREAM);
            out.writeLong(d.coveredOffset);
            out.writeInt(d.nextOrderRef);
            out.writeLong(d.tradeCounter);
            out.writeLong(d.jetsStreamSeq);
            out.writeInt(d.prices.size());
            for (long[] p : d.prices) {
                out.writeInt((int) p[0]);
                out.writeLong(p[1]);
            }
            out.writeInt(d.positions.size());
            for (long[] p : d.positions) {
                out.writeInt((int) p[0]);
                out.writeInt((int) p[1]);
                out.writeInt((int) p[2]);
                out.writeLong(p[3]);
            }
            out.writeInt(d.orders.size());
            for (long[] o : d.orders) {
                out.writeInt((int) o[0]);
                out.writeInt((int) o[1]);
                out.writeInt((int) o[2]);
                out.writeByte((int) o[3]);
                out.writeInt((int) o[4]);
                out.writeInt((int) o[5]);
                out.writeLong(o[6]);
                out.writeByte((int) o[7]);
                out.writeLong(o[8]);
                out.writeInt((int) o[9]);
                out.writeLong(o[10]);
                out.writeLong(o[11]);
            }
            out.flush();
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        log.debug("Wrote snapshot: coveredOffset={} orders={} positions={} prices={}",
            d.coveredOffset(), d.orders().size(), d.positions().size(), d.prices().size());
    }

    public Data read() throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("bad snapshot magic in " + file);
            }
            int version = in.readInt();
            long coveredOffset = in.readLong();
            int nextOrderRef = in.readInt();
            long tradeCounter = in.readLong();
            long jetsStreamSeq = (version >= VERSION_WITH_JETSTREAM) ? in.readLong() : -1L;
            int np = in.readInt();
            List<long[]> prices = new ArrayList<>(Math.max(0, np));
            for (int i = 0; i < np; i++) {
                prices.add(new long[] { in.readInt(), in.readLong() });
            }
            int npos = in.readInt();
            List<long[]> positions = new ArrayList<>(Math.max(0, npos));
            for (int i = 0; i < npos; i++) {
                positions.add(new long[] { in.readInt(), in.readInt(), in.readInt(), in.readLong() });
            }
            int no = in.readInt();
            List<long[]> orders = new ArrayList<>(Math.max(0, no));
            for (int i = 0; i < no; i++) {
                orders.add(new long[] { in.readInt(), in.readInt(), in.readInt(), in.readByte(),
                    in.readInt(), in.readInt(), in.readLong(), in.readByte(), in.readLong(),
                    in.readInt(), in.readLong(), in.readLong() });
            }
            return new Data(coveredOffset, nextOrderRef, tradeCounter, prices, positions, orders, jetsStreamSeq);
        }
    }
}
