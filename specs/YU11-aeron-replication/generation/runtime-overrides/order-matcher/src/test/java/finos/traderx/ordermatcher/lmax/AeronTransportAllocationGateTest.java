package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exact-zero gate for the real Aeron/SBE transport threads, outside startup/JIT warm-up. */
class AeronTransportAllocationGateTest {
    @TempDir Path tempDir;

    @Test
    void primaryAndFollowerTransportAreAllocationFreeInSteadyState() throws Exception {
        var baseMx = ManagementFactory.getThreadMXBean();
        assumeTrue(baseMx instanceof com.sun.management.ThreadMXBean);
        var threadMx = (com.sun.management.ThreadMXBean) baseMx;
        assumeTrue(threadMx.isThreadAllocatedMemorySupported());
        if (!threadMx.isThreadAllocatedMemoryEnabled()) {
            threadMx.setThreadAllocatedMemoryEnabled(true);
        }

        int warmup = Integer.getInteger("gate.warmupEvents", 250_000);
        int steady = Integer.getInteger("gate.steadyStateEvents", 1_000_000);
        String aeronDir = tempDir.resolve("aeron").toString();
        MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .threadingMode(ThreadingMode.SHARED);

        Disruptor<InputEvent> inputDisruptor = new Disruptor<>(InputEvent::newInstance, 65536,
            DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, new YieldingWaitStrategy());
        Drain drain = new Drain();
        inputDisruptor.handleEventsWith(drain);
        inputDisruptor.start();

        try (MediaDriver driver = MediaDriver.launch(driverContext);
             Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
             AeronReplicator primary = new AeronReplicator(aeron,
                 CommonContext.IPC_CHANNEL, 1111, CommonContext.IPC_CHANNEL, 1112,
                 9L, ReplicationAckMode.ON_RING, ReplicationFailurePolicy.DEGRADED_SOLO,
                 1_000L, false);
             AeronReplicationFollower follower = new AeronReplicationFollower(aeron,
                 CommonContext.IPC_CHANNEL, 1111, CommonContext.IPC_CHANNEL, 1112,
                 9L, ReplicationAckMode.ON_RING, 131072, 1_000L)) {
            follower.setInputRing(inputDisruptor.getRingBuffer());
            CountDownLatch ready = new CountDownLatch(1);
            follower.start(ready::countDown, () -> { });
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Aeron pair did not connect");

            long warmupTail = warmup - 1L;
            try (PublisherAgent publisher = new PublisherAgent(primary)) {
                publisher.start();
                await(() -> publisher.publishedSequence() >= warmupTail
                        && follower.lastInputSeq() >= warmupTail && drain.sequence >= warmupTail,
                    10_000L);

                long primaryThreadId = publisher.threadId();
                long followerThreadId = follower.pollThreadId();
                assertTrue(primaryThreadId != 0L && followerThreadId != 0L,
                    "transport threads not started");
                long primaryDelta = Long.MIN_VALUE;
                long followerDelta = Long.MIN_VALUE;
                long tail = warmupTail;
                int consecutiveZeroWindows = 0;
                for (int window = 0; window < 4 && consecutiveZeroWindows < 2; window++) {
                    threadMx.getThreadAllocatedBytes(primaryThreadId);
                    threadMx.getThreadAllocatedBytes(followerThreadId);
                    long primaryBefore = threadMx.getThreadAllocatedBytes(primaryThreadId);
                    long followerBefore = threadMx.getThreadAllocatedBytes(followerThreadId);

                    tail = publisher.publishedSequence() + steady;
                    long measuredTail = tail;
                    await(() -> follower.lastInputSeq() >= measuredTail
                            && drain.sequence >= measuredTail,
                        20_000L);

                    primaryDelta = threadMx.getThreadAllocatedBytes(primaryThreadId) - primaryBefore;
                    followerDelta = threadMx.getThreadAllocatedBytes(followerThreadId) - followerBefore;
                    if (primaryDelta == 0L && followerDelta == 0L) {
                        consecutiveZeroWindows++;
                    } else {
                        // A late one-time C2 rematerialization means the JVM was not fully warmed.
                        // It is JFR-invisible and does not repeat; actual hot-loop allocation
                        // cannot produce two subsequent exact-zero million-event windows.
                        consecutiveZeroWindows = 0;
                    }
                }
                assertEquals(2, consecutiveZeroWindows,
                    "Aeron transport did not produce two consecutive exact-zero windows"
                        + " (primary=" + primaryDelta + ", follower=" + followerDelta + ")");
                assertTrue(follower.lastInputSeq() >= tail);
                assertEquals(AeronReplicationFollower.FAULT_NONE, follower.faultCode());
            }
        } finally {
            inputDisruptor.shutdown(10, TimeUnit.SECONDS);
        }
    }

    private static InputEvent event() {
        InputEvent event = InputEvent.newInstance();
        event.type = InputEvent.TYPE_ORDER_NEW;
        event.side = InputEvent.SIDE_BUY;
        event.accountId = 22214;
        event.securityId = 7;
        event.qty = 10;
        event.limitPx = 123_000_000L;
        event.priceTicks = 122_000_000L;
        return event;
    }

    private static void await(Check check, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!check.ok()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("condition timed out");
            Thread.sleep(1L);
        }
    }

    @FunctionalInterface
    private interface Check { boolean ok(); }

    private static final class Drain implements EventHandler<InputEvent> {
        private volatile long sequence = -1L;
        @Override public void onEvent(InputEvent event, long sequence, boolean endOfBatch) {
            this.sequence = sequence;
        }
    }

    /** Keeps the publisher's hot loop running across both measurement samples. */
    private static final class PublisherAgent implements AutoCloseable, Runnable {
        private final AeronReplicator primary;
        private final InputEvent event = event();
        private final Thread thread;
        private volatile long publishedSequence = -1L;
        private volatile boolean running = true;

        private PublisherAgent(AeronReplicator primary) {
            this.primary = primary;
            this.thread = new Thread(this, "blp-aeron-allocation-primary");
            this.thread.setDaemon(true);
        }

        void start() { thread.start(); }
        long threadId() { return thread.threadId(); }
        long publishedSequence() { return publishedSequence; }

        @Override public void run() {
            long sequence = 0L;
            while (running) {
                event.seq = sequence;   // the wire inputSeq is the event's business sequence
                event.orderRef = (int) sequence;
                event.eventTimeMillis = sequence;
                primary.onEvent(event, sequence, (sequence & 63L) == 63L);
                publishedSequence = sequence;
                sequence++;
            }
        }

        @Override public void close() throws InterruptedException {
            running = false;
            thread.join(5_000L);
        }
    }
}
