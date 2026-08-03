package finos.traderx.ordermatcher.lmax;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.FragmentHandler;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Primary-side Aeron replication handler. It encodes the SBE record directly into a claimed
 * publication term buffer and coalesces follower ACK waits at Disruptor batch boundaries.
 */
public final class AeronReplicator implements ReplicationEventHandler {
    private final ExclusivePublication dataPublication;
    private final Subscription ackSubscription;
    private final long leaderEpoch;
    private final ReplicationAckMode ackMode;
    private final ReplicationFailurePolicy failurePolicy;
    private final long ackTimeoutNs;
    private final boolean shadow;
    private final BooleanSupplier peerAuthenticated;
    private final AeronReplicationCodec dataCodec = new AeronReplicationCodec();
    private final AeronReplicationCodec ackCodec = new AeronReplicationCodec();
    private final BufferClaim dataClaim = new BufferClaim();
    private final FragmentHandler ackHandler = this::onAck;
    private final LongAdder offerFailures = new LongAdder();
    private final LongAdder invalidAcks = new LongAdder();
    private volatile AeronPublicationSequenceMap publicationSequenceMap;
    private long snapshotEpoch;
    private long snapshotInputSeq;
    private long snapshotRecordingPosition;
    private long snapshotChecksum;
    private int snapshotDataSessionId;
    /** Release-published last: binds the primitive fields above to one exact local marker. */
    private volatile long snapshotLocalSeq = Long.MIN_VALUE;

    private volatile long publishedSeq = -1;
    private volatile long followerAckedSeq = -1;
    private volatile long lastAckNs;
    private volatile boolean degraded;

    public AeronReplicator(Aeron aeron, String dataChannel, int dataStreamId,
                           String ackChannel, int ackStreamId, long leaderEpoch,
                           ReplicationAckMode ackMode, ReplicationFailurePolicy failurePolicy,
                           long ackTimeoutMs, boolean shadow) {
        this(aeron, dataChannel, dataStreamId, ackChannel, ackStreamId, leaderEpoch,
            ackMode, failurePolicy, ackTimeoutMs, shadow, () -> true, "", "");
    }

    public AeronReplicator(Aeron aeron, String dataChannel, int dataStreamId,
                           String ackChannel, int ackStreamId, long leaderEpoch,
                           ReplicationAckMode ackMode, ReplicationFailurePolicy failurePolicy,
                           long ackTimeoutMs, boolean shadow, BooleanSupplier peerAuthenticated) {
        this(aeron, dataChannel, dataStreamId, ackChannel, ackStreamId, leaderEpoch,
            ackMode, failurePolicy, ackTimeoutMs, shadow, peerAuthenticated, "", "");
    }

    public AeronReplicator(Aeron aeron, String dataChannel, int dataStreamId,
                           String ackChannel, int ackStreamId, long leaderEpoch,
                           ReplicationAckMode ackMode, ReplicationFailurePolicy failurePolicy,
                           long ackTimeoutMs, boolean shadow, BooleanSupplier peerAuthenticated,
                           String dataLiveChannel) {
        this(aeron, dataChannel, dataStreamId, ackChannel, ackStreamId, leaderEpoch,
            ackMode, failurePolicy, ackTimeoutMs, shadow, peerAuthenticated, "",
            dataLiveChannel);
    }

    public AeronReplicator(Aeron aeron, String dataChannel, int dataStreamId,
                           String ackChannel, int ackStreamId, long leaderEpoch,
                           ReplicationAckMode ackMode, ReplicationFailurePolicy failurePolicy,
                           long ackTimeoutMs, boolean shadow, BooleanSupplier peerAuthenticated,
                           String archiveDestination, String liveDestination) {
        if (leaderEpoch < 0 || leaderEpoch > 0xffff_ffffL) {
            throw new IllegalArgumentException("leaderEpoch must fit uint32");
        }
        this.dataPublication = aeron.addExclusivePublication(dataChannel, dataStreamId);
        addDestination(archiveDestination);
        addDestination(liveDestination);
        this.ackSubscription = aeron.addSubscription(ackChannel, ackStreamId);
        this.leaderEpoch = leaderEpoch;
        this.ackMode = ackMode;
        this.failurePolicy = failurePolicy;
        this.ackTimeoutNs = Math.max(1L, ackTimeoutMs) * 1_000_000L;
        this.shadow = shadow;
        this.peerAuthenticated = peerAuthenticated;
    }

    /** Live-peer destination queued for the single-writer thread to attach (the publication is
     *  not thread-safe, so the retry thread must not touch it). Lets the archive leg record from
     *  the stream origin while the peer is still unresolvable (crashed pod without a DNS entry). */
    private volatile String pendingLiveDestination;

    public void queueLiveDestination(String destination) {
        if (destination != null && !destination.isBlank()) {
            pendingLiveDestination = destination;
        }
    }

    @Override
    public void onEvent(InputEvent event, long sequence, boolean endOfBatch) {
        String queuedDestination = pendingLiveDestination;
        if (queuedDestination != null) {
            pendingLiveDestination = null;
            dataPublication.addDestination(queuedDestination);
        }
        pollAcks();
        // One MDC publication fans each claimed frame to the local Archive and peer follower.
        // Both legs therefore share one session and one position space; the Archive recording is
        // the live stream ReplayMerge later joins.
        //
        // The wire inputSeq is the event's BUSINESS sequence (event.seq), not the raw Disruptor
        // sequence: the ring restarts at -1 on every reboot and continues per-pod on promotion,
        // while the replicated stream's numbering must stay continuous across the whole leader
        // lineage (the engine stamps event.seq with its lineage base at ingress). Follower ACKs
        // carry the same business sequence, so all comparisons here use event.seq.
        long inputSeq = event.seq;
        if (!peerAuthenticated.getAsBoolean()) degraded = true;
        long result;
        long deadline = System.nanoTime() + ackTimeoutNs;
        do {
            result = dataPublication.tryClaim(AeronReplicationCodec.INPUT_BYTES, dataClaim);
            if (result >= 0) break;
            pollAcks();
            if (shadow || System.nanoTime() >= deadline) {
                onOfferFailure(inputSeq, result);
                if (endOfBatch && !shadow) awaitRequiredAck(inputSeq);
                return;
            }
            Thread.onSpinWait();
        } while (true);

        int flags = shadow ? AeronReplicationCodec.INPUT_FLAG_SHADOW : 0;
        dataCodec.encodeInput(dataClaim.buffer(), dataClaim.offset(), event, inputSeq,
            leaderEpoch, flags);
        long checksum = AeronReplicationCodec.checksum64(
            dataClaim.buffer(), dataClaim.offset(), AeronReplicationCodec.INPUT_BYTES);
        dataClaim.commit();
        AeronPublicationSequenceMap boundaryMap = publicationSequenceMap;
        if (boundaryMap != null) {
            // tryClaim's successful result is the position immediately after this frame. Capturing
            // it here is exact; publication.position() sampled later by the BLP may include frames
            // the upstream replication handler has already published.
            boundaryMap.put(sequence, leaderEpoch, inputSeq, result, checksum,
                dataPublication.sessionId());
        }
        if (event.type == InputEvent.TYPE_SNAPSHOT) {
            snapshotEpoch = leaderEpoch;
            snapshotInputSeq = inputSeq;
            snapshotRecordingPosition = result;
            snapshotChecksum = checksum;
            snapshotDataSessionId = dataPublication.sessionId();
            snapshotLocalSeq = sequence;
        }
        publishedSeq = inputSeq;

        if (endOfBatch && !shadow) awaitRequiredAck(inputSeq);
    }

    private void onOfferFailure(long sequence, long result) {
        offerFailures.increment();
        degraded = true;
        if (failurePolicy == ReplicationFailurePolicy.STRICT && !shadow) {
            throw new IllegalStateException(
                "strict Aeron replication offer failed at seq=" + sequence + " result=" + result);
        }
        // Degraded-solo explicitly makes the local post-force journal the durability authority.
        publishedSeq = sequence;
    }

    private void addDestination(String destination) {
        if (destination != null && !destination.isBlank()) {
            dataPublication.addDestination(destination);
        }
    }

    private void awaitRequiredAck(long sequence) {
        boolean followerActive = lastAckNs > 0 && System.nanoTime() - lastAckNs <= ackTimeoutNs;
        if (!followerActive && failurePolicy == ReplicationFailurePolicy.DEGRADED_SOLO) {
            degraded = true;
            return;
        }

        long deadline = System.nanoTime() + ackTimeoutNs;
        while (followerAckedSeq < sequence) {
            pollAcks();
            if (System.nanoTime() >= deadline) {
                degraded = true;
                if (failurePolicy == ReplicationFailurePolicy.STRICT) {
                    throwStrictAckTimeout(sequence);
                }
                return;
            }
            Thread.onSpinWait();
        }
        degraded = false;
    }

    // Keep diagnostic String construction outside the exact-zero ACK polling method. C2 can
    // otherwise rematerialize the cold concat graph during an uncommon trap even when this
    // DEGRADED_SOLO instance never takes the strict branch.
    private void throwStrictAckTimeout(long sequence) {
        throw new IllegalStateException(
            "strict Aeron replication ACK timeout at seq=" + sequence
                + " acked=" + followerAckedSeq + " mode=" + ackMode);
    }

    private void pollAcks() {
        ackSubscription.poll(ackHandler, 32);
    }

    /** Maintenance hook for idle periods and deterministic transport tests. Call from one agent. */
    public void pollAcksOnce() { pollAcks(); }

    private void onAck(org.agrona.DirectBuffer buffer, int offset, int length,
                       io.aeron.logbuffer.Header header) {
        if (ackCodec.tryDecodeAck(buffer, offset, length) != AeronReplicationCodec.OK
            || ackCodec.leaderEpoch() != leaderEpoch) {
            invalidAcks.increment();
            return;
        }
        long required = ackMode == ReplicationAckMode.DURABLE
            ? AeronReplicationCodec.ACK_JOURNALED
            : AeronReplicationCodec.ACK_ON_RING;
        if ((ackCodec.ackFlags() & required) == 0) return;
        long seq = ackCodec.inputSeq();
        if (seq >= followerAckedSeq) {
            followerAckedSeq = seq;
            lastAckNs = System.nanoTime();
            degraded = false;
        }
    }

    @Override
    public long replicatedSeq() {
        long ackNs = lastAckNs;
        if (ackNs > 0 && System.nanoTime() - ackNs <= ackTimeoutNs) return followerAckedSeq;
        return failurePolicy == ReplicationFailurePolicy.DEGRADED_SOLO ? publishedSeq : followerAckedSeq;
    }

    @Override
    public boolean degraded() { return degraded; }
    public long followerAckedSeq() { return followerAckedSeq; }
    public long offerFailureCount() { return offerFailures.sum(); }
    public long invalidAckCount() { return invalidAcks.sum(); }
    public int publicationSessionId() { return dataPublication.sessionId(); }
    public long publicationPosition() { return dataPublication.position(); }
    public void setPublicationSequenceMap(AeronPublicationSequenceMap map) {
        this.publicationSequenceMap = map;
    }

    /**
     * Dedicated marker register. Unlike the general overwrite map, this cannot be displaced by
     * upstream run-ahead before the BLP's snapshot callback observes the marker.
     */
    public boolean readSnapshotBoundary(long localSeq, FollowerSequenceMap.Entry target) {
        if (snapshotLocalSeq != localSeq) return false;
        target.localSeq = localSeq;
        target.epoch = snapshotEpoch;
        target.inputSeq = snapshotInputSeq;
        target.recordingPosition = snapshotRecordingPosition;
        target.checksum = snapshotChecksum;
        target.dataSessionId = snapshotDataSessionId;
        return true;
    }
    public boolean archiveConnected() { return dataPublication.isConnected(); }
    public boolean connected() { return dataPublication.isConnected(); }

    /**
     * Wait outside the Disruptor path for the local Archive destination. The peer may be absent in
     * degraded-solo mode, but the single MDC publication must have its retention destination before
     * the ring starts.
     */
    public boolean awaitConnected(long timeoutMs) {
        long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMs));
        while (!dataPublication.isConnected() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(100_000L);
        }
        return dataPublication.isConnected();
    }

    /** Compatibility name for callers that explicitly describe the retained Archive barrier. */
    public boolean awaitArchiveConnected(long timeoutMs) {
        return awaitConnected(timeoutMs);
    }

    @Override
    public void close() {
        dataPublication.close();
        ackSubscription.close();
    }
}
