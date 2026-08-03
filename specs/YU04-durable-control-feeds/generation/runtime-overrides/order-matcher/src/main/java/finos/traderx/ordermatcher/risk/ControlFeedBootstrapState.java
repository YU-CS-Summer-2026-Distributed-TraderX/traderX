package finos.traderx.ordermatcher.risk;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Pure implementation of ADR-019's watermarked subscribe-buffer-snapshot-catchup protocol for one
 * durable control-feed source (account or security). No I/O lives here — no NATS, no HTTP — so it
 * is fully unit-testable against ADR-019's "Validation (when adopted)" fixture list (updates
 * immediately before/during/after snapshot creation; duplicate/reorder/gap/epoch-change fixtures;
 * buffer overflow; checksum/schema mismatch; readiness remains false until high-watermark
 * catch-up). {@link ControlFeedSubscriber} is the thin, separately-verified I/O adapter around
 * this class — mirrors the split already used on the account-service/reference-data side
 * (transaction-orchestration logic tested with a fake connection; the real NATS/HTTP calls
 * verified live in staging instead of unit tests, since there is no embeddable JetStream broker).
 *
 * <p>Not journaled or snapshotted — this is edge-only bootstrap-protocol bookkeeping, entirely
 * separate from {@link GatewayReplicaStore}'s own (also edge-only, also not journaled) records.
 */
public final class ControlFeedBootstrapState<T> {

    public enum Outcome { OK, BUFFER_OVERFLOW, CHECKSUM_MISMATCH, EPOCH_MISMATCH, GAP, DUPLICATE }

    public record Delta<T>(long version, long epoch, T payload) {}

    public record Snapshot<T>(long epoch, long watermark, int count, String checksum, List<T> records) {}

    private final String source;
    private final int bufferCapacity;
    private final Deque<Delta<T>> buffer = new ArrayDeque<>();

    private volatile long epoch = -1L;
    private volatile long watermark = -1L;
    private volatile boolean ready = false;

    public ControlFeedBootstrapState(String source, int bufferCapacity) {
        this.source = source;
        this.bufferCapacity = bufferCapacity;
    }

    /** Step 1: subscribe and buffer — call for every delta received before the snapshot installs. */
    public Outcome bufferDelta(long version, long epoch, T payload) {
        if (buffer.size() >= bufferCapacity) {
            return Outcome.BUFFER_OVERFLOW;
        }
        buffer.addLast(new Delta<>(version, epoch, payload));
        return Outcome.OK;
    }

    /**
     * Steps 2-4: verify the fetched snapshot (recomputed checksum + count must match), atomically
     * install it via {@code installer} — every snapshot record is installed with {@code
     * sourceVersion = snapshot.watermark()} (the snapshot has no per-record version of its own; a
     * complete consistent view as of watermark W means every record in it is valid as of at least
     * W) — then apply buffered deltas with version greater than the snapshot's watermark, same
     * epoch, in order — duplicates at or below the watermark are silently discarded (FR-IMRG04).
     * On any Outcome other than OK, the caller must quarantine (readiness stays/returns false; see
     * {@link #quarantine()}) and retry the whole bootstrap.
     */
    public Outcome installSnapshotAndReplay(
            Snapshot<T> snapshot,
            Function<List<T>, String> checksumFn,
            BiConsumer<T, Long> installer,
            BiConsumer<T, Long> applier) {
        if (snapshot.records().size() != snapshot.count()) {
            return Outcome.CHECKSUM_MISMATCH;
        }
        String recomputed = checksumFn.apply(snapshot.records());
        if (!recomputed.equals(snapshot.checksum())) {
            return Outcome.CHECKSUM_MISMATCH;
        }

        for (T record : snapshot.records()) {
            installer.accept(record, snapshot.watermark());
        }
        this.epoch = snapshot.epoch();
        this.watermark = snapshot.watermark();

        List<Delta<T>> sorted = buffer.stream().sorted(Comparator.comparingLong(Delta::version)).toList();
        buffer.clear();
        for (Delta<T> delta : sorted) {
            if (delta.epoch() != this.epoch) {
                return Outcome.EPOCH_MISMATCH;
            }
            if (delta.version() <= this.watermark) {
                continue; // at/below the snapshot watermark: duplicate, discard (FR-IMRG04)
            }
            if (delta.version() != this.watermark + 1) {
                return Outcome.GAP;
            }
            applier.accept(delta.payload(), delta.version());
            this.watermark = delta.version();
        }
        this.ready = true;
        return Outcome.OK;
    }

    /**
     * Step 5: apply one delta received during live consumption, after bootstrap completed. A
     * version at/below the current watermark is an ordinary idempotent duplicate redelivery, not
     * a fault (JetStream is at-least-once). A forward jump greater than 1, or a different epoch,
     * is a real gap/resync and must quarantine.
     */
    public Outcome applyLiveDelta(long version, long epoch, T payload, BiConsumer<T, Long> applier) {
        if (epoch != this.epoch) {
            return Outcome.EPOCH_MISMATCH;
        }
        if (version <= this.watermark) {
            return Outcome.DUPLICATE;
        }
        if (version != this.watermark + 1) {
            return Outcome.GAP;
        }
        applier.accept(payload, version);
        this.watermark = version;
        return Outcome.OK;
    }

    /** FR-IMRG34: invalidate readiness and clear the buffer; caller restarts from step 1. */
    public void quarantine() {
        ready = false;
        buffer.clear();
    }

    public boolean isReady() {
        return ready;
    }

    public long watermark() {
        return watermark;
    }

    public long epoch() {
        return epoch;
    }

    public String source() {
        return source;
    }

    public int bufferedCount() {
        return buffer.size();
    }
}
