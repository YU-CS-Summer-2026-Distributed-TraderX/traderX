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

/** Follower-side reliable-unicast poll/decode/ring-injection agent for the Aeron transport. */
public final class AeronReplicationFollower implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AeronReplicationFollower.class);

    public static final int FAULT_NONE = 0;
    public static final int FAULT_WIRE = 1;
    public static final int FAULT_EPOCH = 2;
    public static final int FAULT_GAP = 3;
    public static final int FAULT_DUPLICATE_MISMATCH = 4;

    private final Subscription dataSubscription;
    private final ExclusivePublication ackPublication;
    private final long expectedEpoch;
    private final ReplicationAckMode ackMode;
    private final long offerTimeoutNs;
    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final FollowerSequenceMap sequenceMap;
    private final BufferClaim ackClaim = new BufferClaim();
    private final FragmentHandler fragmentHandler = this::onFragment;
    private final LongAdder invalidFrames = new LongAdder();

    private volatile RingBuffer<InputEvent> inputRing;
    private volatile boolean running;
    private volatile int faultCode;
    private volatile long lastInputSeq = -1;
    private volatile long lastChecksum;
    private volatile long lastLocalSeq = -1;
    private Thread pollThread;
    private AeronDurableAckAgent durableAckAgent;
    private Runnable readyCallback;
    private Runnable faultCallback;

    public AeronReplicationFollower(Aeron aeron, String dataChannel, int dataStreamId,
                                    String ackChannel, int ackStreamId, long expectedEpoch,
                                    ReplicationAckMode ackMode, int mappingCapacity,
                                    long offerTimeoutMs) {
        this.dataSubscription = aeron.addSubscription(dataChannel, dataStreamId);
        this.ackPublication = aeron.addExclusivePublication(ackChannel, ackStreamId);
        this.expectedEpoch = expectedEpoch;
        this.ackMode = ackMode;
        this.sequenceMap = new FollowerSequenceMap(mappingCapacity);
        this.offerTimeoutNs = Math.max(1L, offerTimeoutMs) * 1_000_000L;
    }

    public void setInputRing(RingBuffer<InputEvent> ring) { this.inputRing = ring; }

    public void setDurabilityWatermarks(LongSupplier journaledSeq, LongSupplier journalForceNanos,
                                        LongSupplier appliedSeq) {
        if (ackMode == ReplicationAckMode.DURABLE) {
            durableAckAgent = new AeronDurableAckAgent(ackPublication, sequenceMap,
                journaledSeq, journalForceNanos, appliedSeq, TimeUnit.NANOSECONDS.toMillis(offerTimeoutNs));
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
        boolean readySignalled = false;
        while (running) {
            int fragments = dataSubscription.poll(fragmentHandler, 256);
            if (!readySignalled && faultCode == FAULT_NONE
                && dataSubscription.isConnected() && ackPublication.isConnected()) {
                readySignalled = true;
                readyCallback.run();
            }
            if (fragments == 0) LockSupport.parkNanos(50_000L);
        }
    }

    private void onFragment(org.agrona.DirectBuffer buffer, int offset, int length,
                            io.aeron.logbuffer.Header header) {
        if (faultCode != FAULT_NONE) return;
        int status = codec.tryInspectInput(buffer, offset, length);
        if (status != AeronReplicationCodec.OK) {
            invalidFrames.increment();
            fault(FAULT_WIRE);
            return;
        }
        if (codec.leaderEpoch() != expectedEpoch) {
            fault(FAULT_EPOCH);
            return;
        }

        long inputSeq = codec.inputSeq();
        long checksum = AeronReplicationCodec.checksum64(buffer, offset, length);
        if (lastInputSeq >= 0 && inputSeq <= lastInputSeq) {
            if (inputSeq == lastInputSeq && checksum == lastChecksum) return;
            fault(FAULT_DUPLICATE_MISMATCH);
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
            while (running && !sequenceMap.put(localSeq, expectedEpoch, inputSeq,
                header.position(), checksum)) {
                Thread.onSpinWait();
            }
            if (!running) return;
        } finally {
            ring.publish(localSeq);
        }

        lastLocalSeq = localSeq;
        lastInputSeq = inputSeq;
        lastChecksum = checksum;
        if (ackMode == ReplicationAckMode.ON_RING) {
            offerOnRingAck(expectedEpoch, inputSeq, header.position());
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
            code, expectedEpoch, lastInputSeq);
        if (faultCallback != null) faultCallback.run();
    }

    public boolean healthy() { return faultCode == FAULT_NONE; }
    public int faultCode() { return faultCode; }
    public long lastInputSeq() { return lastInputSeq; }
    public long lastLocalSeq() { return lastLocalSeq; }
    public long invalidFrameCount() { return invalidFrames.sum(); }
    public FollowerSequenceMap sequenceMap() { return sequenceMap; }

    @Override
    public void close() {
        running = false;
        if (pollThread != null) pollThread.interrupt();
        if (durableAckAgent != null) durableAckAgent.close();
        dataSubscription.close();
        ackPublication.close();
    }
}
