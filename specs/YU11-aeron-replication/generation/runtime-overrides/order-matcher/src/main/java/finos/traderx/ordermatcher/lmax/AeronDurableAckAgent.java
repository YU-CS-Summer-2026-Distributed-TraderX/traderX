package finos.traderx.ordermatcher.lmax;

import io.aeron.ExclusivePublication;
import io.aeron.logbuffer.BufferClaim;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Follower-side ACK agent. It translates the Journaler's exact post-force local sequence through
 * the fixed SPSC mapping and publishes the highest covered primary sequence. It never infers
 * durability from ring publication or BLP application.
 */
public final class AeronDurableAckAgent implements AutoCloseable, Runnable {
    private final ExclusivePublication publication;
    private final FollowerSequenceMap sequenceMap;
    private final LongSupplier journaledSeq;
    private final LongSupplier journalForceNanos;
    private final LongSupplier appliedSeq;
    private final long offerTimeoutNs;
    private final boolean publishDurableAck;
    private final AeronFollowerCheckpointStore checkpointStore;
    private final Runnable checkpointFault;
    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final FollowerSequenceMap.Entry entry = new FollowerSequenceMap.Entry();
    private final BufferClaim claim = new BufferClaim();
    private volatile boolean running;
    private volatile long lastAckedLocalSeq = -1;
    private volatile long lastAckedInputSeq = -1;
    private Thread thread;

    public AeronDurableAckAgent(ExclusivePublication publication, FollowerSequenceMap sequenceMap,
                                LongSupplier journaledSeq, LongSupplier journalForceNanos,
                                LongSupplier appliedSeq, long offerTimeoutMs) {
        this(publication, sequenceMap, journaledSeq, journalForceNanos, appliedSeq,
            offerTimeoutMs, true, null, () -> { });
    }

    public AeronDurableAckAgent(ExclusivePublication publication, FollowerSequenceMap sequenceMap,
                                LongSupplier journaledSeq, LongSupplier journalForceNanos,
                                LongSupplier appliedSeq, long offerTimeoutMs,
                                boolean publishDurableAck,
                                AeronFollowerCheckpointStore checkpointStore,
                                Runnable checkpointFault) {
        this.publication = publication;
        this.sequenceMap = sequenceMap;
        this.journaledSeq = journaledSeq;
        this.journalForceNanos = journalForceNanos;
        this.appliedSeq = appliedSeq;
        this.offerTimeoutNs = Math.max(1L, offerTimeoutMs) * 1_000_000L;
        this.publishDurableAck = publishDurableAck;
        this.checkpointStore = checkpointStore;
        this.checkpointFault = checkpointFault;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "blp-aeron-durable-ack");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        while (running) {
            long localSeq = journaledSeq.getAsLong();
            if (localSeq <= lastAckedLocalSeq || !sequenceMap.read(localSeq, entry)) {
                LockSupport.parkNanos(50_000L);
                continue;
            }
            long flags = AeronReplicationCodec.ACK_ON_RING | AeronReplicationCodec.ACK_JOURNALED;
            if (appliedSeq.getAsLong() >= localSeq) flags |= AeronReplicationCodec.ACK_APPLIED;
            long forceNanos = journalForceNanos.getAsLong();
            if (publishDurableAck && !offer(entry.epoch, flags, entry.inputSeq,
                entry.recordingPosition, forceNanos)) {
                LockSupport.parkNanos(100_000L);
                continue;
            }
            if (checkpointStore != null) {
                try {
                    checkpointStore.write(entry.epoch, entry.inputSeq,
                        entry.recordingPosition, entry.checksum, entry.dataSessionId);
                } catch (java.io.IOException ex) {
                    running = false;
                    checkpointFault.run();
                    continue;
                }
            }
            lastAckedLocalSeq = localSeq;
            lastAckedInputSeq = entry.inputSeq;
            sequenceMap.consumed(localSeq);
        }
    }

    private boolean offer(long epoch, long flags, long inputSeq,
                          long recordingPosition, long forceNanos) {
        long deadline = System.nanoTime() + offerTimeoutNs;
        while (running) {
            long result = publication.tryClaim(AeronReplicationCodec.ACK_BYTES, claim);
            if (result >= 0) {
                codec.encodeAck(claim.buffer(), claim.offset(), epoch, flags,
                    inputSeq, recordingPosition, forceNanos);
                claim.commit();
                return true;
            }
            if (System.nanoTime() >= deadline) return false;
            Thread.onSpinWait();
        }
        return false;
    }

    public long lastAckedLocalSeq() { return lastAckedLocalSeq; }
    public long lastAckedInputSeq() { return lastAckedInputSeq; }

    @Override
    public void close() {
        running = false;
        if (thread != null) thread.interrupt();
        if (checkpointStore != null) {
            try { checkpointStore.close(); } catch (java.io.IOException ignored) { }
        }
    }
}
