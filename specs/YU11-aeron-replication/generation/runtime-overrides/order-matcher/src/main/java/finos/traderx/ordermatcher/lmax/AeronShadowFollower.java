package finos.traderx.ordermatcher.lmax;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Consume-only Aeron shadow validator. NATS remains the only follower injector and ACK authority;
 * this agent checks that Aeron delivered the same contiguous logical sequence and canonical input
 * payload without publishing to the ring or changing readiness.
 */
public final class AeronShadowFollower implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AeronShadowFollower.class);

    public static final int FAULT_NONE = 0;
    public static final int FAULT_WIRE = 1;
    public static final int FAULT_NOT_SHADOW = 2;
    public static final int FAULT_EPOCH = 3;
    public static final int FAULT_GAP = 4;
    public static final int FAULT_NATS_MISSING = 5;
    public static final int FAULT_PAYLOAD_MISMATCH = 6;

    private final Subscription subscription;
    private final ShadowSequenceMap authoritative;
    private final long expectedEpoch;
    private final long compareTimeoutNs;
    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final FragmentHandler fragmentHandler = this::onFragment;
    private final LongAdder compared = new LongAdder();
    private final LongAdder mismatches = new LongAdder();

    private volatile boolean running;
    private volatile int faultCode;
    private volatile long lastComparedSeq = -1L;
    private Thread pollThread;
    private Runnable faultCallback;

    public AeronShadowFollower(Aeron aeron, String dataChannel, int dataStreamId,
                               long expectedEpoch, ShadowSequenceMap authoritative,
                               long compareTimeoutMs) {
        this.subscription = aeron.addSubscription(dataChannel, dataStreamId);
        this.expectedEpoch = expectedEpoch;
        this.authoritative = authoritative;
        this.compareTimeoutNs = Math.max(1L, compareTimeoutMs) * 1_000_000L;
    }

    public void start(Runnable faultCallback) {
        if (running) return;
        this.faultCallback = faultCallback;
        running = true;
        pollThread = new Thread(this::pollLoop, "blp-aeron-shadow-follower");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void pollLoop() {
        while (running) {
            int fragments = subscription.poll(fragmentHandler, 256);
            if (fragments == 0) LockSupport.parkNanos(50_000L);
        }
    }

    private void onFragment(org.agrona.DirectBuffer buffer, int offset, int length,
                            io.aeron.logbuffer.Header header) {
        if (faultCode != FAULT_NONE) return;
        if (codec.tryInspectInput(buffer, offset, length) != AeronReplicationCodec.OK) {
            fault(FAULT_WIRE);
            return;
        }
        if ((codec.inputFlags() & AeronReplicationCodec.INPUT_FLAG_SHADOW) == 0) {
            fault(FAULT_NOT_SHADOW);
            return;
        }
        if (codec.leaderEpoch() != expectedEpoch) {
            fault(FAULT_EPOCH);
            return;
        }
        long seq = codec.inputSeq();
        if (lastComparedSeq >= 0 && seq != lastComparedSeq + 1L) {
            fault(FAULT_GAP);
            return;
        }

        long deadline = System.nanoTime() + compareTimeoutNs;
        while (running && !authoritative.contains(seq) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (!authoritative.contains(seq)) {
            fault(FAULT_NATS_MISSING);
            return;
        }
        long aeronChecksum = codec.inspectedPayloadChecksum();
        if (authoritative.checksum(seq) != aeronChecksum) {
            fault(FAULT_PAYLOAD_MISMATCH);
            return;
        }
        lastComparedSeq = seq;
        compared.increment();
    }

    private void fault(int code) {
        faultCode = code;
        mismatches.increment();
        running = false;
        log.error("Aeron shadow mismatch: code={} epoch={} lastComparedSeq={}",
            code, expectedEpoch, lastComparedSeq);
        if (faultCallback != null) faultCallback.run();
    }

    public boolean healthy() { return faultCode == FAULT_NONE; }
    public int faultCode() { return faultCode; }
    public long comparedCount() { return compared.sum(); }
    public long mismatchCount() { return mismatches.sum(); }
    public long lastComparedSeq() { return lastComparedSeq; }

    @Override
    public void close() {
        running = false;
        if (pollThread != null) pollThread.interrupt();
        subscription.close();
    }
}
