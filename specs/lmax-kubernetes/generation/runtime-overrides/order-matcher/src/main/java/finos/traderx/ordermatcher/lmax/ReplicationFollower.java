package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.RingBuffer;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * FOLLOWER-mode input stream consumer. Subscribes to the primary's JetStream replication
 * subject and injects each received InputEvent into the local input ring so the local BLP
 * processes the identical event sequence without accepting HTTP gateway traffic.
 *
 * <p>Each event is injected via {@link #injectCallback} (a package-private method on
 * {@link LmaxEngine}) that claims a ring slot without registering a gateway ack — so the
 * local MarshallerHandler updates the in-memory read model but has no future to complete.
 *
 * <p>Lifecycle: {@link #start()} blocks until the follower has drained the JetStream stream
 * to the current tail (i.e. caught up to the primary), then signals readiness and switches to
 * live tail mode in a background daemon thread. {@link #stop()} unsubscribes cleanly.
 */
public final class ReplicationFollower {
    private static final Logger log = LoggerFactory.getLogger(ReplicationFollower.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);
    private static final Duration CATCH_UP_TIMEOUT = Duration.ofMillis(50);

    private final Connection conn;
    private final String podName;
    private final long startJetsStreamSeq;   // -1 = deliver all from beginning
    private final Runnable readinessCallback;
    private final InMemoryOrderReadModel readModel;

    /** Set by LmaxEngine after afterPropertiesSet() wires up the ring. */
    private volatile RingBuffer<InputEvent> inputRing;

    private final AtomicLong lastJetsStreamSeq = new AtomicLong(-1);
    private volatile JetStreamSubscription subscription;
    private volatile Thread followerThread;
    private volatile boolean running;

    // Pre-allocated ACK buffer — inject() runs on a single thread (tailLoop / drainCatchUp),
    // so this is safe without synchronization and avoids ByteBuffer.allocate() on every event.
    private final ByteBuffer ackBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);

    public ReplicationFollower(Connection conn, String podName, long startJetsStreamSeq,
                               InMemoryOrderReadModel readModel, Runnable readinessCallback) {
        this.conn = conn;
        this.podName = podName;
        this.startJetsStreamSeq = startJetsStreamSeq;
        this.readModel = readModel;
        this.readinessCallback = readinessCallback;
    }

    /** Called by LmaxEngine after the input ring is created. */
    void setInputRing(RingBuffer<InputEvent> ring) {
        this.inputRing = ring;
    }

    /** Current JetStream sequence position (for snapshot checkpointing). */
    public long lastJetsStreamSeq() {
        return lastJetsStreamSeq.get();
    }

    /**
     * Subscribe to JetStream and drain the stream until caught up, then signal readiness and
     * continue tailing in the background. Blocks until the catch-up phase completes.
     */
    public void start() {
        running = true;
        readModel.setReplaying(true);

        try {
            JetStream js = conn.jetStream();
            DeliverPolicy deliverPolicy;
            long startSeq = startJetsStreamSeq;
            if (startSeq > 0) {
                deliverPolicy = DeliverPolicy.ByStartSequence;
            } else {
                deliverPolicy = DeliverPolicy.All;
                startSeq = 1;
            }

            ConsumerConfiguration.Builder ccb = ConsumerConfiguration.builder()
                .durable("blp-follower-" + podName.replaceAll("[^a-zA-Z0-9-]", "-"))
                .deliverPolicy(deliverPolicy)
                .ackPolicy(AckPolicy.None)
                .filterSubject(NatsJournalReplicator.SUBJECT);
            if (deliverPolicy == DeliverPolicy.ByStartSequence) {
                ccb.startSequence(startSeq);
            }

            PushSubscribeOptions opts = PushSubscribeOptions.builder()
                .configuration(ccb.build())
                .build();
            subscription = js.subscribe(NatsJournalReplicator.SUBJECT, opts);
            log.info("Follower subscribed to {} from JetStream seq={}", NatsJournalReplicator.SUBJECT, startSeq);
        } catch (Exception ex) {
            log.warn("Could not subscribe to replication stream: {} — continuing without replication", ex.getMessage());
            readModel.setReplaying(false);
            readinessCallback.run();
            return;
        }

        // Drain catch-up messages (fast loop with short timeout to detect tail).
        drainCatchUp();
        readModel.setReplaying(false);
        log.info("Follower caught up at JetStream seq={} — signalling readiness", lastJetsStreamSeq.get());
        readinessCallback.run();

        // Continue tailing in daemon thread.
        followerThread = new Thread(this::tailLoop, "blp-replication-follower");
        followerThread.setDaemon(true);
        followerThread.start();
    }

    public void stop() {
        running = false;
        if (followerThread != null) followerThread.interrupt();
        try {
            if (subscription != null) subscription.unsubscribe();
        } catch (Exception ex) {
            log.warn("Unsubscribe error: {}", ex.getMessage());
        }
    }

    // ----- internal -----------------------------------------------------------------------

    private void drainCatchUp() {
        while (running) {
            try {
                Message msg = subscription.nextMessage(CATCH_UP_TIMEOUT);
                if (msg == null) break;  // no more pending messages → caught up
                inject(msg);
            } catch (Exception ex) {
                if (!running) break;
                log.warn("Catch-up receive error: {}", ex.getMessage());
                break;
            }
        }
    }

    private void tailLoop() {
        while (running) {
            try {
                Message msg = subscription.nextMessage(POLL_TIMEOUT);
                if (msg != null) inject(msg);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                if (!running) break;
                log.warn("Replication receive error: {}", ex.getMessage());
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void inject(Message msg) {
        byte[] data = msg.getData();
        if (data == null || data.length < NatsJournalReplicator.RECORD_BYTES) {
            return;
        }
        RingBuffer<InputEvent> ring = this.inputRing;
        if (ring == null) return;

        // Extract the Disruptor ring sequence embedded by the primary before injecting,
        // so we can ACK it after the event is on the ring (before BLP processes it, matching
        // the LMAX model where standby receives the event at the same wall-clock time).
        long disruptorSeq = NatsJournalReplicator.decodeDisruptorSeq(data);

        long seq;
        try {
            seq = ring.next();
        } catch (Exception ex) {
            log.warn("Ring claim failed: {}", ex.getMessage());
            return;
        }
        try {
            InputEvent e = ring.get(seq);
            NatsJournalReplicator.decode(data, e);
        } finally {
            ring.publish(seq);
        }
        lastJetsStreamSeq.set(msg.metaData().streamSequence());

        // ACK the primary so its onEvent() spin-wait can advance past this Disruptor sequence.
        // Uses a pre-allocated buffer to avoid heap allocation on every event (no-GC safe).
        if (disruptorSeq >= 0) {
            try {
                ackBuf.clear();
                ackBuf.putLong(disruptorSeq);
                conn.publish(NatsJournalReplicator.ACK_SUBJECT, ackBuf.array());
            } catch (Exception ex) {
                log.warn("ACK publish failed at disruptorSeq {}: {}", disruptorSeq, ex.getMessage());
            }
        }
    }
}
