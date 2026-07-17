package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.RingBuffer;
import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.FragmentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;

/** Follower-side reliable-unicast poll/decode/ring-injection agent for the Aeron transport. */
public final class AeronReplicationFollower implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AeronReplicationFollower.class);

    public static final int FAULT_NONE = 0;
    public static final int FAULT_WIRE = 1;
    public static final int FAULT_EPOCH = 2;
    public static final int FAULT_GAP = 3;
    public static final int FAULT_DUPLICATE_MISMATCH = 4;
    public static final int FAULT_ARCHIVE = 5;

    private final Aeron aeron;
    private final Subscription dataSubscription;
    private final ExclusivePublication ackPublication;
    private final LongSupplier expectedEpoch;
    private final ReplicationAckMode ackMode;
    private final long offerTimeoutNs;
    private final BooleanSupplier peerAuthenticated;
    private final AeronArchiveReplayMerge.Config archiveConfig;
    private final AeronFollowerCheckpointStore.Record initialCheckpoint;
    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final FollowerSequenceMap sequenceMap;
    private final BufferClaim ackClaim = new BufferClaim();
    private final FragmentHandler fragmentHandler = this::onFragment;
    private final LongAdder invalidFrames = new LongAdder();

    private volatile RingBuffer<InputEvent> inputRing;
    private volatile LongSupplier primaryHighWatermark = () -> -1L;
    private volatile LongSupplier primaryRecordingPosition = () -> -1L;
    /** First input sequence this checkpoint-less follower may accept. 0 = the stream origin
     *  (legacy contract); a cross-epoch bootstrap sets the new epoch's first sequence after the
     *  engine has proven local state through exactly that boundary minus one. */
    private volatile long expectedFirstInputSeq;
    private volatile boolean running;
    private volatile int faultCode;
    private volatile long lastInputSeq = -1;
    private volatile long lastChecksum;
    private volatile long lastLocalSeq = -1;
    private volatile long lastRecordingPosition = -1L;
    private volatile int lastDataSessionId = -1;
    private volatile boolean archiveReplaying;
    private volatile long pollThreadId;
    private volatile LongSupplier journaledWatermark = () -> -1L;
    private volatile LongSupplier appliedWatermark = () -> -1L;
    private Thread pollThread;
    private AeronDurableAckAgent durableAckAgent;
    private AeronArchiveReplayMerge archiveReplayMerge;
    private Runnable readyCallback;
    private Runnable faultCallback;

    public AeronReplicationFollower(Aeron aeron, String dataChannel, int dataStreamId,
                                    String ackChannel, int ackStreamId, long expectedEpoch,
                                    ReplicationAckMode ackMode, int mappingCapacity,
                                    long offerTimeoutMs) {
        this(aeron, dataChannel, dataStreamId, ackChannel, ackStreamId, expectedEpoch,
            ackMode, mappingCapacity, offerTimeoutMs, () -> true, null, null);
    }

    public AeronReplicationFollower(Aeron aeron, String dataChannel, int dataStreamId,
                                    String ackChannel, int ackStreamId, long expectedEpoch,
                                    ReplicationAckMode ackMode, int mappingCapacity,
                                    long offerTimeoutMs, BooleanSupplier peerAuthenticated) {
        this(aeron, dataChannel, dataStreamId, ackChannel, ackStreamId, expectedEpoch,
            ackMode, mappingCapacity, offerTimeoutMs, peerAuthenticated, null, null);
    }

    public AeronReplicationFollower(Aeron aeron, String dataChannel, int dataStreamId,
                                    String ackChannel, int ackStreamId, long expectedEpoch,
                                    ReplicationAckMode ackMode, int mappingCapacity,
                                    long offerTimeoutMs, BooleanSupplier peerAuthenticated,
                                    AeronArchiveReplayMerge.Config archiveConfig,
                                    AeronFollowerCheckpointStore.Record initialCheckpoint) {
        this(aeron, dataChannel, dataStreamId, ackChannel, ackStreamId, () -> expectedEpoch,
            ackMode, mappingCapacity, offerTimeoutMs, peerAuthenticated,
            archiveConfig, initialCheckpoint);
    }

    public AeronReplicationFollower(Aeron aeron, String dataChannel, int dataStreamId,
                                    String ackChannel, int ackStreamId,
                                    LongSupplier expectedEpoch,
                                    ReplicationAckMode ackMode, int mappingCapacity,
                                    long offerTimeoutMs, BooleanSupplier peerAuthenticated,
                                    AeronArchiveReplayMerge.Config archiveConfig,
                                    AeronFollowerCheckpointStore.Record initialCheckpoint) {
        this.aeron = aeron;
        this.archiveConfig = archiveConfig;
        this.initialCheckpoint = initialCheckpoint;
        this.dataSubscription = archiveConfig == null
            ? aeron.addSubscription(dataChannel, dataStreamId) : null;
        this.ackPublication = aeron.addExclusivePublication(ackChannel, ackStreamId);
        this.expectedEpoch = expectedEpoch;
        this.ackMode = ackMode;
        this.sequenceMap = new FollowerSequenceMap(mappingCapacity);
        this.offerTimeoutNs = Math.max(1L, offerTimeoutMs) * 1_000_000L;
        this.peerAuthenticated = peerAuthenticated;
        if (initialCheckpoint != null) {
            lastInputSeq = initialCheckpoint.inputSeq();
            lastChecksum = initialCheckpoint.payloadChecksum();
            lastRecordingPosition = initialCheckpoint.recordingPosition();
            lastDataSessionId = initialCheckpoint.dataSessionId();
        }
    }

    public void setInputRing(RingBuffer<InputEvent> ring) { this.inputRing = ring; }
    public void setExpectedFirstInputSeq(long inputSeq) {
        this.expectedFirstInputSeq = Math.max(0L, inputSeq);
    }
    public void setPrimaryHighWatermark(LongSupplier watermark) {
        this.primaryHighWatermark = watermark == null ? () -> -1L : watermark;
    }
    public void setPrimaryRecordingPosition(LongSupplier position) {
        this.primaryRecordingPosition = position == null ? () -> -1L : position;
    }

    public void setDurabilityWatermarks(LongSupplier journaledSeq, LongSupplier journalForceNanos,
                                        LongSupplier appliedSeq) {
        setDurabilityWatermarks(journaledSeq, journalForceNanos, appliedSeq, null, () -> { });
    }

    public void setDurabilityWatermarks(LongSupplier journaledSeq, LongSupplier journalForceNanos,
                                        LongSupplier appliedSeq, java.nio.file.Path checkpointPath,
                                        Runnable checkpointFault) {
        try {
            this.journaledWatermark = journaledSeq;
            this.appliedWatermark = appliedSeq;
            AeronFollowerCheckpointStore store = checkpointPath == null
                ? null : new AeronFollowerCheckpointStore(checkpointPath);
            durableAckAgent = new AeronDurableAckAgent(ackPublication, sequenceMap,
                journaledSeq, journalForceNanos, appliedSeq,
                TimeUnit.NANOSECONDS.toMillis(offerTimeoutNs),
                ackMode == ReplicationAckMode.DURABLE, store, checkpointFault);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("cannot open Aeron follower checkpoint", ex);
        }
    }

    public void start(Runnable readyCallback, Runnable faultCallback) {
        if (inputRing == null) throw new IllegalStateException("input ring must be installed before start");
        if (ackMode == ReplicationAckMode.DURABLE && durableAckAgent == null) {
            throw new IllegalStateException("durable ACK watermarks must be installed before start");
        }
        this.readyCallback = readyCallback;
        this.faultCallback = faultCallback;
        running = true;
        if (durableAckAgent != null) durableAckAgent.start();
        pollThread = new Thread(this::pollLoop, "blp-aeron-replication-follower");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void pollLoop() {
        pollThreadId = Thread.currentThread().threadId();
        if (archiveConfig != null) {
            log.info("Waiting for authenticated Aeron peer before Archive catch-up");
            while (running && !peerAuthenticated.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
            if (!running) return;
            long negotiatedEpoch = expectedEpoch.getAsLong();
            AeronFollowerCheckpointStore.Record replayCheckpoint = initialCheckpoint;
            if (replayCheckpoint != null && replayCheckpoint.epoch() != negotiatedEpoch) {
                replayCheckpoint = null;
                lastInputSeq = -1L;
                lastChecksum = 0L;
            }
            try {
                ReplayTarget target = stablePrimaryReplayTarget();
                log.info("Starting Aeron Archive catch-up epoch={} checkpointInputSeq={} "
                        + "primaryInputSeq={} primaryRecordingPosition={}",
                    negotiatedEpoch, replayCheckpoint == null ? -1L : replayCheckpoint.inputSeq(),
                    target.inputSeq(), target.recordingPosition());
                archiveReplaying = true;
                archiveReplayMerge = new AeronArchiveReplayMerge(aeron, archiveConfig,
                    replayCheckpoint, target.recordingPosition());
            } catch (RuntimeException ex) {
                log.error("Aeron Archive catch-up initialization failed", ex);
                fault(FAULT_ARCHIVE);
                return;
            }
        }
        boolean readySignalled = false;
        long nextArchiveStatusNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (running) {
            int fragments;
            try {
                fragments = archiveReplayMerge == null
                    ? dataSubscription.poll(fragmentHandler, 256)
                    : archiveReplayMerge.poll(fragmentHandler, 256);
            } catch (RuntimeException ex) {
                log.error("Aeron Archive replay-to-live merge failed", ex);
                fault(FAULT_ARCHIVE);
                return;
            }
            if (archiveReplayMerge != null && archiveReplayMerge.isMerged()) {
                archiveReplaying = false;
            }
            if (archiveReplayMerge != null && archiveReplaying
                && System.nanoTime() >= nextArchiveStatusNs) {
                log.info("Aeron Archive catch-up status: {}", archiveReplayMerge.status());
                nextArchiveStatusNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
            }
            boolean dataLive = archiveReplayMerge == null
                ? dataSubscription.isConnected() : archiveReplayMerge.isMerged();
            long primaryHigh = primaryHighWatermark.getAsLong();
            boolean caughtUpToPrimary = primaryHigh < 0L || lastInputSeq >= primaryHigh;
            boolean appliedThroughTail = lastLocalSeq < 0
                || (journaledWatermark.getAsLong() >= lastLocalSeq
                    && appliedWatermark.getAsLong() >= lastLocalSeq);
            if (!readySignalled && faultCode == FAULT_NONE
                && peerAuthenticated.getAsBoolean()
                && dataLive && caughtUpToPrimary && appliedThroughTail
                && ackPublication.isConnected()) {
                readySignalled = true;
                readyCallback.run();
            }
            if (fragments == 0) LockSupport.parkNanos(50_000L);
        }
    }

    private ReplayTarget stablePrimaryReplayTarget() {
        while (running) {
            long firstInputSeq = primaryHighWatermark.getAsLong();
            long recordingPosition = primaryRecordingPosition.getAsLong();
            long secondInputSeq = primaryHighWatermark.getAsLong();
            if (firstInputSeq == secondInputSeq
                && (firstInputSeq < 0L || recordingPosition >= 0L)) {
                return new ReplayTarget(firstInputSeq, recordingPosition);
            }
            LockSupport.parkNanos(100_000L);
        }
        return new ReplayTarget(-1L, -1L);
    }

    private record ReplayTarget(long inputSeq, long recordingPosition) { }

    private void onFragment(org.agrona.DirectBuffer buffer, int offset, int length,
                            io.aeron.logbuffer.Header header) {
        if (faultCode != FAULT_NONE) return;
        if (!peerAuthenticated.getAsBoolean()) return;
        int status = codec.tryInspectInput(buffer, offset, length);
        if (status != AeronReplicationCodec.OK) {
            invalidFrames.increment();
            fault(FAULT_WIRE);
            return;
        }
        long epoch = expectedEpoch.getAsLong();
        if (codec.leaderEpoch() != epoch) {
            fault(FAULT_EPOCH);
            return;
        }

        long inputSeq = codec.inputSeq();
        long checksum = AeronReplicationCodec.checksum64(buffer, offset, length);
        if (lastInputSeq >= 0 && inputSeq <= lastInputSeq) {
            if (archiveReplaying && inputSeq < lastInputSeq) return;
            if (inputSeq == lastInputSeq && checksum == lastChecksum) return;
            fault(FAULT_DUPLICATE_MISMATCH);
            return;
        }
        if (lastInputSeq < 0 && archiveConfig != null && inputSeq != expectedFirstInputSeq) {
            fault(FAULT_GAP);
            return;
        }
        if (lastInputSeq >= 0 && inputSeq != lastInputSeq + 1) {
            fault(FAULT_GAP);
            return;
        }

        RingBuffer<InputEvent> ring = inputRing;
        long localSeq = ring.next();
        try {
            codec.decodeInspectedInput(ring.get(localSeq));
            while (running && !sequenceMap.put(localSeq, epoch, inputSeq,
                header.position(), checksum, header.sessionId())) {
                Thread.onSpinWait();
            }
            if (!running) return;
        } finally {
            ring.publish(localSeq);
        }

        lastLocalSeq = localSeq;
        lastInputSeq = inputSeq;
        lastChecksum = checksum;
        lastRecordingPosition = header.position();
        lastDataSessionId = header.sessionId();
        if (ackMode == ReplicationAckMode.ON_RING) {
            offerOnRingAck(epoch, inputSeq, header.position());
            if (durableAckAgent == null) sequenceMap.consumed(localSeq);
        }
    }

    private void offerOnRingAck(long epoch, long inputSeq, long recordingPosition) {
        long deadline = System.nanoTime() + offerTimeoutNs;
        while (running) {
            long result = ackPublication.tryClaim(AeronReplicationCodec.ACK_BYTES, ackClaim);
            if (result >= 0) {
                codec.encodeAck(ackClaim.buffer(), ackClaim.offset(), epoch,
                    AeronReplicationCodec.ACK_ON_RING, inputSeq, recordingPosition, 0L);
                ackClaim.commit();
                return;
            }
            if (System.nanoTime() >= deadline) return;
            Thread.onSpinWait();
        }
    }

    private void fault(int code) {
        faultCode = code;
        running = false;
        log.error("Aeron follower protocol fault: code={} epoch={} lastInputSeq={}",
            code, expectedEpoch.getAsLong(), lastInputSeq);
        if (faultCallback != null) faultCallback.run();
    }

    public boolean healthy() { return faultCode == FAULT_NONE; }
    public int faultCode() { return faultCode; }
    public long lastInputSeq() { return lastInputSeq; }
    public long lastLocalSeq() { return lastLocalSeq; }
    public long invalidFrameCount() { return invalidFrames.sum(); }
    public FollowerSequenceMap sequenceMap() { return sequenceMap; }
    public long pollThreadId() { return pollThreadId; }
    public boolean archiveReplaying() { return archiveReplaying; }
    public boolean archiveMerged() {
        AeronArchiveReplayMerge replay = archiveReplayMerge;
        return replay != null && replay.isMerged();
    }
    public long durableAckedInputSeq() {
        AeronDurableAckAgent agent = durableAckAgent;
        return agent == null ? -1L : agent.lastAckedInputSeq();
    }

    @Override
    public void close() {
        running = false;
        if (pollThread != null) pollThread.interrupt();
        if (durableAckAgent != null) durableAckAgent.close();
        if (archiveReplayMerge != null) archiveReplayMerge.close();
        if (dataSubscription != null) dataSubscription.close();
        ackPublication.close();
    }
}
