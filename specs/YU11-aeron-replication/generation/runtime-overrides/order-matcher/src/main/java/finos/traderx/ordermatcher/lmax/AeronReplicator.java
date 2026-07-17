package finos.traderx.ordermatcher.lmax;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.FragmentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Primary-side Aeron replication handler. It encodes the SBE record directly into a claimed
 * publication term buffer and coalesces follower ACK waits at Disruptor batch boundaries.
 */
public final class AeronReplicator implements ReplicationEventHandler {
    private static final Logger log = LoggerFactory.getLogger(AeronReplicator.class);

    private final ExclusivePublication dataPublication;
    private final ExclusivePublication livePublication;
    private final Subscription ackSubscription;
    private final long leaderEpoch;
    private final ReplicationAckMode ackMode;
    private final ReplicationFailurePolicy failurePolicy;
    private final long ackTimeoutNs;
    private final boolean shadow;
    private final BooleanSupplier peerAuthenticated;
    private final AeronReplicationCodec dataCodec = new AeronReplicationCodec();
    private final AeronReplicationCodec ackCodec = new AeronReplicationCodec();
    private final BufferClaim archiveClaim = new BufferClaim();
    private final BufferClaim liveClaim = new BufferClaim();
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
        this(aeron, dataChannel, dataStreamId, ackChannel, ackStreamId, leaderEpoch,
            ackMode, failurePolicy, ackTimeoutMs, shadow, () -> true, "");
    }

    public AeronReplicator(Aeron aeron, String dataChannel, int dataStreamId,
                           String ackChannel, int ackStreamId, long leaderEpoch,
                           ReplicationAckMode ackMode, ReplicationFailurePolicy failurePolicy,
                           long ackTimeoutMs, boolean shadow, BooleanSupplier peerAuthenticated) {
        this(aeron, dataChannel, dataStreamId, ackChannel, ackStreamId, leaderEpoch,
            ackMode, failurePolicy, ackTimeoutMs, shadow, peerAuthenticated, "");
    }

    public AeronReplicator(Aeron aeron, String dataChannel, int dataStreamId,
                           String ackChannel, int ackStreamId, long leaderEpoch,
                           ReplicationAckMode ackMode, ReplicationFailurePolicy failurePolicy,
                           long ackTimeoutMs, boolean shadow, BooleanSupplier peerAuthenticated,
                           String dataLiveChannel) {
        if (leaderEpoch < 0 || leaderEpoch > 0xffff_ffffL) {
            throw new IllegalArgumentException("leaderEpoch must fit uint32");
        }
        this.dataPublication = aeron.addExclusivePublication(dataChannel, dataStreamId);
        this.livePublication = dataLiveChannel == null || dataLiveChannel.isBlank() ? null
            : aeron.addExclusivePublication(withSessionId(dataLiveChannel,
                dataPublication.sessionId()), dataStreamId);
        this.ackSubscription = aeron.addSubscription(ackChannel, ackStreamId);
        this.leaderEpoch = leaderEpoch;
        this.ackMode = ackMode;
        this.failurePolicy = failurePolicy;
        this.ackTimeoutNs = Math.max(1L, ackTimeoutMs) * 1_000_000L;
        this.shadow = shadow;
        this.peerAuthenticated = peerAuthenticated;
    }

    @Override
    public void onEvent(InputEvent event, long sequence, boolean endOfBatch) {
        pollAcks();
        // Always publish to the local Archive IPC leg. The direct peer UDP leg shares its session
        // id, so ReplayMerge can move from the retained recording to the live unicast image.
        if (!peerAuthenticated.getAsBoolean()) degraded = true;
        long result;
        long deadline = System.nanoTime() + ackTimeoutNs;
        do {
            result = dataPublication.tryClaim(AeronReplicationCodec.INPUT_BYTES, archiveClaim);
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
        dataCodec.encodeInput(archiveClaim.buffer(), archiveClaim.offset(), event, sequence,
            leaderEpoch, flags);
        archiveClaim.commit();
        publishedSeq = sequence;

        if (livePublication != null) {
            long liveResult;
            long liveDeadline = System.nanoTime() + ackTimeoutNs;
            do {
                liveResult = livePublication.tryClaim(AeronReplicationCodec.INPUT_BYTES, liveClaim);
                if (liveResult >= 0L) break;
                pollAcks();
                if (shadow || System.nanoTime() >= liveDeadline) {
                    onLiveOfferFailure(sequence, liveResult);
                    if (endOfBatch && !shadow) awaitRequiredAck(sequence);
                    return;
                }
                Thread.onSpinWait();
            } while (true);
            dataCodec.encodeInput(liveClaim.buffer(), liveClaim.offset(), event, sequence,
                leaderEpoch, flags);
            liveClaim.commit();
        }

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

    private void onLiveOfferFailure(long sequence, long result) {
        offerFailures.increment();
        degraded = true;
        log.warn("Aeron live publication failed at seq={} result={}", sequence, result);
        if (failurePolicy == ReplicationFailurePolicy.STRICT && !shadow) {
            throw new IllegalStateException(
                "strict Aeron live offer failed at seq=" + sequence + " result=" + result);
        }
    }

    private static String withSessionId(String channel, int sessionId) {
        if (channel.contains("session-id=")) return channel;
        return channel + (channel.indexOf('?') >= 0 ? "|" : "?") + "session-id=" + sessionId;
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
    public boolean archiveConnected() { return dataPublication.isConnected(); }
    public boolean connected() {
        return archiveConnected()
            && (livePublication == null || livePublication.isConnected());
    }

    /**
     * Wait outside the Disruptor path for both the local Archive leg and the peer-live leg. Without
     * this startup barrier degraded-solo can consume the first ring sequences before either
     * publication has an image, leaving a later healthy follower with an irrecoverable initial gap.
     */
    public boolean awaitConnected(long timeoutMs) {
        return awaitConnection(timeoutMs, true);
    }

    /** Promotion must fence and resume before the dead peer returns; only local Archive is needed. */
    public boolean awaitArchiveConnected(long timeoutMs) {
        return awaitConnection(timeoutMs, false);
    }

    private boolean awaitConnection(long timeoutMs, boolean requirePeerLive) {
        long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMs));
        while (!(requirePeerLive ? connected() : archiveConnected())
            && System.nanoTime() < deadline) {
            LockSupport.parkNanos(100_000L);
        }
        return requirePeerLive ? connected() : archiveConnected();
    }

    @Override
    public void close() {
        dataPublication.close();
        if (livePublication != null) livePublication.close();
        ackSubscription.close();
    }
}
