package finos.traderx.ordermatcher.lmax;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.FragmentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.LongAdder;

/**
 * Primary-side Aeron replication handler. It encodes the SBE record directly into a claimed
 * publication term buffer and coalesces follower ACK waits at Disruptor batch boundaries.
 */
public final class AeronReplicator implements ReplicationEventHandler {
    private static final Logger log = LoggerFactory.getLogger(AeronReplicator.class);

    private final ExclusivePublication dataPublication;
    private final Subscription ackSubscription;
    private final long leaderEpoch;
    private final ReplicationAckMode ackMode;
    private final ReplicationFailurePolicy failurePolicy;
    private final long ackTimeoutNs;
    private final boolean shadow;
    private final AeronReplicationCodec dataCodec = new AeronReplicationCodec();
    private final AeronReplicationCodec ackCodec = new AeronReplicationCodec();
    private final BufferClaim claim = new BufferClaim();
    private final FragmentHandler ackHandler = this::onAck;
    private final LongAdder offerFailures = new LongAdder();
    private final LongAdder invalidAcks = new LongAdder();

    private volatile long publishedSeq = -1;
    private volatile long followerAckedSeq = -1;
    private volatile long lastAckNs;
    private volatile boolean degraded;

    public AeronReplicator(Aeron aeron, String dataChannel, int dataStreamId,
                           String ackChannel, int ackStreamId, long leaderEpoch,
                           ReplicationAckMode ackMode, ReplicationFailurePolicy failurePolicy,
                           long ackTimeoutMs, boolean shadow) {
        if (leaderEpoch < 0 || leaderEpoch > 0xffff_ffffL) {
            throw new IllegalArgumentException("leaderEpoch must fit uint32");
        }
        this.dataPublication = aeron.addExclusivePublication(dataChannel, dataStreamId);
        this.ackSubscription = aeron.addSubscription(ackChannel, ackStreamId);
        this.leaderEpoch = leaderEpoch;
        this.ackMode = ackMode;
        this.failurePolicy = failurePolicy;
        this.ackTimeoutNs = Math.max(1L, ackTimeoutMs) * 1_000_000L;
        this.shadow = shadow;
    }

    @Override
    public void onEvent(InputEvent event, long sequence, boolean endOfBatch) {
        pollAcks();
        long result;
        long deadline = System.nanoTime() + ackTimeoutNs;
        do {
            result = dataPublication.tryClaim(AeronReplicationCodec.INPUT_BYTES, claim);
            if (result >= 0) break;
            pollAcks();
            if (shadow || System.nanoTime() >= deadline) {
                onOfferFailure(sequence, result);
                if (endOfBatch && !shadow) awaitRequiredAck(sequence);
                return;
            }
            Thread.onSpinWait();
        } while (true);

        int flags = shadow ? AeronReplicationCodec.INPUT_FLAG_SHADOW : 0;
        dataCodec.encodeInput(claim.buffer(), claim.offset(), event, sequence, leaderEpoch, flags);
        claim.commit();
        publishedSeq = sequence;

        if (endOfBatch && !shadow) awaitRequiredAck(sequence);
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
                    throw new IllegalStateException(
                        "strict Aeron replication ACK timeout at seq=" + sequence
                            + " acked=" + followerAckedSeq + " mode=" + ackMode);
                }
                return;
            }
            Thread.onSpinWait();
        }
        degraded = false;
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

    @Override
    public void close() {
        dataPublication.close();
        ackSubscription.close();
    }
}
