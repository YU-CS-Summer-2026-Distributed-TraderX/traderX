package finos.traderx.ordermatcher.config;

import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Non-gating async decorator for a NATS {@link Publisher} (state 009b throughput, lever 3).
 *
 * <p>The output-ring NATS bridge handlers (order / account-trade / position) run on Disruptor
 * consumer threads that GATE the BLP: the single producer cannot reclaim an output-ring slot until
 * its slowest consumer releases it. So a synchronous {@code publish()} (Jackson serialize + jnats
 * enqueue) on those threads backpressures the engine and, through the gateway ack, throttles the
 * ingress rate in lockstep. An A/B (swapping the publishers for no-ops) measured this capping
 * sustained throughput ~2.5x and peak ~1.8x.
 *
 * <p>This decorator severs that path WITHOUT changing the wire format: {@link #publish} only drops a
 * (topic, message) pair into a bounded in-memory queue and returns immediately — it never blocks the
 * ring thread — and a single dedicated drain thread performs the real {@code publish} (the delegate's
 * Jackson serialize + jnats publish) off the ring. The drain is single-threaded and FIFO, so
 * per-subject publish order is preserved exactly (the bridges never gave a cross-subject ordering
 * guarantee anyway — they are separate consumer threads).
 *
 * <p>Correctness: the matcher's NATS subjects are a best-effort, UI-only live feed — the durable
 * record is the journal + projector-&gt;DB, and no backend persists from these subjects by default
 * (trade-processor consumes the global {@code /trades}, fed only by the legacy handler, default off).
 * So under extreme overflow we DROP (counted) rather than block — the same best-effort contract the
 * jnats client already has past its pending limit — and UI consumers re-sync via REST. No
 * system-of-record state depends on NATS delivery, so decoupling cannot affect correctness.
 */
public final class AsyncPublisher<T> implements Publisher<T>, InitializingBean, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(AsyncPublisher.class);

    private record Item(String topic, Object message) {}

    private final Publisher<T> delegate;
    private final String name;
    private final BlockingQueue<Item> queue;
    private final Thread drainThread;
    private final LongAdder dropped = new LongAdder();
    private final LongAdder published = new LongAdder();
    private volatile boolean running;

    public AsyncPublisher(Publisher<T> delegate, String name, int capacity) {
        this.delegate = delegate;
        this.name = name;
        this.queue = new ArrayBlockingQueue<>(Math.max(1024, capacity));
        this.drainThread = new Thread(this::drainLoop, "nats-async-" + name);
        this.drainThread.setDaemon(true);
    }

    // ----- ring-thread fast path (never blocks) -------------------------------------------------

    @Override
    public void publish(String topic, T message) {
        // offer() returns false when the queue is full; we drop (best-effort bus) so the gating
        // consumer thread is never paced by NATS. enqueueBlocks-equivalent: stays a non-blocking add.
        if (running && !queue.offer(new Item(topic, message))) {
            dropped.increment();
        }
    }

    @Override
    public void publish(T message) {
        publish(null, message);
    }

    // ----- drain thread (off the ring: the real Jackson serialize + jnats publish) --------------

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            final Item item;
            try {
                item = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                // shutdown signal: loop condition drains whatever remains, then exits on running=false
                continue;
            }
            if (item == null) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                T payload = (T) item.message();
                if (item.topic() == null) {
                    delegate.publish(payload);
                } else {
                    delegate.publish(item.topic(), payload);
                }
                published.increment();
            } catch (PubSubException ex) {
                log.warn("async NATS publish failed on {} ({}): {}", item.topic(), name, ex.getMessage());
            } catch (RuntimeException ex) {
                log.warn("async NATS publish error on {} ({})", item.topic(), name, ex.toString());
            }
        }
    }

    // ----- lifecycle (Spring drives the outer bean; we drive the delegate) ----------------------

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public void connect() throws PubSubException {
        delegate.connect();
        running = true;
        drainThread.start();
        log.info("AsyncPublisher[{}] started; drain off the output ring, queue capacity {}",
            name, queue.remainingCapacity());
    }

    @Override
    public void disconnect() throws PubSubException {
        running = false;
        drainThread.interrupt();
        try {
            drainThread.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        long dropCount = dropped.sum();
        if (dropCount > 0) {
            log.warn("AsyncPublisher[{}] dropped {} messages under overflow (best-effort bus; UI re-syncs via REST)",
                name, dropCount);
        }
        delegate.disconnect();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        connect();
    }

    @Override
    public void destroy() throws Exception {
        disconnect();
    }

    public long dropped() {
        return dropped.sum();
    }

    public long published() {
        return published.sum();
    }
}
