package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live-NATS Phase-0 harness for the real File-backed replication transport.
 *
 * <p>This is deliberately opt-in: normal unit/no-GC suites have no broker dependency. The
 * benchmark runner starts an isolated NATS server and sets {@code NATS_REPLICATION_PHASE0=true};
 * the allocation gate uses {@code NATS_REPLICATION_ALLOCATION_GATE=true}. Both exercise the real
 * {@link NatsJournalReplicator}, unlike {@link AllocationGateTest}, whose replication branch is a
 * {@link ReplicatorStub} by design.
 */
class NatsReplicationPhase0Test {
    private static final int RING_SIZE = 65_536;
    private static final int JOURNAL_BATCH_RECORDS = 1_024;
    private static final int PUBLISH_BATCH = 256;
    private static final int WARMUP_EVENTS = 65_536;
    private static final long FIXED_EVENT_TIME = 1_750_000_000_000L;

    @Test
    void ackModeIsDefaultOffAndRequiresAnExplicitDurableValue() {
        assertEquals(ReplicationFollower.AckMode.ONRING, ReplicationFollower.AckMode.parse(null));
        assertEquals(ReplicationFollower.AckMode.ONRING, ReplicationFollower.AckMode.parse(""));
        assertEquals(ReplicationFollower.AckMode.ONRING, ReplicationFollower.AckMode.parse("onring"));
        assertEquals(ReplicationFollower.AckMode.ONRING, ReplicationFollower.AckMode.parse("invalid"));
        assertEquals(ReplicationFollower.AckMode.DURABLE, ReplicationFollower.AckMode.parse("durable"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NATS_REPLICATION_PHASE0", matches = "true")
    void compareJournaledControlWithFileBackedReplication() throws Exception {
        String url = requiredEnv("NATS_REPLICATION_BENCH_URL");
        int runs = positiveEnvInt("NATS_REPLICATION_BENCH_RUNS", 3);
        int seconds = positiveEnvInt("NATS_REPLICATION_BENCH_SECONDS", 30);

        try (Connection admin = Nats.connect(url)) {
            for (int run = 1; run <= runs; run++) {
                Result result = runJournaledControl(seconds);
                printResult("single-control", run, result, -1L);
            }

            for (int run = 1; run <= runs; run++) {
                recreateFileStream(admin);
                Result result = runReplicated(url, seconds, run);
                long messages = admin.jetStreamManagement()
                    .getStreamInfo(NatsJournalReplicator.STREAM_NAME).getStreamState().getMsgCount();
                printResult("ha-file-" + configuredAckMode(), run, result, messages);
                assertTrue(messages >= WARMUP_EVENTS + result.events,
                    "JetStream did not retain every warm-up + measured event");
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NATS_REPLICATION_ALLOCATION_GATE", matches = "true")
    void recordsRealReplicatorAllocationBudget() throws Exception {
        String url = requiredEnv("NATS_REPLICATION_BENCH_URL");
        // jnats 2.20.5 allocates its acknowledged request/future graph per publish. The owner-layer
        // copy/list fix measures 1,589-1,591 B/event on this harness; 1,620 is a tight trend gate.
        long budgetPerEvent = nonNegativeEnvLong("NATS_REPLICATION_ALLOCATION_BUDGET_BYTES_PER_EVENT", 1_620L);
        int warmup = positiveEnvInt("NATS_REPLICATION_ALLOCATION_WARMUP_EVENTS", 65_536);
        int measured = positiveEnvInt("NATS_REPLICATION_ALLOCATION_MEASURED_EVENTS", 131_072);

        var baseMx = ManagementFactory.getThreadMXBean();
        assumeTrue(baseMx instanceof com.sun.management.ThreadMXBean,
            "com.sun.management.ThreadMXBean unavailable");
        var threadMx = (com.sun.management.ThreadMXBean) baseMx;
        assumeTrue(threadMx.isThreadAllocatedMemorySupported(), "thread allocation accounting unavailable");
        if (!threadMx.isThreadAllocatedMemoryEnabled()) {
            threadMx.setThreadAllocatedMemoryEnabled(true);
        }

        try (Connection conn = Nats.connect(url)) {
            recreateFileStream(conn);
            NatsJournalReplicator replicator = new NatsJournalReplicator(
                conn.jetStream(), NatsJournalReplicator.SUBJECT);
            InputEvent event = new InputEvent();
            driveReplicator(replicator, event, 0L, warmup);

            long threadId = Thread.currentThread().threadId();
            threadMx.getThreadAllocatedBytes(threadId);
            long before = threadMx.getThreadAllocatedBytes(threadId);
            driveReplicator(replicator, event, warmup, measured);
            long allocated = threadMx.getThreadAllocatedBytes(threadId) - before;
            long bytesPerEvent = allocated / measured;

            System.out.printf(Locale.ROOT,
                "REAL_REPLICATOR_ALLOCATION events=%d allocatedBytes=%d bytesPerEvent=%d budgetPerEvent=%d%n",
                measured, allocated, bytesPerEvent, budgetPerEvent);
            assertTrue(bytesPerEvent <= budgetPerEvent,
                () -> "real NATS replicator allocated " + bytesPerEvent
                    + " bytes/event; budget=" + budgetPerEvent);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NATS_REPLICATION_ACK_CORRECTNESS", matches = "true")
    void durableAckWaitsForTheFollowerGatingSequenceWhileOnringDoesNot() throws Exception {
        String url = requiredEnv("NATS_REPLICATION_BENCH_URL");
        assertAckTiming(url, ReplicationFollower.AckMode.ONRING, true);
        assertAckTiming(url, ReplicationFollower.AckMode.DURABLE, false);
    }

    private static Result runJournaledControl(int seconds) throws Exception {
        Path journalDir = Files.createTempDirectory("phase0-single-journal-");
        HotPathMetrics metrics = new HotPathMetrics();
        Journaler journaler = new Journaler(true, journalDir, metrics, JOURNAL_BATCH_RECORDS);
        CountingHandler counter = new CountingHandler();
        Disruptor<InputEvent> disruptor = newInputDisruptor("phase0-single");
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

    private static void assertAckTiming(String url, ReplicationFollower.AckMode ackMode,
                                        boolean expectedBeforeGating) throws Exception {
        try (Connection admin = Nats.connect(url);
             Connection primaryConn = Nats.connect(url);
             Connection followerConn = Nats.connect(url)) {
            recreateFileStream(admin);
            RingBuffer<InputEvent> ring = RingBuffer.createMultiProducer(
                InputEvent::newInstance, 1_024, new YieldingWaitStrategy());
            Sequence durableGate = new Sequence(-1L);
            ring.addGatingSequences(durableGate);

            ReplicationFollower follower = new ReplicationFollower(followerConn,
                "ack-proof-" + ackMode.name().toLowerCase(Locale.ROOT) + "-" + System.nanoTime(),
                -1L, new InMemoryOrderReadModel(), () -> {}, ackMode);
            follower.setInputRing(ring);
            NatsJournalReplicator replicator = new NatsJournalReplicator(
                primaryConn.jetStream(), NatsJournalReplicator.SUBJECT);
            replicator.startAckListener(primaryConn);
            try {
                follower.start();
                InputEvent event = new InputEvent();
                fill(event, 0L);
                replicator.onEvent(event, 0L, true);
                if (expectedBeforeGating) {
                    awaitFollowerAck(replicator, 0L);
                } else {
                    Thread.sleep(250L);
                    assertEquals(-1L, replicator.followerAckedSeq(),
                        "durable ACK advanced before the follower's gated journal/apply path");
                }

                durableGate.set(0L);
                awaitFollowerAck(replicator, 0L);
            } finally {
                follower.stop();
                replicator.stopAckListener();
            }
        }
    }

    private static Result runReplicated(String url, int seconds, int run) throws Exception {
        Path primaryJournalDir = Files.createTempDirectory("phase0-primary-journal-");
        Path followerJournalDir = Files.createTempDirectory("phase0-follower-journal-");
        Connection primaryConn = Nats.connect(url);
        Connection followerConn = Nats.connect(url);
        Journaler primaryJournaler = new Journaler(true, primaryJournalDir,
            new HotPathMetrics(), JOURNAL_BATCH_RECORDS);
        Journaler followerJournaler = new Journaler(true, followerJournalDir,
            new HotPathMetrics(), JOURNAL_BATCH_RECORDS);
        CountingHandler primaryCounter = new CountingHandler();
        CountingHandler followerCounter = new CountingHandler();
        Disruptor<InputEvent> primaryDisruptor = newInputDisruptor("phase0-primary");
        Disruptor<InputEvent> followerDisruptor = newInputDisruptor("phase0-follower");
        NatsJournalReplicator replicator = new NatsJournalReplicator(
            primaryConn.jetStream(), NatsJournalReplicator.SUBJECT);
        replicator.startAckListener(primaryConn);
        primaryDisruptor.handleEventsWith(primaryJournaler, replicator).then(primaryCounter);
        followerDisruptor.handleEventsWith(followerJournaler, new ReplicatorStub()).then(followerCounter);
        RingBuffer<InputEvent> primaryRing = primaryDisruptor.start();
        RingBuffer<InputEvent> followerRing = followerDisruptor.start();
        AtomicBoolean followerReady = new AtomicBoolean();
        ReplicationFollower follower = new ReplicationFollower(followerConn,
            "phase0-follower-" + run + "-" + System.nanoTime(), -1L,
            new InMemoryOrderReadModel(), () -> followerReady.set(true));
        follower.setInputRing(followerRing);

        try {
            follower.start();
            assertTrue(followerReady.get(), "follower did not reach the empty stream tail");
            publishCount(primaryRing, WARMUP_EVENTS);
            awaitSequence(primaryCounter, primaryRing.getCursor());
            awaitCount(followerCounter, WARMUP_EVENTS);
            Result result = publishFor(primaryRing, primaryCounter, followerCounter, seconds);
            awaitFollowerAck(replicator, primaryRing.getCursor());
            return result;
        } finally {
            follower.stop();
            replicator.stopAckListener();
            shutdown(primaryDisruptor);
            shutdown(followerDisruptor);
            primaryJournaler.close();
            followerJournaler.close();
            primaryConn.close();
            followerConn.close();
            deleteTree(primaryJournalDir);
            deleteTree(followerJournalDir);
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

    private static void driveReplicator(NatsJournalReplicator replicator, InputEvent event,
                                        long startSequence, int count) {
        for (int i = 0; i < count; i++) {
            long sequence = startSequence + i;
            fill(event, sequence);
            boolean endOfBatch = (i & (PUBLISH_BATCH - 1)) == PUBLISH_BATCH - 1
                || i == count - 1;
            replicator.onEvent(event, sequence, endOfBatch);
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

    private static void recreateFileStream(Connection conn) throws Exception {
        var jsm = conn.jetStreamManagement();
        try {
            jsm.deleteStream(NatsJournalReplicator.STREAM_NAME);
        } catch (io.nats.client.JetStreamApiException ex) {
            if (ex.getApiErrorCode() != 10059) {
                throw ex;
            }
        }
        assertTrue(NatsJournalReplicator.ensureStream(conn), "replication stream was not created");
        assertEquals(StorageType.File,
            jsm.getStreamInfo(NatsJournalReplicator.STREAM_NAME).getConfiguration().getStorageType(),
            "Phase 0 must measure the File-backed stream");
    }

    private static void printResult(String mode, int run, Result result, long streamMessages) {
        System.out.printf(Locale.ROOT,
            "PHASE0_RESULT mode=%s run=%d events=%d elapsedSeconds=%.3f eventsPerSecond=%.0f streamMessages=%d%n",
            mode, run, result.events, result.elapsedNanos / 1_000_000_000.0,
            result.eventsPerSecond(), streamMessages);
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

    private static void awaitFollowerAck(NatsJournalReplicator replicator, long target) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (replicator.followerAckedSeq() < target) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("timed out waiting for real follower ACK " + target
                    + ", current=" + replicator.followerAckedSeq());
            }
            Thread.onSpinWait();
        }
    }

    private static String configuredAckMode() {
        String value = System.getenv("BLP_REPLICATION_ACK_MODE");
        return value == null || value.isBlank() ? "onring" : value.toLowerCase(Locale.ROOT);
    }

    private static void shutdown(Disruptor<InputEvent> disruptor) {
        try {
            disruptor.shutdown(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            disruptor.halt();
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static int positiveEnvInt(String name, int fallback) {
        String value = System.getenv(name);
        int parsed = value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        if (parsed < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private static long nonNegativeEnvLong(String name, long fallback) {
        String value = System.getenv(name);
        long parsed = value == null || value.isBlank() ? fallback : Long.parseLong(value);
        if (parsed < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
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
