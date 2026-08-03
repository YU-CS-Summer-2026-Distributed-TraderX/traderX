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
 * <p>Version 3 (in-memory-risk-gateway, FR-IMRG21) extends the order rows with the authoritative
 * risk decision and live reservation, and adds the BLP risk-state sections (policy scalars,
 * account control/executed exposure, security control/price-freshness, idempotency entries in
 * retention order). Reservation AGGREGATES are intentionally not stored — they are rebuilt from
 * the open-order rows on load, so per-order and aggregate reservation state cannot disagree.
 * Versions 1/2 still load (risk sections absent -> risk state starts from seeds + journal tail).
 *
 * <p>Written atomically (temp + atomic rename) so a crash mid-write leaves the prior snapshot intact.
 * Not hot-path (taken on an interval); plain {@code DataOutputStream}.
 */
public final class SnapshotStore {
    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);
    private static final int MAGIC = 0x534E4150;   // "SNAP"
    private static final int VERSION = 1;
    private static final int VERSION_WITH_JETSTREAM = 2;
    private static final int VERSION_WITH_RISK = 3;

    private final Path file;
    private final Path tmp;

    public SnapshotStore(Path dir) {
        this.file = dir.resolve("snapshot.dat");
        this.tmp = dir.resolve("snapshot.dat.tmp");
    }

    /** prices: {securityId, ticks}; positions: {acct, sec, qty, avgTicks};
     *  orders: {ref, acct, sec, side, qty, rem, limitPx, status, lastExecPx, lastFillQty,
     *  createdMs, updatedMs, riskReason, reservedNotional, reservedQty}.
     *  jetsStreamSeq: JetStream sequence number of the last event covered by this snapshot (-1 if primary/unknown).
     *  riskPolicy: {policyVersion, killSwitch, maxPositionQty, maxConcentrationTicks} or null;
     *  riskAccounts: {accountId, enabled, executedNotional}; riskSecurities: {securityId, enabled,
     *  restricted, lastPrice, lastPriceTime}; riskIdempotency: {key, orderRef, decision} in
     *  retention order — all null when the snapshot predates the risk state. */
    public record Data(long coveredOffset, int nextOrderRef, long tradeCounter,
                       List<long[]> prices, List<long[]> positions, List<long[]> orders,
                       long jetsStreamSeq, long[] riskPolicy, List<long[]> riskAccounts,
                       List<long[]> riskSecurities, List<long[]> riskIdempotency) {
        public Data(long coveredOffset, int nextOrderRef, long tradeCounter,
                    List<long[]> prices, List<long[]> positions, List<long[]> orders,
                    long jetsStreamSeq) {
            this(coveredOffset, nextOrderRef, tradeCounter, prices, positions, orders, jetsStreamSeq,
                null, null, null, null);
        }

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
            out.writeInt(VERSION_WITH_RISK);
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
                out.writeByte((int) o[12]);   // riskReason
                out.writeLong(o[13]);         // reservedNotional
                out.writeInt((int) o[14]);    // reservedQty
            }
            // Risk-state sections (v3). A boolean marker keeps risk-disabled snapshots loadable.
            boolean hasRisk = d.riskPolicy != null;
            out.writeBoolean(hasRisk);
            if (hasRisk) {
                out.writeLong(d.riskPolicy[0]);
                out.writeBoolean(d.riskPolicy[1] != 0);
                out.writeInt((int) d.riskPolicy[2]);
                out.writeLong(d.riskPolicy[3]);
                out.writeInt(d.riskAccounts.size());
                for (long[] a : d.riskAccounts) {
                    out.writeInt((int) a[0]);
                    out.writeBoolean(a[1] != 0);
                    out.writeLong(a[2]);
                }
                out.writeInt(d.riskSecurities.size());
                for (long[] s : d.riskSecurities) {
                    out.writeInt((int) s[0]);
                    out.writeBoolean(s[1] != 0);
                    out.writeBoolean(s[2] != 0);
                    out.writeLong(s[3]);
                    out.writeLong(s[4]);
                }
                out.writeInt(d.riskIdempotency.size());
                for (long[] k : d.riskIdempotency) {
                    out.writeLong(k[0]);
                    out.writeInt((int) k[1]);
                    out.writeByte((int) k[2]);
                }
            }
            out.flush();
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        log.debug("Wrote snapshot: coveredOffset={} orders={} positions={} prices={} risk={}",
            d.coveredOffset(), d.orders().size(), d.positions().size(), d.prices().size(),
            d.riskPolicy() != null);
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
                if (version >= VERSION_WITH_RISK) {
                    orders.add(new long[] { in.readInt(), in.readInt(), in.readInt(), in.readByte(),
                        in.readInt(), in.readInt(), in.readLong(), in.readByte(), in.readLong(),
                        in.readInt(), in.readLong(), in.readLong(), in.readByte(), in.readLong(),
                        in.readInt() });
                } else {
                    // Pre-risk row: decision defaults to ACCEPTED (0) and no live reservation.
                    orders.add(new long[] { in.readInt(), in.readInt(), in.readInt(), in.readByte(),
                        in.readInt(), in.readInt(), in.readLong(), in.readByte(), in.readLong(),
                        in.readInt(), in.readLong(), in.readLong(), 0, 0, 0 });
                }
            }
            long[] riskPolicy = null;
            List<long[]> riskAccounts = null;
            List<long[]> riskSecurities = null;
            List<long[]> riskIdempotency = null;
            if (version >= VERSION_WITH_RISK && in.readBoolean()) {
                riskPolicy = new long[] { in.readLong(), in.readBoolean() ? 1 : 0, in.readInt(),
                    in.readLong() };
                int na = in.readInt();
                riskAccounts = new ArrayList<>(Math.max(0, na));
                for (int i = 0; i < na; i++) {
                    riskAccounts.add(new long[] { in.readInt(), in.readBoolean() ? 1 : 0, in.readLong() });
                }
                int ns = in.readInt();
                riskSecurities = new ArrayList<>(Math.max(0, ns));
                for (int i = 0; i < ns; i++) {
                    riskSecurities.add(new long[] { in.readInt(), in.readBoolean() ? 1 : 0,
                        in.readBoolean() ? 1 : 0, in.readLong(), in.readLong() });
                }
                int nk = in.readInt();
                riskIdempotency = new ArrayList<>(Math.max(0, nk));
                for (int i = 0; i < nk; i++) {
                    riskIdempotency.add(new long[] { in.readLong(), in.readInt(), in.readByte() });
                }
            }
            return new Data(coveredOffset, nextOrderRef, tradeCounter, prices, positions, orders,
                jetsStreamSeq, riskPolicy, riskAccounts, riskSecurities, riskIdempotency);
        }
    }
}
