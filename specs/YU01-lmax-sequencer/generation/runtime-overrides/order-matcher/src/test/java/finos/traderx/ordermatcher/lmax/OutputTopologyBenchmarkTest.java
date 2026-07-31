package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import finos.traderx.messaging.Publisher;
import finos.traderx.ordermatcher.api.OrderResponse;
import org.HdrHistogram.ConcurrentHistogram;
import org.junit.jupiter.api.Test;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test-only comparison of the two output topologies under controlled projector delays.
 * The producer represents the BLP's output publication boundary; the handlers use the
 * production marshaller, optimized NATS bridge, and no-flush projector code paths.
 */
class OutputTopologyBenchmarkTest {
    private static final int WARMUP_EVENTS = 5_000;
    private static final long HIGHEST_TRACKABLE_NS = TimeUnit.SECONDS.toNanos(30);
    private static final long DRAIN_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(30);

    @Test
    void compareDirectConsumersWithSynchronizedWorker() throws Exception {
        assumeTrue(Boolean.getBoolean("outputTopologyBenchmark"),
            "run with ./gradlew outputTopologyBenchmark");

        int fastEvents = Integer.getInteger("outputTopologyBenchmark.fastEvents", 50_000);
        int delayedEvents = Integer.getInteger("outputTopologyBenchmark.delayedEvents", 5_000);
        int stalledEvents = Integer.getInteger("outputTopologyBenchmark.stalledEvents", 5_000);
        int ringSize = normalizeRingSize(Integer.getInteger("outputTopologyBenchmark.ringSize", 1_024));

        List<Scenario> scenarios = List.of(
            new Scenario("fast", fastEvents, 0L, 0L),
            new Scenario("projector-100us", delayedEvents, TimeUnit.MICROSECONDS.toNanos(100), 0L),
            new Scenario("projector-1ms", delayedEvents, TimeUnit.MILLISECONDS.toNanos(1), 0L),
            new Scenario("projector-stall-250ms", stalledEvents, 0L, TimeUnit.MILLISECONDS.toNanos(250))
        );

        System.out.printf("%nOutput topology comparison: ring=%d edgeCapacity=%d waitStrategies=blocking,yielding " +
            "warmup=%d%n", ringSize, ringSize, WARMUP_EVENTS);
        System.out.println("Latency units are ns; throughput is events/s; CPU is process CPU during the scenario.");
        printHeader();

        for (WaitMode waitMode : WaitMode.values()) {
            for (Scenario scenario : scenarios) {
                Result direct = runScenario(Topology.DIRECT, waitMode, scenario, ringSize);
                Result worker = runScenario(Topology.SYNCHRONIZED_WORKER, waitMode, scenario, ringSize);
                printResult(direct);
                printResult(worker);
                printComparison(direct, worker);
            }
        }
    }

    private Result runScenario(Topology topology, WaitMode waitMode, Scenario scenario, int ringSize)
        throws Exception {
        CapturingThreadFactory threadFactory = new CapturingThreadFactory(
            "topology-" + topology.label + "-" + waitMode.label);
        Disruptor<OutputEvent> disruptor = new Disruptor<>(OutputEvent::newInstance, ringSize,
            threadFactory, ProducerType.SINGLE, waitMode.create());

        SymbolTable symbols = new SymbolTable(16);
        int securityId = symbols.idFor("IBM");
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
        AckProbe ack = new AckProbe(new MarshallerHandler(readModel, symbols, new HotPathMetrics()));
        NatsProbe nats = new NatsProbe(new NatsBridgeHandler(new NoopPublisher<>(), symbols, readModel));
        ProjectorProbe projector = new ProjectorProbe(
            new ProjectorHandler(null, null, null, symbols, Integer.MAX_VALUE, new HotPathMetrics()));
        TestSynchronizedWorker externalEdge = null;

        if (topology == Topology.DIRECT) {
            disruptor.handleEventsWith(ack, nats, projector);
        } else {
            externalEdge = new TestSynchronizedWorker(ringSize, nats, projector);
            disruptor.handleEventsWith(ack, externalEdge);
        }

        RingBuffer<OutputEvent> ring = disruptor.start();
        try {
            publish(ring, WARMUP_EVENTS, securityId, null, null, null);
            awaitProcessed(ack, nats, projector, ring.getCursor());

            ack.reset();
            nats.reset();
            projector.configure(scenario.projectorDelayNanos, scenario.stallOnceNanos);

            long[] consumerThreadIds = threadFactory.threadIds();
            if (externalEdge != null) {
                consumerThreadIds = append(consumerThreadIds, findExternalWorkerThreadId());
            }
            AllocationSnapshot allocationBefore = AllocationSnapshot.capture(consumerThreadIds);
            GcSnapshot gcBefore = GcSnapshot.capture();
            long processCpuBefore = processCpuTime();
            long wallBefore = System.nanoTime();

            PublishStats publishStats = new PublishStats(ringSize,
                externalEdge == null ? -1 : externalEdge.remainingCapacity());
            publish(ring, scenario.events, securityId, publishStats, projector, externalEdge);
            long publishDone = System.nanoTime();
            long lastSequence = ring.getCursor();
            awaitProcessed(ack, nats, projector, lastSequence);
            long drainDone = System.nanoTime();

            long processCpuAfter = processCpuTime();
            GcSnapshot gcAfter = GcSnapshot.capture();
            AllocationSnapshot allocationAfter = AllocationSnapshot.capture(consumerThreadIds);

            return new Result(
                topology,
                waitMode,
                scenario,
                ack.histogram,
                nats.histogram,
                rate(scenario.events, publishDone - wallBefore),
                rate(scenario.events, drainDone - wallBefore),
                publishStats.backpressure,
                publishStats.minimumRingCapacity,
                publishStats.minimumEdgeCapacity,
                publishStats.maximumProjectorLag,
                projector.eventsPublishedBeforeStallReleased(),
                Math.max(0L, processCpuAfter - processCpuBefore),
                Math.max(0L, allocationAfter.totalBytes - allocationBefore.totalBytes),
                Math.max(0L, allocationAfter.producerBytes - allocationBefore.producerBytes),
                Math.max(0L, gcAfter.collections - gcBefore.collections),
                Math.max(0L, gcAfter.collectionTimeMillis - gcBefore.collectionTimeMillis),
                drainDone - wallBefore
            );
        } finally {
            try {
                disruptor.shutdown(5, TimeUnit.SECONDS);
            } catch (com.lmax.disruptor.TimeoutException ex) {
                disruptor.halt();
            }
            if (externalEdge != null) {
                externalEdge.close();
            }
        }
    }

    private static void publish(RingBuffer<OutputEvent> ring, int events, int securityId,
                                PublishStats stats, ProjectorProbe projector,
                                TestSynchronizedWorker externalEdge) {
        for (int i = 0; i < events; i++) {
            long ingressNanos = System.nanoTime();
            long sequence;
            try {
                sequence = ring.tryNext();
            } catch (InsufficientCapacityException ex) {
                if (stats != null) {
                    stats.backpressure++;
                }
                sequence = ring.next();
            }
            try {
                writeOrderEvent(ring.get(sequence), sequence, securityId, ingressNanos);
            } finally {
                ring.publish(sequence);
            }
            if (stats != null) {
                stats.minimumRingCapacity = Math.min(stats.minimumRingCapacity, ring.remainingCapacity());
                if (externalEdge != null) {
                    stats.minimumEdgeCapacity = Math.min(stats.minimumEdgeCapacity,
                        externalEdge.remainingCapacity());
                }
                long lag = sequence - projector.projectedSequence();
                stats.maximumProjectorLag = Math.max(stats.maximumProjectorLag, lag);
                projector.recordPublished(i + 1L);
            }
        }
    }

    private static void writeOrderEvent(OutputEvent event, long sequence, int securityId, long ingressNanos) {
        event.inputSeq = sequence;
        event.kind = OutputEvent.KIND_ORDER_ACCEPTED;
        event.flags = OutputEvent.FLAG_CREATE;
        event.publishNats = true;
        event.orderRef = 42;
        event.accountId = 22_214;
        event.securityId = securityId;
        event.side = InputEvent.SIDE_BUY;
        event.quantity = 100;
        event.remainingQty = 100;
        event.limitPx = 101_125_000L;
        event.status = RestingOrder.STATUS_NEW;
        event.lastExecPx = Px.NONE;
        event.lastFillQty = 0;
        event.createdAtMillis = 1_700_000_000_000L;
        event.updatedAtMillis = 1_700_000_000_000L;
        event.marketPx = Px.NONE;
        event.tradeQty = 0;
        event.tradeSeq = 0;
        event.tradePx = Px.NONE;
        event.positionQty = 0;
        event.positionAvgCostTicks = 0L;
        event.averageCostBasisPx = Px.NONE;
        event.ingressNanos = ingressNanos;
    }

    private static void awaitProcessed(AckProbe ack, NatsProbe nats, ProjectorProbe projector,
                                       long expectedSequence) {
        long deadline = System.nanoTime() + DRAIN_TIMEOUT_NS;
        while (ack.processedSequence() < expectedSequence || nats.processedSequence() < expectedSequence
            || projector.projectedSequence() < expectedSequence) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("output topology did not drain through sequence " + expectedSequence +
                    " (ack=" + ack.processedSequence() + ", nats=" + nats.processedSequence() +
                    ", projector=" + projector.projectedSequence() + ")");
            }
            Thread.onSpinWait();
        }
    }

    private static void printHeader() {
        System.out.println("wait      scenario                 topology  ack-p50 ack-p99 ack-p99.9 " +
            "nats-p50 nats-p99 nats-p99.9 publish/s drain/s bp minRing minEdge maxLag " +
            "preRelease cpuMs allocBytes producerAlloc gcCount gcMs wallMs");
    }

    private static void printResult(Result result) {
        System.out.printf("%-9s %-24s %-9s %7d %7d %9d %8d %8d %10d %9.0f %7.0f %2d %7d %7d %6d " +
                "%10d %5.1f %10d %13d %7d %4d %6.1f%n",
            result.waitMode.label, result.scenario.name, result.topology.label,
            percentile(result.ackLatency, 50.0), percentile(result.ackLatency, 99.0),
            percentile(result.ackLatency, 99.9), percentile(result.natsLatency, 50.0),
            percentile(result.natsLatency, 99.0), percentile(result.natsLatency, 99.9),
            result.publishThroughput, result.drainThroughput, result.backpressure,
            result.minimumRingCapacity, result.minimumEdgeCapacity, result.maximumProjectorLag,
            result.eventsPublishedBeforeStallReleased, nanosToMillis(result.processCpuNanos),
            result.allocatedBytes, result.producerAllocatedBytes, result.gcCollections,
            result.gcCollectionMillis, nanosToMillis(result.wallNanos));
    }

    private static void printComparison(Result direct, Result worker) {
        double ackP99Delta = percentChange(percentile(worker.ackLatency, 99.0),
            percentile(direct.ackLatency, 99.0));
        double natsP99Delta = percentChange(percentile(worker.natsLatency, 99.0),
            percentile(direct.natsLatency, 99.0));
        double throughputDelta = percentChange(worker.publishThroughput, direct.publishThroughput);
        System.out.printf("  %s direct relative to worker: ack-p99=%+.1f%% nats-p99=%+.1f%% " +
                "publish-throughput=%+.1f%% " +
                "backpressure=%d/%d maxLag=%d/%d%n",
            direct.waitMode.label, ackP99Delta, natsP99Delta, throughputDelta,
            direct.backpressure, worker.backpressure,
            direct.maximumProjectorLag, worker.maximumProjectorLag);
    }

    private static long percentile(ConcurrentHistogram histogram, double percentile) {
        return histogram.getTotalCount() == 0 ? 0 : histogram.getValueAtPercentile(percentile);
    }

    private static double rate(long events, long nanos) {
        return nanos <= 0 ? 0.0 : events * 1_000_000_000.0 / nanos;
    }

    private static double percentChange(double baseline, double candidate) {
        return baseline == 0.0 ? 0.0 : (candidate - baseline) * 100.0 / baseline;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static int normalizeRingSize(int requested) {
        int size = Math.max(64, Integer.highestOneBit(Math.max(1, requested)));
        return size == requested ? requested : size << 1;
    }

    private static long processCpuTime() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean sunBean
            ? Math.max(0L, sunBean.getProcessCpuTime()) : 0L;
    }

    private static long findExternalWorkerThreadId() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().equals("benchmark-synchronized-worker")) {
                return thread.threadId();
            }
        }
        return -1L;
    }

    private static long[] append(long[] values, long value) {
        if (value < 0) {
            return values;
        }
        long[] result = new long[values.length + 1];
        System.arraycopy(values, 0, result, 0, values.length);
        result[values.length] = value;
        return result;
    }

    private enum Topology {
        DIRECT("direct"),
        SYNCHRONIZED_WORKER("worker");

        private final String label;

        Topology(String label) {
            this.label = label;
        }
    }

    private enum WaitMode {
        BLOCKING("blocking") {
            @Override
            WaitStrategy create() {
                return new BlockingWaitStrategy();
            }
        },
        YIELDING("yielding") {
            @Override
            WaitStrategy create() {
                return new YieldingWaitStrategy();
            }
        };

        private final String label;

        WaitMode(String label) {
            this.label = label;
        }

        abstract WaitStrategy create();
    }

    private record Scenario(String name, int events, long projectorDelayNanos, long stallOnceNanos) {}

    private record Result(
        Topology topology,
        WaitMode waitMode,
        Scenario scenario,
        ConcurrentHistogram ackLatency,
        ConcurrentHistogram natsLatency,
        double publishThroughput,
        double drainThroughput,
        long backpressure,
        long minimumRingCapacity,
        long minimumEdgeCapacity,
        long maximumProjectorLag,
        long eventsPublishedBeforeStallReleased,
        long processCpuNanos,
        long allocatedBytes,
        long producerAllocatedBytes,
        long gcCollections,
        long gcCollectionMillis,
        long wallNanos
    ) {}

    private static final class PublishStats {
        private long backpressure;
        private long minimumRingCapacity;
        private long minimumEdgeCapacity;
        private long maximumProjectorLag;

        private PublishStats(long ringCapacity, long edgeCapacity) {
            this.minimumRingCapacity = ringCapacity;
            this.minimumEdgeCapacity = edgeCapacity;
        }
    }

    private static final class AckProbe implements EventHandler<OutputEvent> {
        private final MarshallerHandler delegate;
        private final ConcurrentHistogram histogram = histogram();
        private volatile long processedSequence = -1;

        private AckProbe(MarshallerHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onEvent(OutputEvent event, long sequence, boolean endOfBatch) {
            delegate.onEvent(event, sequence, endOfBatch);
            histogram.recordValue(Math.min(HIGHEST_TRACKABLE_NS, System.nanoTime() - event.ingressNanos));
            processedSequence = sequence;
        }

        private void reset() {
            histogram.reset();
        }

        private long processedSequence() {
            return processedSequence;
        }
    }

    private static final class NatsProbe implements EventHandler<OutputEvent> {
        private final NatsBridgeHandler delegate;
        private final ConcurrentHistogram histogram = histogram();
        private volatile long processedSequence = -1;

        private NatsProbe(NatsBridgeHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onEvent(OutputEvent event, long sequence, boolean endOfBatch) {
            delegate.onEvent(event, sequence, endOfBatch);
            histogram.recordValue(Math.min(HIGHEST_TRACKABLE_NS, System.nanoTime() - event.ingressNanos));
            processedSequence = sequence;
        }

        private void reset() {
            histogram.reset();
        }

        private long processedSequence() {
            return processedSequence;
        }
    }

    private static final class ProjectorProbe implements EventHandler<OutputEvent> {
        private final ProjectorHandler delegate;
        private final AtomicBoolean stallStarted = new AtomicBoolean();
        private final AtomicLong latestPublished = new AtomicLong();
        private volatile long delayNanos;
        private volatile long stallOnceNanos;
        private volatile long projectedSequence = -1;
        private volatile long eventsPublishedBeforeStallReleased;

        private ProjectorProbe(ProjectorHandler delegate) {
            this.delegate = delegate;
        }

        private void configure(long delayNanos, long stallOnceNanos) {
            this.delayNanos = delayNanos;
            this.stallOnceNanos = stallOnceNanos;
            this.stallStarted.set(false);
            this.latestPublished.set(0L);
            this.eventsPublishedBeforeStallReleased = 0L;
        }

        @Override
        public void onEvent(OutputEvent event, long sequence, boolean endOfBatch) {
            if (stallOnceNanos > 0 && stallStarted.compareAndSet(false, true)) {
                LockSupport.parkNanos(stallOnceNanos);
                eventsPublishedBeforeStallReleased = latestPublished.get();
            }
            if (delayNanos > 0) {
                LockSupport.parkNanos(delayNanos);
            }
            delegate.onEvent(event, sequence, false);
            projectedSequence = sequence;
        }

        private void recordPublished(long count) {
            latestPublished.set(count);
        }

        private long projectedSequence() {
            return projectedSequence;
        }

        private long eventsPublishedBeforeStallReleased() {
            return eventsPublishedBeforeStallReleased;
        }
    }

    private static ConcurrentHistogram histogram() {
        return new ConcurrentHistogram(HIGHEST_TRACKABLE_NS, 3);
    }

    private static final class NoopPublisher<T> implements Publisher<T> {
        @Override
        public void publish(T message) {
            // Synchronous no-op.
        }

        @Override
        public void publish(String topic, T message) {
            // Synchronous no-op: isolate topology and handler construction cost from real network I/O.
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void connect() {
            // No-op.
        }

        @Override
        public void disconnect() {
            // No-op.
        }
    }

    /** Test-only model of the removed production synchronized handoff. */
    private static final class TestSynchronizedWorker implements EventHandler<OutputEvent>, AutoCloseable, Runnable {
        private static final long CLOSE_JOIN_MILLIS = 5_000L;

        private final EventQueue free;
        private final EventQueue ready;
        private final EventHandler<OutputEvent>[] delegates;
        private final Thread worker;
        private volatile boolean running = true;

        @SafeVarargs
        private TestSynchronizedWorker(int capacity, EventHandler<OutputEvent>... delegates) {
            this.free = new EventQueue(capacity);
            this.ready = new EventQueue(capacity);
            this.delegates = delegates;
            for (int i = 0; i < capacity; i++) {
                free.add(OutputEvent.newInstance());
            }
            this.worker = new Thread(this, "benchmark-synchronized-worker");
            this.worker.setDaemon(true);
            this.worker.start();
        }

        @Override
        public void onEvent(OutputEvent event, long sequence, boolean endOfBatch) throws InterruptedException {
            OutputEvent copy = free.take();
            copy.copyFrom(event);
            ready.put(copy);
        }

        @Override
        public void run() {
            long sequence = -1;
            while (running || !ready.isEmpty()) {
                OutputEvent event = null;
                try {
                    event = ready.take();
                    sequence++;
                    for (EventHandler<OutputEvent> delegate : delegates) {
                        delegate.onEvent(event, sequence, true);
                    }
                } catch (InterruptedException ex) {
                    if (!running && ready.isEmpty()) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } catch (Exception ignored) {
                    // The production comparison model swallowed delegate exceptions.
                } finally {
                    if (event != null) {
                        returnToFree(event);
                    }
                }
            }
        }

        private void returnToFree(OutputEvent event) {
            while (running) {
                try {
                    free.put(event);
                    return;
                } catch (InterruptedException ex) {
                    if (!running) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            free.offer(event);
        }

        private int remainingCapacity() {
            return ready.remainingCapacity();
        }

        @Override
        public void close() {
            running = false;
            worker.interrupt();
            try {
                worker.join(CLOSE_JOIN_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private static final class EventQueue {
            private final OutputEvent[] events;
            private int head;
            private int tail;
            private int size;

            private EventQueue(int capacity) {
                this.events = new OutputEvent[capacity];
            }

            private synchronized void add(OutputEvent event) {
                events[tail] = event;
                tail = next(tail);
                size++;
            }

            private synchronized void put(OutputEvent event) throws InterruptedException {
                while (size == events.length) {
                    wait();
                }
                events[tail] = event;
                tail = next(tail);
                size++;
                notifyAll();
            }

            private synchronized OutputEvent take() throws InterruptedException {
                while (size == 0) {
                    wait();
                }
                OutputEvent event = events[head];
                events[head] = null;
                head = next(head);
                size--;
                notifyAll();
                return event;
            }

            private synchronized boolean offer(OutputEvent event) {
                if (size == events.length) {
                    return false;
                }
                events[tail] = event;
                tail = next(tail);
                size++;
                notifyAll();
                return true;
            }

            private synchronized boolean isEmpty() {
                return size == 0;
            }

            private synchronized int remainingCapacity() {
                return events.length - size;
            }

            private int next(int index) {
                int next = index + 1;
                return next == events.length ? 0 : next;
            }
        }
    }

    private static final class CapturingThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();
        private final Thread[] threads = new Thread[8];

        private CapturingThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            int index = counter.getAndIncrement();
            Thread thread = new Thread(runnable, prefix + "-" + index);
            thread.setDaemon(true);
            threads[index] = thread;
            return thread;
        }

        private synchronized long[] threadIds() {
            int count = counter.get();
            long[] ids = new long[count];
            for (int i = 0; i < count; i++) {
                ids[i] = threads[i].threadId();
            }
            return ids;
        }
    }

    private record AllocationSnapshot(long producerBytes, long totalBytes) {
        private static AllocationSnapshot capture(long[] consumerThreadIds) {
            var base = ManagementFactory.getThreadMXBean();
            if (!(base instanceof com.sun.management.ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
                return new AllocationSnapshot(0L, 0L);
            }
            if (!bean.isThreadAllocatedMemoryEnabled()) {
                bean.setThreadAllocatedMemoryEnabled(true);
            }
            long producer = Math.max(0L, bean.getThreadAllocatedBytes(Thread.currentThread().threadId()));
            long total = producer;
            for (long threadId : consumerThreadIds) {
                total += Math.max(0L, bean.getThreadAllocatedBytes(threadId));
            }
            return new AllocationSnapshot(producer, total);
        }
    }

    private record GcSnapshot(long collections, long collectionTimeMillis) {
        private static GcSnapshot capture() {
            long collections = 0L;
            long time = 0L;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                collections += Math.max(0L, bean.getCollectionCount());
                time += Math.max(0L, bean.getCollectionTime());
            }
            return new GcSnapshot(collections, time);
        }
    }
}
