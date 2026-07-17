package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-0 harness for the real Aeron replication transport, matched line-for-line to
 * {@code NatsReplicationPhase0Test}: same ring size, journal batch, publish batch, warm-up,
 * event fill, publish loop, and timed window, so events/second is directly comparable with the
 * File-backed NATS Phase-0 numbers taken on the same host.
 *
 * <p>Deliberately opt-in ({@code AERON_REPLICATION_PHASE0=true}); normal suites never run it.
 *
 * <p>Three tiers, each with fresh journals and a fresh embedded Media Driver per run:
 * <ul>
 *   <li>{@code single-control} — journaler + ReplicatorStub, identical to the NATS control, so
 *       the same-day journaling ceiling is measured on the same host state.</li>
 *   <li>{@code aeron-ipc} — one IPC publication to the real follower (transport upper bound,
 *       no fan-out).</li>
 *   <li>{@code aeron-mdc-udp} — the YU11 production topology: one manual-MDC UDP publication
 *       fanned out to a drained archive destination and the real follower over UDP loopback,
 *       ACK returning over UDP loopback.</li>
 * </ul>
 *
 * <p>Fairness notes. Both replicated tiers include the real primary journal fsync path, which is
 * the durability authority in this design (the NATS comparator additionally persisted to the
 * broker's File store — redundant durability the Aeron design assigns to the journal). The MDC
 * archive destination is drained by a live subscriber without disk recording: in production the
 * Archive write happens in the sidecar process, off the matcher's critical path, coupled only
 * through flow control — which the drain models.
 */
class AeronReplicationPhase0Test {
    private static final int RING_SIZE = 65_536;
    private static final int JOURNAL_BATCH_RECORDS = 1_024;
    private static final int PUBLISH_BATCH = 256;
    private static final int WARMUP_EVENTS = 65_536;
    private static final long FIXED_EVENT_TIME = 1_750_000_000_000L;
    private static final int DATA_STREAM_ID = 1101;
    private static final int ACK_STREAM_ID = 1102;
    private static final long EPOCH = 11L;
    private static final int MAPPING_CAPACITY = 131_072;
    private static final long ACK_TIMEOUT_MS = 5_000L;

    @Test
    @EnabledIfEnvironmentVariable(named = "AERON_REPLICATION_PHASE0", matches = "true")
    void compareJournaledControlWithAeronReplication() throws Exception {
        int runs = positiveEnvInt("AERON_REPLICATION_BENCH_RUNS", 3);
        int seconds = positiveEnvInt("AERON_REPLICATION_BENCH_SECONDS", 30);

        for (int run = 1; run <= runs; run++) {
            printResult("single-control", run, runJournaledControl(seconds));
        }
        for (int run = 1; run <= runs; run++) {
            printResult("aeron-ipc", run, runReplicated(seconds, run, false));
        }
        for (int run = 1; run <= runs; run++) {
            printResult("aeron-mdc-udp", run, runReplicated(seconds, run, true));
        }
    }

    private static Result runJournaledControl(int seconds) throws Exception {
        Path journalDir = Files.createTempDirectory("aeron-phase0-single-journal-");
        Journaler journaler = new Journaler(true, journalDir, new HotPathMetrics(), JOURNAL_BATCH_RECORDS);
        CountingHandler counter = new CountingHandler();
        Disruptor<InputEvent> disruptor = newInputDisruptor("aeron-phase0-single");
        disruptor.handleEventsWith(journaler, new ReplicatorStub()).then(counter);
        RingBuffer<InputEvent> ring = disruptor.start();
        try {
            publishCount(ring, WARMUP_EVENTS);
            awaitSequence(counter, ring.getCursor());
            return publishFor(ring, counter, null, seconds);
        } finally {
            shutdown(disruptor);
            journaler.close();
            deleteTree(journalDir);
        }
    }

    private static Result runReplicated(int seconds, int run, boolean mdc) throws Exception {
        Path aeronDir = Files.createTempDirectory("aeron-phase0-driver-");
        Path primaryJournalDir = Files.createTempDirectory("aeron-phase0-primary-journal-");
        Path followerJournalDir = Files.createTempDirectory("aeron-phase0-follower-journal-");

        int basePort = 24_000 + run * 10;
        String dataChannel = mdc ? "aeron:udp?control-mode=manual" : CommonContext.IPC_CHANNEL;
        String archiveDestination = mdc ? "aeron:udp?endpoint=127.0.0.1:" + basePort : "";
        String followerDestination = mdc ? "aeron:udp?endpoint=127.0.0.1:" + (basePort + 1) : "";
        String followerDataChannel = mdc ? followerDestination : CommonContext.IPC_CHANNEL;
        String ackChannel = mdc ? "aeron:udp?endpoint=127.0.0.1:" + (basePort + 2) : CommonContext.IPC_CHANNEL;

        MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDir.resolve("aeron").toString())
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .threadingMode(ThreadingMode.SHARED);

        Journaler primaryJournaler = new Journaler(true, primaryJournalDir,
            new HotPathMetrics(), JOURNAL_BATCH_RECORDS);
        Journaler followerJournaler = new Journaler(true, followerJournalDir,
            new HotPathMetrics(), JOURNAL_BATCH_RECORDS);
        CountingHandler primaryCounter = new CountingHandler();
        CountingHandler followerCounter = new CountingHandler();
        Disruptor<InputEvent> primaryDisruptor = newInputDisruptor("aeron-phase0-primary");
        Disruptor<InputEvent> followerDisruptor = newInputDisruptor("aeron-phase0-follower");

        try (MediaDriver driver = MediaDriver.launch(driverContext);
             Aeron aeron = Aeron.connect(new Aeron.Context()
                 .aeronDirectoryName(driver.aeronDirectoryName()));
             Subscription archiveDrain = mdc
                 ? aeron.addSubscription(archiveDestination, DATA_STREAM_ID) : null;
             AeronReplicator replicator = new AeronReplicator(aeron,
                 dataChannel, DATA_STREAM_ID, ackChannel, ACK_STREAM_ID,
                 EPOCH, ReplicationAckMode.ON_RING, ReplicationFailurePolicy.STRICT,
                 ACK_TIMEOUT_MS, false, () -> true, archiveDestination, followerDestination);
             AeronReplicationFollower follower = new AeronReplicationFollower(aeron,
                 followerDataChannel, DATA_STREAM_ID, ackChannel, ACK_STREAM_ID,
                 EPOCH, ReplicationAckMode.ON_RING, MAPPING_CAPACITY, ACK_TIMEOUT_MS);
             ArchiveDrainAgent drainAgent = mdc ? new ArchiveDrainAgent(archiveDrain) : null) {

            primaryDisruptor.handleEventsWith(primaryJournaler, replicator).then(primaryCounter);
            followerDisruptor.handleEventsWith(followerJournaler, new ReplicatorStub()).then(followerCounter);
            RingBuffer<InputEvent> primaryRing = primaryDisruptor.start();
            RingBuffer<InputEvent> followerRing = followerDisruptor.start();
            follower.setInputRing(followerRing);
            CountDownLatch ready = new CountDownLatch(1);
            follower.start(ready::countDown, () -> { });
            if (drainAgent != null) {
                drainAgent.start();
            }
            assertTrue(replicator.awaitConnected(10_000L), "Aeron publication did not connect");
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Aeron follower did not become ready");

            try {
                publishCount(primaryRing, WARMUP_EVENTS);
                awaitSequence(primaryCounter, primaryRing.getCursor());
                awaitCount(followerCounter, WARMUP_EVENTS);
                Result result = publishFor(primaryRing, primaryCounter, followerCounter, seconds);
                awaitFollowerAck(replicator, primaryRing.getCursor());
                assertEquals(AeronReplicationFollower.FAULT_NONE, follower.faultCode(),
                    "follower faulted during the measured window");
                return result;
            } finally {
                shutdown(primaryDisruptor);
                shutdown(followerDisruptor);
            }
        } finally {
            primaryJournaler.close();
            followerJournaler.close();
            deleteTree(primaryJournalDir);
            deleteTree(followerJournalDir);
            deleteTree(aeronDir);
        }
    }

    private static Result publishFor(RingBuffer<InputEvent> ring, CountingHandler primary,
                                     CountingHandler follower, int seconds) {
        long followerBefore = follower == null ? 0L : follower.count;
        long start = System.nanoTime();
        long deadline = start + TimeUnit.SECONDS.toNanos(seconds);
        long events = 0L;
        while (System.nanoTime() < deadline) {
            publishCount(ring, PUBLISH_BATCH);
            events += PUBLISH_BATCH;
        }
        long target = ring.getCursor();
        awaitSequence(primary, target);
        if (follower != null) {
            awaitCount(follower, followerBefore + events);
        }
        return new Result(events, System.nanoTime() - start);
    }

    private static void publishCount(RingBuffer<InputEvent> ring, int count) {
        int remaining = count;
        while (remaining > 0) {
            int batch = Math.min(PUBLISH_BATCH, remaining);
            long hi = ring.next(batch);
            long lo = hi - batch + 1L;
            for (long seq = lo; seq <= hi; seq++) {
                InputEvent event = ring.get(seq);
                fill(event, seq);
            }
            ring.publish(lo, hi);
            remaining -= batch;
        }
    }

    private static void fill(InputEvent event, long sequence) {
        event.seq = sequence;
        event.type = InputEvent.TYPE_PRICE_TICK;
        event.side = InputEvent.SIDE_BUY;
        event.orderRef = (int) sequence;
        event.accountId = 22_214;
        event.securityId = (int) sequence & 3;
        event.qty = 1;
        event.limitPx = 100_000_000L;
        event.priceTicks = 99_500_000L;
        event.ingressNanos = 0L;
        event.eventTimeMillis = FIXED_EVENT_TIME;
    }

    private static Disruptor<InputEvent> newInputDisruptor(String threadName) {
        return new Disruptor<>(InputEvent::newInstance, RING_SIZE,
            runnable -> {
                Thread thread = new Thread(runnable, threadName);
                thread.setDaemon(true);
                return thread;
            }, ProducerType.MULTI, new YieldingWaitStrategy());
    }

    private static void printResult(String mode, int run, Result result) {
        System.out.printf(Locale.ROOT,
            "PHASE0_RESULT mode=%s run=%d events=%d elapsedSeconds=%.3f eventsPerSecond=%.0f%n",
            mode, run, result.events, result.elapsedNanos / 1_000_000_000.0,
            result.eventsPerSecond());
    }

    private static void awaitSequence(CountingHandler handler, long target) {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        while (handler.lastSequence < target) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("timed out waiting for local sequence " + target
                    + ", current=" + handler.lastSequence);
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitCount(CountingHandler handler, long target) {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        while (handler.count < target) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("timed out waiting for follower count " + target
                    + ", current=" + handler.count);
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitFollowerAck(AeronReplicator replicator, long target) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (replicator.followerAckedSeq() < target) {
            replicator.pollAcksOnce();
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("timed out waiting for real follower ACK " + target
                    + ", current=" + replicator.followerAckedSeq());
            }
            Thread.onSpinWait();
        }
    }

    private static void shutdown(Disruptor<InputEvent> disruptor) {
        try {
            disruptor.shutdown(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            disruptor.halt();
        }
    }

    private static int positiveEnvInt(String name, int fallback) {
        String value = System.getenv(name);
        int parsed = value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        if (parsed < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Best-effort benchmark cleanup.
                }
            });
        } catch (Exception ignored) {
            // Best-effort benchmark cleanup.
        }
    }

    /** Live drain of the MDC archive destination — models the sidecar's connected receiver. */
    private static final class ArchiveDrainAgent implements AutoCloseable, Runnable {
        private final Subscription subscription;
        private final Thread thread;
        private volatile boolean running = true;

        private ArchiveDrainAgent(Subscription subscription) {
            this.subscription = subscription;
            this.thread = new Thread(this, "aeron-phase0-archive-drain");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        @Override
        public void run() {
            while (running) {
                if (subscription.poll((buffer, offset, length, header) -> { }, 256) == 0) {
                    Thread.onSpinWait();
                }
            }
        }

        @Override
        public void close() throws InterruptedException {
            running = false;
            thread.join(5_000L);
        }
    }

    private static final class CountingHandler implements EventHandler<InputEvent> {
        private volatile long lastSequence = -1L;
        private volatile long count;

        @Override
        public void onEvent(InputEvent event, long sequence, boolean endOfBatch) {
            count++;
            lastSequence = sequence;
        }
    }

    private record Result(long events, long elapsedNanos) {
        double eventsPerSecond() {
            return events * 1_000_000_000.0 / elapsedNanos;
        }
    }
}
